package com.example.jiangling.net

import com.example.jiangling.ble.Frame
import com.example.jiangling.ble.FrameCodec
import com.example.jiangling.util.M
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ======================================================================
 *  BoardDiscovery —— 板子地址广播发现（UDP 8082）
 * ======================================================================
 *
 *  【要解决的问题】默认拓扑是手机热点 + 板子 STA，板子的 IP 由热点 DHCP 分配，
 *  App 事先不知道。而唯一能问到地址的 `0x87 DAT_INIT_STATE` 又走 TCP 8080 ——
 *  没有 IP 就建不了 TCP，形成鸡生蛋。所以由板子主动广播，App 只听不扫。
 *
 *  【协议】（《index/江灵通信协议/总览.md》§2.3「地址发现」，2026-11-11 定稿）
 *    - 端口：**UDP 8082**，App 固定绑定；板子发往 `255.255.255.255:8082`
 *      和本网段广播 `<subnet>.255:8082`（部分热点会丢受限广播）；
 *    - 报文：**V21 原帧** `AA | SEQ | TYPE | LEN | PAYLOAD | CRC8`，一包一帧；
 *    - TYPE：`0x87 DAT_INIT_STATE`，LEN=9，PAYLOAD 与 TCP 上完全一致
 *      `[stage][wifiMode][wifiState][peerStage][flags][ipv4 4B]`，本类只取 `[5~8]`；
 *    - 节奏：板子拿到/更换 IP 时连发 3 帧，之后每 5 s 补发 1 帧。
 *
 *  【本类做的两道过滤】
 *    1) **来源校验**：`payload[5~8]` 的 IPv4 必须等于 `DatagramPacket.getAddress()`，
 *       不一致直接丢（防止串网/伪造把 App 指到别的地址去建连）；
 *    2) **同 IP 去重**：2 s 内同一 IP 只回调一次，避免每 5 s 一帧就重建一次 TCP。
 *
 *  【生命周期】跟着 ViewModel 的 scope 走：切后台不取消、页面退出也还在，
 *  只有 ViewModel 被销毁才停。所以回到前台能立刻用上后台收到的地址。
 *
 *  【线程】接收循环跑在 [Dispatchers.IO]；[onIp] 在该线程回调，调用方自己切主线程。
 */
class BoardDiscovery(
    private val scope: CoroutineScope,
    private val onIp: (String) -> Unit
) {

    private var job: Job? = null

    /** 开始监听（重复调用无副作用） */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { runLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun runLoop() {
        // 一个数据报一帧：来源地址只在本次 receive 期间有效，FrameCodec 的回调是同步的
        var sourceIp: String? = null

        // 同 IP 去重：不同 IP 立即生效，同 IP 至少隔 DEDUPE_MS 才再回调一次
        var lastIp: String? = null
        var lastAtMs = 0L

        val codec = FrameCodec(
            onFrame = { frame ->
                if (frame is Frame.Dat && frame.kindType == M.DAT_INIT_STATE) {
                    val ip = ipv4Of(frame.payload)
                    // 来源校验：广播自称的地址必须就是发包方地址
                    if (ip != null && ip == sourceIp) {
                        val now = System.currentTimeMillis()
                        if (ip != lastIp || now - lastAtMs >= DEDUPE_MS) {
                            lastIp = ip
                            lastAtMs = now
                            onIp(ip)
                        }
                    }
                }
            },
            onError = { /* 单包非法直接丢：广播是周期重发的，不需要重传逻辑 */ }
        )

        while (scope.isActive) {
            val socket = try {
                DatagramSocket(null).apply {
                    // 绑通配地址才能收到广播；端口复用避免上一轮 socket 未释放导致绑不上
                    reuseAddress = true
                    bind(InetSocketAddress(M.DISCOVERY_PORT))
                    // read 超时只为定期看一眼协程是否被取消，不是链路判定
                    soTimeout = SOCKET_TIMEOUT_MS
                }
            } catch (_: Exception) {
                // 端口被占用等：稍等再试，不影响主动重连流程
                delay(REBIND_DELAY_MS)
                continue
            }

            val buffer = ByteArray(MAX_DATAGRAM_BYTES)
            socket.use { s ->
                while (scope.isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    val alive = try {
                        s.receive(packet)
                        true
                    } catch (_: SocketTimeoutException) {
                        true
                    } catch (_: Exception) {
                        false
                    }
                    if (!alive) break
                    if (packet.length <= 0) continue

                    sourceIp = packet.address?.hostAddress
                    // 一包一帧，不跨包拼接：先清空上一包的残留再解析
                    codec.reset()
                    codec.receive(buffer.copyOf(packet.length))
                }
            }
            delay(REBIND_DELAY_MS)
        }
    }

    /** 取 `0x87` PAYLOAD 的 `[5~8]`：全 0 视为「还没有地址」，返回 null */
    private fun ipv4Of(payload: ByteArray): String? {
        if (payload.size < INIT_STATE_IP_OFFSET + 4) return null
        val v = IntArray(4) { payload[INIT_STATE_IP_OFFSET + it].toInt() and 0xFF }
        if (v.all { it == 0 }) return null
        return v.joinToString(".")
    }

    private companion object {
        /** `0x87` 里 ipv4 的起始下标（stage/mode/state/peerStage/flags 各 1 B） */
        private const val INIT_STATE_IP_OFFSET = 5

        /** 一帧最长 5 + 255，留余量即可 */
        private const val MAX_DATAGRAM_BYTES = 512

        private const val SOCKET_TIMEOUT_MS = 2_000

        /** 同一 IP 的处理间隔（板子每 5 s 补发一帧，2 s 内重复的视作同一事件） */
        private const val DEDUPE_MS = 2_000L

        private const val REBIND_DELAY_MS = 3_000L
    }
}
