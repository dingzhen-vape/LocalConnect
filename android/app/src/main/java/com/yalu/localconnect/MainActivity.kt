// ============================================================================
//  LocalConnect 手机端界面
//
//  本类只负责：界面、把 MessageHandler 的回调反映到屏幕上。
//  所有网络通信都在 MessageHandler 里，协议定义见项目根目录 PROTOCOL.md
//
//  项目流程：
//    1. 手机把相册缩略图发给电脑
//    2. 电脑端展示，用户挑一张
//    3. 电脑按 id 请求原图，手机回传（阶段二，待实现）
// ============================================================================

package com.yalu.localconnect

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), MessageHandler.Callbacks {

    companion object {
        private const val TAG = "LocalConnect"

        /** 一次发送多少张缩略图。手机相册可能有上千张，先限制数量。 */
        private const val THUMB_COUNT = 10
    }

    // ---------- 界面 ----------
    private lateinit var statusText: TextView
    private lateinit var logText: TextView

    // ---------- 通信 ----------
    // 用 lifecycleScope：Activity 销毁时协程自动取消，不会泄漏
    private val handler by lazy { MessageHandler(this, lifecycleScope) }

    // 缩略图发送器。this 一次充当两个角色：Context 和 MessageHandler.Callbacks
    private val thumbnailSender by lazy { ThumbnailSender(this, handler, this) }

    // ---------- 相册权限 ----------
    // API 33 (Android 13) 起，媒体读取权限被拆成图片/视频/音频三个，
    // READ_EXTERNAL_STORAGE 不再作用于图片。所以必须按版本选，
    // 否则在低版本上会「申请一个系统不认识的权限」→ 直接拒绝且不弹框。
    private val mediaPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    /**
     * 权限申请的结果回调。
     * 和文件选择器一样，必须在 Activity 初始化阶段注册，不能等到点击时才注册。
     */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted: Boolean ->
        if (granted) {
            log("✅ 已获得相册权限")
            // 授权成功后接着把刚才被拦下的那次发送做完
            lifecycleScope.launch { thumbnailSender.sendRecent(THUMB_COUNT) }
        } else {
            // shouldShowRequestPermissionRationale 为 false 有两种可能：
            //   ① 用户勾了「不再询问」→ 永久拒绝
            //   ② 从未申请过
            // 在「刚刚被拒绝」这个上下文里，false 只可能是永久拒绝。
            if (!shouldShowRequestPermissionRationale(mediaPermission)) {
                log("❌ 权限被永久拒绝，请到「设置 → 应用 → LocalConnect → 权限」手动开启")
            } else {
                log("❌ 你拒绝了相册权限")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        val thumbButton = findViewById<Button>(R.id.thumbButton)

        // edge-to-edge 会让内容画到系统栏下面，需自己补内边距。
        // 系统栏尺寸变化（旋转、弹键盘）时这个 lambda 会再次触发。
        val root = findViewById<android.view.View>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // 点一下就把最近的缩略图发给电脑。
        // 先检查权限：没授权的话 getRecent 会返回空列表（不报错），
        // 表现为「点了按钮什么都没发生」，很难查。
        thumbButton.setOnClickListener {
            if (hasMediaPermission()) {
                lifecycleScope.launch { thumbnailSender.sendRecent(THUMB_COUNT) }
            } else {
                log("需要相册权限才能读取图片，正在申请…")
                requestMediaPermission()
            }
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

    // ========================================================================
    //  内部
    // ========================================================================

    /**
     * 同时输出到 logcat 和界面。
     *
     * 这个函数【假定自己运行在主线程】——它直接改 UI。
     * 所以所有可能来自后台线程的调用点，都必须先切回主线程。
     */
    private fun log(message: String) {
        Log.d(TAG, message)
        logText.append("$message\n")
        statusText.text = message
    }

    /** 是否已获得相册权限。 */
    private fun hasMediaPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, mediaPermission) ==
            PackageManager.PERMISSION_GRANTED

    /** 申请相册权限。结果回到 permissionLauncher。 */
    private fun requestMediaPermission() {
        permissionLauncher.launch(mediaPermission)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 网络资源不随 Activity 消失，必须显式释放，否则服务端会留下僵尸连接
        handler.close()
    }
}
