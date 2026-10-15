package com.yalu.localconnect

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 负责与电脑端的全部通信：建立连接、接收消息、发送缩略图。
 *
 * 这个类【不碰界面】——只通过 [Callbacks] 把状态变化报告给调用方。
 * 好处：可以独立测试，也不会因为持有 View 而泄漏内存。
 *
 * session 由本类自己管理而不是作为构造参数：连接是异步建立的，
 * 若要求构造时就传入会话，调用方将无法在连接建立前创建本类。
 */
class MessageHandler(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "MessageHandler"

        // 模拟器用 10.0.2.2（宿主机别名）；真机填电脑局域网 IP，如 10.151.3.98
        private const val SERVER_URL = "ws://10.0.2.2:8000"

        private const val CHUNK = 1024 * 1024 // 1 MB

        // 断开后等多久再连。固定间隔，不做退避 —— 局域网里电脑随时可能开机。
        private const val RETRY_DELAY_MS = 3_000L
    }

    /** 状态回调。实现方（通常是 Activity）负责把事件反映到界面上。 */
    interface Callbacks {
        /** 日志文本，调用方自行决定显示到哪 */
        fun onLog(message: String)

        /** 连接建立 / 断开 / 失败 */
        fun onConnectionChanged(connected: Boolean)
    }

    // HttpClient 内部有连接池和线程池，一个实例就够了
    private val client = HttpClient(CIO) { install(WebSockets) }

    // 跨协程共享的状态：连接、发送、清理都要用它
    private var session: DefaultClientWebSocketSession? = null

    // 保持连接的循环。close() 靠取消它来停止重连
    private var connectJob: Job? = null

    private var sendJob: Job? = null

    // 最近发出去的那批缩略图。重连后靠它把电脑端的界面补回来
    private var lastThumbs: List<ThumbnailItem>? = null

    /**
     * 建立会话并持续接收服务端消息，断开后自动重连，直到 [close] 被调用。
     *
     * 结构上分两层：外层管「保持连接」，[connectAndReceive] 管「一次连接」。
     */
    fun connect(callbacks: Callbacks) {
        connectJob?.cancel() // 重复调用不能留下两个重连循环
        connectJob = scope.launch {
            while (true) {
                connectAndReceive(callbacks) // 它一返回，就代表这次连接结束了
                callbacks.onLog("⏳ ${RETRY_DELAY_MS / 1000} 秒后重连…")
                delay(RETRY_DELAY_MS) // 被取消时从这里抛出，循环随之结束
            }
        }
    }

    /** 发送一批缩略图。会取消上一次未完成的发送，避免两批数据交错。 */
    fun sendThumb(list: List<ThumbnailItem>, callbacks: Callbacks) {
        lastThumbs = list // 记住它：下次重连成功后要原样重发
        sendJob?.cancel()
        sendJob = scope.launch {
            try {
                doSendThumbnails(list, callbacks)
            } catch (e: CancellationException) {
                throw e // 取消是正常控制流，不能当失败吞掉
            } catch (e: Exception) {
                // 发送途中连接断掉很常见，重连循环会接手；别让它变成崩溃
                callbacks.onLog("❌ 缩略图发送失败: ${e.javaClass.simpleName} / ${e.message}")
            }
        }
    }

    /** 释放网络资源。连接不随 Activity 消失，必须显式关闭。 */
    fun close() {
        connectJob?.cancel() // 必须先停重连循环，否则它马上又连回来
        sendJob?.cancel()
        scope.launch { session?.close() }
        client.close()
    }

    // ========================================================================
    //  内部实现
    // ========================================================================

    /** 一条连接的完整生命周期：连上 → 收消息 → 断开。返回即代表这次连接结束。 */
    private suspend fun connectAndReceive(callbacks: Callbacks) {
        callbacks.onLog("准备连接 $SERVER_URL")
        try {
            // 挂起函数：完成 WebSocket 握手后才返回会话
            val ws = client.webSocketSession(SERVER_URL)
            session = ws
            callbacks.onLog("✅ 已连接（WebSocket 会话已建立）")
            callbacks.onConnectionChanged(true)

            // 连上之后把上次那批缩略图补回去。
            // 首次连接时 lastThumbs 是 null，直接跳过 —— 不需要额外的「第几次连接」标志。
            // 服务端重启过的话它的内存缓存是空的，正好靠这次补回来。
            val cached = lastThumbs
            if (cached != null) {
                callbacks.onLog("🔄 重连成功，重发 ${cached.size} 张缩略图")
                sendThumb(cached, callbacks)
            }

            // incoming 是通道：没有帧时协程在此挂起，不占线程；通道满时发送方也挂起
            for (frame in ws.incoming) {
                when (frame) {
                    is Frame.Text -> handleText(frame.readText(), callbacks)
                    is Frame.Close -> callbacks.onLog("服务端关闭了连接")
                    else -> Unit
                }
            }
            callbacks.onLog("连接已结束")
        } catch (e: CancellationException) {
            // close() 取消协程时走这里。若被下面的 Exception 吞掉，
            // 外层 while 会当成「这次连接正常结束」而再绕一圈
            throw e
        } catch (e: Exception) {
            callbacks.onLog("❌ 连接失败: ${e.javaClass.simpleName} / ${e.message}")
        } finally {
            session = null
            callbacks.onConnectionChanged(false)
        }
    }

    /** 处理服务端的文本消息。未知类型只记录，不影响后续消息。 */
    private fun handleText(text: String, callbacks: Callbacks) {
        callbacks.onLog("收到服务端消息: $text")
        try {
            val obj = JSONObject(text)
            when (obj.optString("type")) {
                "hello" -> callbacks.onLog("   (握手完成)")
                "fetch_original" -> {
                    val thumbId = obj.optString("id")
                    // ★ 必须另起协程：本函数是被 ws.incoming 的接收循环同步调用的，
                    //   在这里读几 MB 再发几十个帧，接收循环整个被堵住，期间收不到任何消息
                    scope.launch {
                        try {
                            doSendFullPicture(thumbId, callbacks)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            callbacks.onLog(
                                "❌ 原图发送失败: ${e.javaClass.simpleName} / ${e.message}"
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // 解析外部数据必须兜住：协程里未捕获的异常会导致崩溃
            Log.w(TAG, "解析消息失败: ${e.message}")
        }
    }

    /**
     * 发送一批缩略图。
     *
     * 每张按协议发三条消息：thumb_begin(文本) → 二进制帧 → thumb_end(文本)。
     * 全部发完后发 thumb_done。id 用于第二阶段按 id 回传原图。
     */
    private suspend fun doSendThumbnails(items: List<ThumbnailItem>, callbacks: Callbacks) {
        val ws = session
        if (ws == null) {
            callbacks.onLog("⚠️ 还没有连上服务端")
            return
        }

        callbacks.onLog("开始发送 ${items.size} 张缩略图")
        for (item in items) {
            // ① 宣告这一张
            ws.send(Frame.Text(JSONObject().apply {
                put("type", "thumb_begin")
                put("id", item.id)
                put("name", item.name)
                put("date", item.dateAdded)
                put("size", item.bitmap.size)   // 便于服务端校验
            }.toString()))

            // ② 缩略图只有几 KB，一帧发完即可，无需分块
            ws.send(Frame.Binary(true, item.bitmap))

            // ③ 这一张结束。带上 id，服务端靠它清掉「当前正在收的 id」
            ws.send(Frame.Text(JSONObject().apply {
                put("type", "thumb_end")
                put("id", item.id)
            }.toString()))

            callbacks.onLog("  已发 ${item.name} (${item.bitmap.size} 字节)")
        }

        // ④ 整批结束
        ws.send(Frame.Text(JSONObject().apply {
            put("type", "thumb_done")
            put("count", items.size)
        }.toString()))
        callbacks.onLog("✅ 缩略图发送完毕，共 ${items.size} 张")
    }

    private suspend fun doSendFullPicture(thumbId: String, callbacks: Callbacks) {
        val ws = session
        if (ws == null) {
            callbacks.onLog("⚠️ 还没有连上服务端")
            return
        }
        val rawId = thumbId.toLongOrNull()
        if (rawId == null) {
            callbacks.onLog("⚠️ 非法 id: $thumbId")
            return
        }
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, rawId)
        val input = context.contentResolver.openInputStream(uri)
        if (input == null) {
            callbacks.onLog("❌ 打不开原图: $thumbId（可能已删除）")
            return
        }
        val total = context.contentResolver.query(
            uri, arrayOf(MediaStore.Images.Media.SIZE), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else -1L
        } ?: -1L

        ws.send(Frame.Text(JSONObject().apply {
            put("type", "original_begin")
            put("id", thumbId)
            put("size", total)
        }.toString()))

        // ② 分块搬运
        var sent = 0L
        input.use { stream ->
            val buffer = ByteArray(CHUNK)
            while (true) {
                val n = stream.read(buffer)
                if (n <= 0) break
                // ★ 每块都按「本次实际读到的字节数」截断。
                //   read() 不保证填满缓冲区，直接发整个 buffer 会多送垃圾字节，
                //   服务端按 size 校验就会失败、剪贴板里的图也是坏的
                ws.send(Frame.Binary(true, buffer.copyOf(n)))
                sent += n
            }
        }

        // ③ 宣告结束
        ws.send(Frame.Text(JSONObject().apply {
            put("type", "original_end")
            put("id", thumbId)
        }.toString()))
        callbacks.onLog("✅ 原图发送完毕: $thumbId（$sent 字节）")
    }
}
