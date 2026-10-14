// ============================================================================
//  LocalConnect 手机端界面
//
//  本类只负责：界面、文件选择、把 MessageHandler 的回调反映到屏幕上。
//  所有网络通信都在 MessageHandler 里，协议定义见项目根目录 PROTOCOL.md
// ============================================================================

package com.yalu.localconnect

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), MessageHandler.Callbacks {

    companion object {
        private const val TAG = "LocalConnect"
    }

    // ---------- 界面 ----------
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var progressBar: ProgressBar

    // ---------- 通信 ----------
    // 用 lifecycleScope：Activity 销毁时协程自动取消，不会泄漏
    private val handler by lazy { MessageHandler(this, lifecycleScope) }

    // 缩略图发送器。this 一次充当两个角色：Context 和 MessageHandler.Callbacks
    private val thumbnailSender by lazy { ThumbnailSender(this, handler, this) }

    // ---------- 文件选择 ----------
    // 系统选择器 = 只有用户选中的那个文件被授权，所以不需要任何存储权限。
    // 必须在 Activity 初始化阶段注册，否则无法在重建时接收结果。
    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            log("已取消选择")
        } else {
            handler.uploadFile(uri, this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        progressBar = findViewById(R.id.progressBar)
        val pickButton = findViewById<Button>(R.id.pickButton)
        val thumbButton = findViewById<Button>(R.id.thumbButton)

        // edge-to-edge 会让内容画到系统栏下面，需自己补内边距。
        // 系统栏尺寸变化（旋转、弹键盘）时这个 lambda 会再次触发。
        val root = findViewById<android.view.View>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        pickButton.setOnClickListener {
            // "*/*" 表示任意类型；改成 "image/*" 则只让选图片
            filePicker.launch(arrayOf("*/*"))
        }

        // 点一下就把最近 10 张图的缩略图发给电脑。
        // sendRecent 是挂起函数（内部要查相册、解码、发网络），所以放进协程
        thumbButton.setOnClickListener {
            lifecycleScope.launch { thumbnailSender.sendRecent(10) }
        }

        // 连接与收消息都不阻塞界面
        handler.connect(this)
    }

    // ========================================================================
    //  MessageHandler 回调
    //
    //  ⚠️ 这些回调可能来自后台线程（MessageHandler 用 scope.launch 启动，
    //     默认调度器是 Dispatchers.Default）。改 UI 只能在主线程，
    //     所以统一用 runOnUiThread 包一层。runOnUiThread 在主线程调用时是
    //     立即执行的，不会引入额外延迟。
    // ========================================================================

    override fun onLog(message: String) {
        runOnUiThread { log(message) }
    }

    override fun onConnectionChanged(connected: Boolean) {
        runOnUiThread {
            statusText.text = if (connected) "已连接" else "未连接"
        }
    }

    override fun onUploadProgress(percent: Int, sentBytes: Long) {
        runOnUiThread {
            // 回调已按 256KB 节流过，这里不必再判重。
            // sentBytes 暂未使用，保留参数是为了将来能显示已发送字节数。
            progressBar.progress = percent
            statusText.text = "上传中 $percent%"
        }
    }

    // ========================================================================
    //  内部
    // ========================================================================

    /**
     * 同时输出到 logcat 和界面。
     *
     * 这个函数【假定自己运行在主线程】——它直接改 UI。
     * 所以所有可能来自后台线程的调用点，都必须先切回主线程
     * （见上面三个回调里的 runOnUiThread）。
     */
    private fun log(message: String) {
        Log.d(TAG, message)
        logText.append("$message\n")
        statusText.text = message
    }

    override fun onDestroy() {
        super.onDestroy()
        // 网络资源不随 Activity 消失，必须显式释放，否则服务端会留下僵尸连接
        handler.close()
    }
}
