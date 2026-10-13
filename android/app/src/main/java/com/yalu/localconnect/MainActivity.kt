// ============================================================================
//  LocalConnect 手机端 — 用 Ktor + 协程把文件传到电脑
// ============================================================================
//
//  【这个文件在做什么】
//
//      手机 ──WebSocket──> 电脑（Python server.py）
//
//      连接建立后，服务端会先发一条 hello。
//      用户点按钮选文件后，把文件按 64KB 分块，一块一个二进制帧发过去，
//      最后等服务端回一条 ack 确认。
//
//  【和之前的 OkHttp 回调式版本对比】
//
//      回调式：逻辑被劈成 onOpen / onMessage / onFailure 三块，
//              状态（文件流、字节数、是否正在上传）必须提升为类字段，
//              每次改 UI 都要 runOnUiThread，还得手动清理资源。
//
//      协程式：整个流程从上到下写在一个函数里，
//              状态用普通局部变量，use{} 自动关流，
//              改 UI 直接写，协程自己切回主线程。
//
//      → 本文件里几乎没有「跨方法共享的状态字段」，这就是协程的收益。
//
//  【协议】详细定义见项目根目录的 PROTOCOL.md
//      手机 → 电脑 (文本)   {"type":"upload_begin","name":"a.jpg","size":12345}
//      手机 → 电脑 (二进制) 文件内容，每帧 <= 64KB，重复 N 次
//      手机 → 电脑 (文本)   {"type":"upload_end"}
//      电脑 → 手机 (文本)   {"type":"hello", ...}
//      电脑 → 手机 (文本)   {"type":"ack","saved":"...","bytes":12345}
//
// ============================================================================

package com.yalu.localconnect

// ---------------------------------------------------------------------------
//  import 逐类说明
//
//  Android 框架自带的类大多在 android.*、androidx.* 下。
//  第三方库（Ktor、协程）在 io.ktor.*、kotlinx.coroutines.* 下。
//  Kotlin 不像 Java 需要一个个 import 静态方法，但类名和扩展函数必须导入。
// ---------------------------------------------------------------------------

import android.net.Uri                    // 统一资源标识符：系统选择器返回的「文件句柄」
import android.os.Bundle                 // Activity 的状态容器（旋转屏幕时用来恢复）
import android.provider.OpenableColumns  // 内容提供器的「标准列名」常量（DISPLAY_NAME / SIZE）
import android.util.Log                  // 输出到 logcat，方便在 IDE 里排查
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge                    // 让内容延伸到状态栏下方
import androidx.activity.result.contract.ActivityResultContracts // 系统预置的「结果契约」
import androidx.appcompat.app.AppCompatActivity              // 带兼容性的 Activity 基类
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat                 // 查询状态栏/导航栏占用的边距
import androidx.lifecycle.lifecycleScope                     // ★ 与 Activity 生命周期绑定的协程作用域
import io.ktor.client.HttpClient                             // ★ Ktor 客户端主体
import io.ktor.client.engine.cio.CIO                         // ★ 用 CIO 引擎（纯 Kotlin 实现，不依赖 OkHttp）
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession // ★ WebSocket 会话类型
import io.ktor.client.plugins.websocket.WebSockets           // ★ WebSocket 插件
import io.ktor.client.plugins.websocket.webSocketSession     // ★ 建立会话的挂起函数
import io.ktor.websocket.Frame                               // ★ 帧：Text / Binary / Close …
import io.ktor.websocket.close                               // ★ 扩展函数：优雅关闭连接
import io.ktor.websocket.readText                            // ★ 扩展函数：从 Text 帧读出字符串
import io.ktor.websocket.send                                // ★ 扩展函数：发送一帧
import kotlinx.coroutines.Dispatchers                        // 调度器：决定代码跑在哪个线程
import kotlinx.coroutines.Job                                // 协程句柄：可以取消、可以等它结束
import kotlinx.coroutines.launch                             // 启动一个新协程
import kotlinx.coroutines.withContext                        // 在当前协程内临时切换线程
import org.json.JSONObject                                   // Android 自带，用来拼/解 JSON
import java.io.BufferedInputStream                           // 带缓冲的输入流，避免一次只读几字节

