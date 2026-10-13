package com.yalu.localconnect

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * 第一步：只做「连上服务端」这一件事。
 *
 * 注意此时此刻 AndroidManifest.xml 里【还没有】INTERNET 权限，
 * 也【没有】允许明文流量。我们就是要先看看它会怎么失败。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LocalConnect"

        // 电脑的地址。注意两种运行环境不一样：
        //   官方模拟器(emulator-5554)：用 10.0.2.2，它是 Google 规定的「宿主机」别名，
        //                              映射到电脑的 127.0.0.1
        //   真机(同一 WiFi/热点)：     用电脑的局域网 IP，例如 10.151.3.98
        // 模拟器内的 127.0.0.1 指的是模拟器自己，不是你的电脑。
        private const val SERVER_URL = "ws://10.0.2.2:8000"
    }

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private val client = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)

        // 处理状态栏/导航栏的边距，避免内容被系统栏挡住
        val root = findViewById<android.view.View>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        connect()
    }

    private fun connect() {
        log("准备连接 $SERVER_URL")

        val request = Request.Builder().url(SERVER_URL).build()
        client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                log("✅ 已连接！HTTP 状态码 = ${response.code}")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                log("收到服务端消息: $text")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                log("❌ 连接失败: ${t.javaClass.simpleName}")
                log("   信息: ${t.message}")
            }
        })
    }

    /**
     * 往界面和 logcat 同时输出。
     *
     * runOnUiThread 是必须的：OkHttp 的回调运行在后台线程，
     * 而 Android 只允许主线程碰 UI，直接改会崩溃。
     */
    private fun log(message: String) {
        Log.d(TAG, message)
        runOnUiThread {
            logText.append("$message\n")
            statusText.text = message
        }
    }
}
