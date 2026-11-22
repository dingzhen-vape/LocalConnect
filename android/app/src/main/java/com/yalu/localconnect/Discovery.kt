package com.yalu.localconnect

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * 靠 UDP 多播找电脑。
 *
 * 手机往 224.0.0.167 发探测包，电脑收到后单播回一个应答。
 * 电脑的 IP **不在应答里**，而是取应答包的源地址 —— 回包从哪张网卡出去，
 * 那个地址就一定是手机连得上的；写死在包里，遇上多网卡（这台有 VPN）必选错。
 *
 * 不需要 MulticastLock：那个锁解决的是「入站多播被 WiFi 芯片丢掉」，
 * 这里是出站多播 + 入站单播，驱动不会拦。
 */
object Discovery {

    private const val TAG = "Discovery"

    /** 与 server/discovery.py 的 MULTICAST_GROUP 保持一致。 */
    private const val MULTICAST_GROUP = "224.0.0.167"

    /** 发几次探测。丢一两个包不影响结果，重试比等更久划算。 */
    private const val ATTEMPTS = 3

    /** 单次等待时间。3 × 500ms = 最多 1.5 秒，用户感觉不到卡。 */
    private const val TIMEOUT_MS = 500

    /**
     * 找电脑，返回它的 IP；找不到返回 null。
     *
     * 用的是阻塞式 socket，必须在 IO 线程上跑 —— 这就是外面包一层
     * withContext 的原因。调用方在主线程 launch 里直接 await 即可。
     */
    suspend fun find(port: Int): String? = withContext(Dispatchers.IO) {
        val probe = JSONObject().apply {
            put("type", "discover")
            put("app", "localconnect")
            put("ver", 1)
        }.toString().toByteArray()

        // 不绑定端口，让系统分配临时端口。应答会单播回到这个端口，
        // 所以发送和接收必须共用同一个 socket，不能发完就关。
        DatagramSocket().use { socket ->
            socket.soTimeout = TIMEOUT_MS
            val group = InetAddress.getByName(MULTICAST_GROUP)
            val buffer = ByteArray(512)

            repeat(ATTEMPTS) {
                socket.send(DatagramPacket(probe, probe.size, group, port))

                // 组里可能有别的程序应答，所以要收到超时为止，
                // 不能收到任意一个包就返回
                val deadline = System.currentTimeMillis() + TIMEOUT_MS
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        break
                    }
                    val ip = parseReply(packet, port)
                    if (ip != null) {
                        Log.d(TAG, "发现电脑: $ip")
                        return@withContext ip
                    }
                }
            }
        }
        null
    }

    private fun parseReply(packet: DatagramPacket, port: Int): String? {
        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
        val obj = try {
            JSONObject(text)
        } catch (e: Exception) {
            return null
        }
        if (obj.optString("type") != "discovered") return null
        if (obj.optString("app") != "localconnect") return null
        // 端口对不上说明是别的程序（比如另一台电脑上的旧实例）在应答
        if (obj.optInt("ws", -1) != port) return null

        val address = packet.address?.hostAddress ?: return null
        // 双栈 socket 会把 IPv4 表示成 ::ffff:1.2.3.4，拼进 URL 会坏掉
        return address.removePrefix("::ffff:")
    }
}