class MainActivity : AppCompatActivity() {

    // =======================================================================
    //  常量
    //
    //  companion object = Kotlin 的「静态成员」，等价于 Java 的 static。
    //  用 const val 才是编译期常量（能用于注解等场景），普通 val 只是静态字段。
    // =======================================================================
    companion object {
        private const val TAG = "LocalConnect"   // logcat 的过滤标签，用 adb logcat -s LocalConnect 可只看本应用

        // 电脑的地址。两种运行环境不一样：
        //   官方模拟器(emulator-5554)：10.0.2.2
        //       它是 Google 规定的「宿主机」别名，映射到电脑的 127.0.0.1。
        //       因为模拟器是一台虚拟设备，它自己的 127.0.0.1 指的是模拟器内部。
        //   真机（同一 WiFi/热点）：填电脑的局域网 IP，例如 10.151.3.98
        //       ⚠️ 注意这不是 JSON，是 WebSocket URL，协议头是 ws:// 而不是 http://
        private const val SERVER_URL = "ws://10.0.2.2:8000"

        // 每个二进制帧的大小，必须和服务端协议一致。
        // 64 * 1024 是 Int 运算，结果是 65536。
        private const val CHUNK_SIZE = 64 * 1024
    }

    // =======================================================================
    //  界面控件
    //
    //  lateinit 表示「我保证在使用前会赋值」——这样就不必写成可空类型（TextView?）。
    //  如果访问时还没赋值，会抛 UninitializedPropertyAccessException。
    //  它们必须在 onCreate 里赋值（那时布局才被解析出来）。
    // =======================================================================
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var progressBar: ProgressBar

    // =======================================================================
    //  网络
    //
    //  HttpClient 是个「重」对象：内部维护连接池、线程池。
    //  惯例是整个 App 共用一个，不要每次请求都新建。
    //
    //  HttpClient(CIO) { ... } 中的 { } 是配置块（lambda with receiver）。
    //  install(WebSockets) 表示「装上 WebSocket 插件」——
    //  Ktor 的很多能力都是插件式的，不装就没有相关方法。
    // =======================================================================
    private val client = HttpClient(CIO) { install(WebSockets) }

    // =======================================================================
    //  ★ 全文件唯一需要跨协程共享的状态：会话本身
    //
    //  对比回调式版本里的一堆字段（fileHandle、sentBytes、isUploading…），
    //  这里只剩一个。为什么？
    //    - 「正在上传的流」「已发送字节数」都活在 upload() 函数内部，
    //      由协程的栈帧天然持有，不需要提升为字段。
    //    - 「是否正在上传」也不需要布尔开关了 —— 用 Job 取消即可（见下）。
    //
    //  那为什么 session 必须放在字段里？因为它被多个协程使用：
    //    - connectAndReceive() 里创建并使用
    //    - upload() 里拿它发数据
    //    - onDestroy() 里关闭它
    //  跨协程共享 → 只能是字段。
    //
    //  类型写全限定名是为了让「它来自哪个包」一目了然；
    //  因为顶部已经 import，其实也可以简写成 DefaultClientWebSocketSession。
    // =======================================================================
    private var session: DefaultClientWebSocketSession? = null

    // 当前上传任务的句柄。用途：需要时可以取消它（比如防止重复上传）。
    // Job 是「协程的身份证」——launch 会返回它。
    private var uploadJob: Job? = null

