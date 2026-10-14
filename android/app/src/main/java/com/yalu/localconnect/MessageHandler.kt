package com.yalu.localconnect

import android.content.Context
import android.net.Uri
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream

/**
 * 负责与电脑端的一切通信：建立连接、接收消息、上传文件。
 *
 * 这个类【不碰界面】——它只通过 [Callbacks] 把状态变化报告给调用方。
 * 好处：可以被独立测试，也不会因为持有 View 而导致内存泄漏。
 *
 * 注意 session 由本类自己管理，不作为构造参数传入：
 * 因为「连接」本身就属于通信职责，而且连接是异步建立的，
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

        private const val CHUNK_SIZE = 64 * 1024   // 单帧大小，须与服务端一致
    }

    /** 状态回调。实现方（通常是 Activity）负责把事件反映到界面上。 */
    interface Callbacks {
        /** 日志文本，调用方自行决定显示到哪 */
        fun onLog(message: String)

        /** 连接建立 / 断开 / 失败 */
        fun onConnectionChanged(connected: Boolean)

        /** 上传进度，percent 为 0~100 */
        fun onUploadProgress(percent: Int, sentBytes: Long)
    }

    // HttpClient 内部有连接池和线程池，一个实例就够了
    private val client = HttpClient(CIO) { install(WebSockets) }

    // 唯一跨协程共享的状态：连接、上传、清理都要用它
    private var session: DefaultClientWebSocketSession? = null

    private var uploadJob: Job? = null

    /** 建立会话并持续接收服务端消息。不阻塞调用方。 */
    fun connect(callbacks: Callbacks) {
        scope.launch { connectAndReceive(callbacks) }
    }

    /**
     * 上传一个文件。内部会检查连接状态，所以调用方不必先判断。
     * 会取消上一次未完成的上传，避免两个上传同时往一条连接里写。
     */
    fun uploadFile(uri: Uri, callbacks: Callbacks) {
        uploadJob?.cancel()
        uploadJob = scope.launch { doUpload(uri, callbacks) }
    }

    /** 发送一批缩略图。会取消上一次未完成的发送，避免两批数据交错。 */
    fun sendThumb(list: List<ThumbnailItem>, callbacks: Callbacks) {
        uploadJob?.cancel()
        uploadJob = scope.launch { doUpload(list, callbacks) }
    }

    /** 释放网络资源。连接不随 Activity 消失，必须显式关闭。 */
    fun close() {
        uploadJob?.cancel()
        scope.launch { session?.close() }
        client.close()
    }

    // ========================================================================
    //  内部实现
    // ========================================================================

    private suspend fun connectAndReceive(callbacks: Callbacks) {
        callbacks.onLog("准备连接 $SERVER_URL")
        try {
            // 挂起函数：完成 WebSocket 握手后才返回会话
            val ws = client.webSocketSession(SERVER_URL)
            session = ws
            callbacks.onLog("✅ 已连接（WebSocket 会话已建立）")
            callbacks.onConnectionChanged(true)

            // incoming 是通道：没有帧时协程在此挂起，不占线程；通道满时发送方也挂起
            for (frame in ws.incoming) {
                when (frame) {
                    is Frame.Text -> handleText(frame.readText(), callbacks)
                    is Frame.Close -> callbacks.onLog("服务端关闭了连接")
                    else -> Unit
                }
            }
            callbacks.onLog("连接已结束")
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
                "ack" -> callbacks.onLog("✅ 服务端确认落盘: ${obj.optString("saved")}")
                "hello" -> callbacks.onLog("   (握手完成)")
                // 缩略图相关消息暂不处理，等实现阶段一后再加
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
     * 全部发完后发 thumb_done。id 用于第二阶段按 id 请求原图。
     */
    private suspend fun doUpload(items: List<ThumbnailItem>, callbacks: Callbacks) {
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

    private suspend fun doUpload(uri: Uri, callbacks: Callbacks) {
        val ws = session
        if (ws == null) {
            callbacks.onLog("⚠️ 还没有连上服务端")
            return
        }

        try {
            // 读文件和写网络都会阻塞，必须切到 IO 线程，否则界面卡死甚至 ANR
            withContext(Dispatchers.IO) {
                val (fileName, mimeType) = queryFileInfo(uri)
                val totalSize = queryFileSize(uri)
                callbacks.onLog("开始上传 $fileName (${formatSize(totalSize)}, $mimeType)")

                // use{} 保证流一定被关闭。openInputStream 可能返回 null，用 ?: 处理。
                context.contentResolver.openInputStream(uri)?.use { raw ->
                    // 加缓冲，避免底层流每次只返回少量字节
                    val input = BufferedInputStream(raw, CHUNK_SIZE)

                    // ① 宣告
                    val begin = JSONObject().apply {
                        put("type", "upload_begin")
                        put("name", fileName)
                        put("size", totalSize)
                        put("mime", mimeType)
                    }
                    ws.send(Frame.Text(begin.toString()))

                    // ② 分块发送。sent 是局部变量：进度随函数存在，无需类字段。
                    val buffer = ByteArray(CHUNK_SIZE)
                    var sent = 0L
                    var lastReportAt = 0L

                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break

                        // copyOf(n) 只取本次真正读到的字节。若整块发出，
                        // 最后一块会带上上一轮的残留数据，导致文件损坏。
                        ws.send(Frame.Binary(true, buffer.copyOf(n)))
                        sent += n

                        // 进度节流：每 256KB 报一次；末次必报，否则进度条停不到 100%
                        if (sent - lastReportAt >= 256 * 1024 || sent == totalSize) {
                            lastReportAt = sent
                            val percent = if (totalSize > 0) (sent * 100 / totalSize).toInt() else 0
                            callbacks.onUploadProgress(percent, sent)
                        }
                    }

                    // ③ 宣告结束。ack 由 connectAndReceive 那个协程接收，两者互不阻塞。
                    ws.send(Frame.Text(JSONObject().put("type", "upload_end").toString()))
                    callbacks.onLog("📤 已发送完毕，共 $sent 字节，等待服务端确认…")
                } ?: callbacks.onLog("❌ 打开文件失败")
            }
        } catch (e: Exception) {
            callbacks.onLog("❌ 上传失败: ${e.javaClass.simpleName} / ${e.message}")
        }
    }

    // ========================================================================
    //  工具
    // ========================================================================

    /**
     * 取文件名和 MIME 类型。
     * 选择器返回 content:// URI 而非文件路径，所以只能通过 contentResolver 查询。
     */
    private fun queryFileInfo(uri: Uri): Pair<String, String> {
        var name = "unknown"
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = cursor.getString(idx) ?: name
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "查询文件名失败: ${e.message}")
        }
        if (name == "unknown") name = uri.lastPathSegment ?: "unknown"
        return name to (context.contentResolver.getType(uri) ?: "application/octet-stream")
    }

    /** 取文件大小；查不到返回 -1。 */
    private fun queryFileSize(uri: Uri): Long = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                // 该列可能为空，直接 getLong 会抛异常
                if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else -1L
            } else -1L
        } ?: -1L
    } catch (e: Exception) {
        -1L
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 0 -> "大小未知"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    }
}