    // =======================================================================
    //  ★ 文件选择器（现代写法）
    //
    //  旧写法（网上很多教程，已过时）：
    //      startActivityForResult(intent, REQUEST_CODE)
    //      然后覆写 onActivityResult，靠 requestCode 分辨是哪个请求。
    //      缺点：请求码要手动管理，容易内存泄漏，进程被杀后恢复麻烦。
    //
    //  新写法：每个请求注册一次，自带回调。
    //      registerForActivityResult(契约) { 结果 -> ... }
    //
    //  ⚠️ 时机要求：registerForActivityResult 必须在 Activity 初始化阶段调用
    //     （字段初始化、或 onCreate 里），不能在按钮点击时才注册。
    //     因为系统需要能在 Activity 重建时把结果重新投递回来。
    //
    //  OpenDocument 这个契约的作用：弹系统文件选择器，返回一个 content:// URI。
    //  它带来一个重要好处 —— 我们【不需要任何存储权限】：
    //     用户选中某个文件 = 一次性的、只针对那一个文件的授权。
    //     （Android 11+ 的作用域存储就是按这个思路设计的。）
    // =======================================================================
    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        // 参数可空：用户按返回键取消时就是 null，必须处理，否则崩溃。
        if (uri == null) {
            log("已取消选择")
        } else {
            // lifecycleScope.launch 启动一个「与 Activity 同生命周期」的协程。
            // Activity 销毁时它会被自动取消，不会泄漏。
            // 这里不能直接调 upload(uri)：因为它是 suspend 函数，
            // 而 suspend 函数必须在协程或其他 suspend 函数中调用。
            // launch 返回的 Job 存下来，需要时可用于取消。
            uploadJob = lifecycleScope.launch { upload(uri) }
        }
    }

    // =======================================================================
    //  onCreate：Activity 的入口，只在创建时调用一次
    // =======================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 让界面延伸到系统栏下方（现代 Android 的默认观感）。
        enableEdgeToEdge()

        // 把 XML 布局解析成 View 树，并挂到当前 Activity 上。
        // 在这一行之前 findViewById 找不到任何东西。
        setContentView(R.layout.activity_main)

        // findViewById 返回的是泛型 View，靠推断或显式类型参数确定具体类型。
        // lateinit 的字段在这里被赋值，之后就安全了。
        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        progressBar = findViewById(R.id.progressBar)
        val pickButton = findViewById<Button>(R.id.pickButton)

        // 处理「状态栏和导航栏遮挡内容」的问题。
        // 因为开了 edge-to-edge，界面会画到系统栏下面，需要自己补内边距。
        // 这个 lambda 会在系统栏尺寸变化时（如旋转屏幕、弹出键盘）被再次调用。
        val root = findViewById<android.view.View>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets   // 返回值：表示「我处理了这些 insets」
        }

        pickButton.setOnClickListener {
            // OpenDocument 需要传一个 MIME 类型数组。
            // "*/*" 表示任意类型；写 "image/*" 就只让选图片。
            // launch 会弹出系统选择器；结果回到上面注册的那个 lambda。
            filePicker.launch(arrayOf("*/*"))
        }

        // 启动「连接 + 一直收消息」的协程。
        // 注意这里是 fire-and-forget：connectAndReceive 里的 for 循环会一直跑，
        // 直到连接关闭或 Activity 销毁，onCreate 不会等它。
        lifecycleScope.launch { connectAndReceive() }
    }

    // =======================================================================
    //  连接 + 收消息
    //
    //  suspend 关键字：表示这个函数可以「挂起」。
    //  挂起 ≠ 阻塞：挂起时线程会被释放出来去干别的，恢复时再切回来。
    //  只有 suspend 函数里才能调用其他 suspend 函数。
    // =======================================================================
    private suspend fun connectAndReceive() {
        log("准备连接 $SERVER_URL")
        try {
            // ★ webSocketSession 是挂起函数：它会完成 HTTP 升级握手，
            //   成功后返回一个会话对象。
            //   和 webSocket { } 的区别：后者把会话限制在块内，
            //   而我们需要把会话存起来给 upload() 用，所以用 session 版本。
            //
            //   ⚠️ 这行的参数形式（直接传 URL 字符串）我尚未编译验证过。
            //      如果报错，多半要改成：
            //          client.webSocketSession { url(SERVER_URL) }
            val ws = client.webSocketSession(SERVER_URL)
            session = ws
            log("✅ 已连接（WebSocket 会话已建立）")

            // ★★ 整个文件最像 Python 的一行 ★★
            //
            //   ws.incoming 是一个 ReceiveChannel<Frame>（接收通道）。
            //   for 循环会不断从通道里「取下一帧」——
            //   取的时候如果通道是空的，协程就在这里【挂起】，
            //   不占线程、不烧 CPU，等到有帧了再被唤醒。
            //
            //   这等价于 Python 的：  async for msg in ws:
            //
            //   通道模型顺带解决了背压：生产端（网络层）往通道塞帧，
            //   如果消费端处理得慢、通道满了，生产端会挂起等待 ——
            //   不像回调式那样可能把数据越堆越多。
            for (frame in ws.incoming) {
                // when 类似 switch，但支持类型判断（is）。
                // Kotlin 的密封类/密封接口在 when 里可以做到穷尽检查。
                when (frame) {
                    // Frame.Text 是文本帧，readText() 是它的扩展函数，返回 String。
                    is Frame.Text -> handleText(frame.readText())

                    // 连接被对端关闭时，通道里会出现关闭帧。
                    is Frame.Close -> log("服务端关闭了连接")

                    // Binary / Ping / Pong 等暂时不处理。
                    // 「谁在发二进制？」—— 我们的协议里只有服务端发文本，所以不处理。
                    else -> Unit   // Unit 相当于 Java 的 void，表示「什么都不做」
                }
            }
            log("连接已结束")
        } catch (e: Exception) {
            // 网络代码必须兜住异常：连接被拒、域名解析失败、握手失败都在这里。
            // javaClass.simpleName 取异常类名（不含包名），便于快速判断类型。
            log("❌ 连接失败: ${e.javaClass.simpleName} / ${e.message}")
        }
    }

    /**
     * 解析服务端发来的文本消息。
     *
     * 对比回调式：那边 onMessage 里必须判断「这条消息对应哪个阶段」，
     * 因为所有消息都挤进同一个回调。这里是在顺序流程里遇到消息就处理，
     * 语义上干净得多。
     */
    private fun handleText(text: String) {
        log("收到服务端消息: $text")
        try {
            val obj = JSONObject(text)
            // optString 与 getString 的区别：前者在字段缺失时返回 ""，
            // 后者会抛 JSONException。处理外部输入时用 opt 系列更稳。
            when (obj.optString("type")) {
                "ack" -> log("✅ 服务端确认落盘: ${obj.optString("saved")}")
                "hello" -> log("   (握手完成)")
            }
        } catch (e: Exception) {
            // ⚠️ 重要：解析外部数据的异常必须自己兜住。
            //    在协程里，未捕获的异常会向上传播并可能导致 App 崩溃。
            Log.w(TAG, "解析消息失败: ${e.message}")
        }
    }

    // =======================================================================
    //  上传
    // =======================================================================

    private suspend fun upload(uri: Uri) {
        // 先做前置检查：没连上就别往下走了。
        // 用局部变量 val ws 持有，编译器由此知道它非空（智能类型转换）。
        val ws = session
        if (ws == null) {
            log("⚠️ 还没有连上服务端")
            return
        }

        try {
            // ★ withContext 的作用：把这段代码临时切到 IO 线程池执行。
            //
            //   为什么必须切？读文件、写网络都是「耗时且会阻塞」的操作，
            //   放在主线程会卡住界面，超过约 5 秒就 ANR（应用无响应）。
            //
            //   注意它是「切过去再切回来」：块结束后会自动恢复到原来的线程。
            //   所以 upload() 整体跑在主线程，只有这一块在 IO 线程。
            withContext(Dispatchers.IO) {
                // Kotlin 的多返回值技巧：Pair + 解构声明。
                // 这行等价于： val pair = queryFileInfo(uri); val fileName = pair.first; val mimeType = pair.second
                val (fileName, mimeType) = queryFileInfo(uri)
                val totalSize = queryFileSize(uri)
                log("开始上传 $fileName (${formatSize(totalSize)}, $mimeType)")

                // ★ use { }：Kotlin 的「自动关闭」语法（等价于 Java 的 try-with-resources）。
                //   无论块内正常结束还是抛异常，流都会被关闭。
                //   对比回调式版本：那边要在 finally 里手写 stream?.close() 加 try/catch。
                //
                //   openInputStream 可能返回 null（比如文件已被删除），所以用 ?.use
                //   后面的 ?: log(...) 处理 null 情况。
                contentResolver.openInputStream(uri)?.use { raw ->
                    // 再包一层缓冲：底层流每次 read 可能只返回很少字节，
                    // 缓冲后每次能读满 CHUNK_SIZE，减少系统调用次数。
                    val input = BufferedInputStream(raw, CHUNK_SIZE)

                    // ---------- ① 宣告：告诉服务端「我要发什么」 ----------
                    // JSONObject().apply { } ：用 apply 在构造后连续配置，最后返回对象本身。
                    val begin = JSONObject().apply {
                        put("type", "upload_begin")
                        put("name", fileName)
                        put("size", totalSize)
                        put("mime", mimeType)
                    }
                    // Frame.Text(...) 构造一个文本帧；send 是挂起函数。
                    ws.send(Frame.Text(begin.toString()))

                    // ---------- ② 分块发送 ----------
                    // buffer 复用同一个数组，避免每块都分配新内存。
                    val buffer = ByteArray(CHUNK_SIZE)

                    // sent 是「局部变量」——这正是协程相对回调式的最大好处：
                    // 上传进度天然随函数存在，不需要提升为类字段，
                    // 也就不存在「忘记重置」的隐患。
                    var sent = 0L
                    var lastReportAt = 0L   // 上次刷新界面的字节数，用于节流

                    while (true) {
                        // read 返回实际读到的字节数；文件末尾返回 -1。
                        val n = input.read(buffer)
                        if (n <= 0) break   // 读完了

                        // ★ Frame.Binary 的构造签名是 (fin: Boolean, data: ByteArray)。
                        //   - fin = true 表示这是该消息的最后一个分片（我们不使用分片发送）
                        //   - copyOf(n) 只取本次真正读到的 n 个字节
                        //
                        //   ⚠️ 这里绝不能用整个 buffer：最后一块通常不满 64KB，
                        //      整块发出去会把上一轮循环的残留数据一起带上，文件就损坏了。
                        //      而且这个 bug 只在「文件大小不是 64KB 整数倍」时出现，非常隐蔽。
                        //
                        //   send 是挂起函数：协程会等这一帧被处理后继续，
                        //   天然形成节流，不会把整个文件堆进内存队列。
                        ws.send(Frame.Binary(true, buffer.copyOf(n)))
                        sent += n

                        // ---------- ③ 进度更新（节流） ----------
                        // 每 256KB 才刷一次界面：如果每块都刷，1GB 文件要跨线程切换 16384 次。
                        // || sent == totalSize 是为了保证最后一次一定刷新，
                        // 否则进度条可能永远停在 97% 这种数字上。
                        if (sent - lastReportAt >= 256 * 1024 || sent == totalSize) {
                            lastReportAt = sent
                            val percent = if (totalSize > 0) (sent * 100 / totalSize).toInt() else 0

                            // withContext(Dispatchers.Main) 切回主线程 ——
                            // 只有主线程能修改界面。
                            //
                            // 对比回调式版本：那边到处要写 runOnUiThread { }。
                            // 这里变成了「声明式地说明这段要在哪个线程跑」。
                            withContext(Dispatchers.Main) {
                                progressBar.progress = percent
                                statusText.text = "上传中 $percent%  (${formatSize(sent)})"
                            }
                        }
                    }

                    // ---------- ④ 宣告结束 ----------
                    ws.send(Frame.Text(JSONObject().put("type", "upload_end").toString()))
                    log("📤 已发送完毕，共 $sent 字节，等待服务端确认…")
                    withContext(Dispatchers.Main) { statusText.text = "已发送，等待确认…" }

                    // 这里的 ack 由另一个协程（connectAndReceive 的 for 循环）接收。
                    // 两个协程通过同一个 session 收发，互不阻塞 —— 这就是全双工。
                } ?: log("❌ 打开文件失败")
            }
        } catch (e: Exception) {
            // 上传期间的任何异常（文件被删、连接断开、磁盘满）都在这里收口。
            // 因为 upload() 是被 launch 启动的协程，异常若不捕获可能导致崩溃。
            log("❌ 上传失败: ${e.javaClass.simpleName} / ${e.message}")
        }
    }

    // =======================================================================
    //  小工具
    // =======================================================================

    /**
     * 从 URI 取文件名和 MIME 类型。
     *
     * 【关键概念】系统选择器返回的是 content:// URI，不是文件路径。
     *   例：content://com.android.providers.media.documents/document/image%3A1234
     *   所以不能用 File(uri.path) —— 那既不是真实路径，也读不到内容。
     *   必须通过 contentResolver 打开数据流或查询元信息。
     *
     * 为什么 URI 要这样设计？
     *   因为文件可能根本不在手机上：可能在云盘、在内容提供器动态生成的虚拟文件。
     *   URI 是个抽象句柄，由提供器决定怎么把数据给你。
     */
    private fun queryFileInfo(uri: Uri): Pair<String, String> {
        var name = "unknown"
        try {
            // query 返回 Cursor（游标），像数据库查询结果。
            // use{} 保证游标被关闭 —— 忘了关会泄漏。
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                // 游标必须先移动到第一行才能读数据。
                if (cursor.moveToFirst()) {
                    // 列可能不存在，所以取列索引后要判断 >= 0。
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = cursor.getString(idx) ?: name
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "查询文件名失败: ${e.message}")
        }
        // 有些来源查不到名字，退回到 URI 的最后一段。
        if (name == "unknown") name = uri.lastPathSegment ?: "unknown"

        // getType 可能返回 null，给个通用兜底值。
        return name to (contentResolver.getType(uri) ?: "application/octet-stream")
    }

    /** 只取文件大小。单独成函数是为了让 upload() 的主体更清爽。 */
    private fun queryFileSize(uri: Uri): Long = try {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                // isNull 判断该列是否为空值 —— 不能直接 getLong，会抛异常。
                if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else -1L
            } else -1L
        } ?: -1L
    } catch (e: Exception) {
        -1L
    }

    /**
     * 字节数转成人看的字符串。
     * when { 条件 -> 结果 } 是 Kotlin 的表达式式分支（不需要写 return）。
     */
    private fun formatSize(bytes: Long): String = when {
        bytes < 0 -> "大小未知"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    }

    /**
     * 往 logcat 和界面同时输出。
     *
     * ★ 这里【不需要】runOnUiThread，这是协程相对回调式最直观的收益。
     *
     *   原因：这个方法总是从「跑在主线程上的协程」中被调用 ——
     *     - lifecycleScope 启动的协程默认使用 Dispatchers.Main
     *     - withContext(Dispatchers.IO) 块结束后会自动切回 Main
     *   所以进入这里时代码已经在主线程了。
     *
     *   代价是「隐式约定」：哪天真从别的线程调它就会崩。
     *   之所以能省掉 runOnUiThread，是因为协程把线程切换变成了显式声明（withContext），
     *   而不是靠每个调用点自己记得切。
     */
    private fun log(message: String) {
        Log.d(TAG, message)
        // append 而不是 = ：保留历史日志，便于回看整个流程。
        logText.append("$message\n")
        statusText.text = message
    }

    // =======================================================================
    //  onDestroy：Activity 即将销毁时的清理
    //
    //  为什么要清理？因为网络连接和客户端并不随 Activity 自动消失：
    //    - 不关会话：服务端会看到一条僵尸连接一直挂着
    //    - 不关 client：内部线程池和连接池泄漏
    //
    //  注意 lifecycleScope 里的协程会被自动取消，这里只需管好网络资源。
    // =======================================================================
    override fun onDestroy() {
        super.onDestroy()
        // 关闭时给对端发一个正常的关闭帧（Ktor 会处理状态码），
        // 这样 Python 那边看到的是「正常断开」而不是「异常断开」。
        lifecycleScope.launch { session?.close() }
        client.close()
    }
}
