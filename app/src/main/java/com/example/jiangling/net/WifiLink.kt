package com.example.jiangling.net

import com.example.jiangling.ble.Frame
import com.example.jiangling.ble.FrameCodec
import com.example.jiangling.util.M
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ======================================================================
 *  WifiLink —— WiFi 命令通道（主通道）
 * ======================================================================
 *
 *  【依据】《江灵通信协议/总览.md》§2.3 定稿（2026-11-11）：
 *    - cmd（下行，主通道）：**UDP 8081**，一包一帧，
 *      帧结构沿用协议统一格式（`AA | SEQ | TYPE | LEN | PAYLOAD | CRC8`），不另造轻量帧；
 *    - ctrl（回程）：**TCP 8080**，ACK / LOG / DAT 都从这里回来。
 *
 *  【职责】
 *    1) 往主板地址（由 `0x87 DAT_INIT_STATE` 上报，**不写死**）发 UDP 命令帧；
 *    2) 收 TCP 8080 回程字节流，交给 [FrameCodec] 定界（解析逻辑只有一份）；
 *    3) 链路健康：发送失败、TCP 断链、连续 [STALE_LIMIT] 个读超时（约 9 s 收不到数据）
 *       都判为不可用 → 关 socket → 按 1/2/4/8 秒退避自动重连，**不停在半死状态**。
 *
 *  【为什么 READY 要看 TCP】
 *    UDP 无连接，"发出去没报错"不等于板子收到了；TCP 8080 能连上才说明板子确实在线，
 *    也才有 ACK / DAT 回程。因此 READY = UDP 端口已开 + TCP 回程已建立。
 *
 *  【线程】attach/detach/send 在主线程调用；读循环和重连循环跑在 [scope]（IO）。
 */
class WifiLink(
    private val scope: CoroutineScope,
    private val onFrame: (Frame) -> Unit,
    private val onState: (State, String) -> Unit
) {

    /** 通道状态（UI 据此显示「WiFi 已连接 / 未连接」） */
    enum class State {
        /** 还不知道板子地址（未配网 / 未收到 0x87） */
        IDLE,

        /** 正在连（含自动重连） */
        CONNECTING,

        /** 可用：UDP 能发、TCP 回程已建立 */
        READY,

        /** 不可用：等退避后自动重连 */
        DOWN
    }

    @Volatile
    private var state: State = State.IDLE

    /** 当前目标地址；null = 未配置 */
    private var targetIp: String? = null

    /** 代次：attach/detach 时自增，旧循环的迟到回调不会覆盖新状态 */
    @Volatile
    private var generation = 0

    @Volatile
    private var udp: DatagramSocket? = null

    @Volatile
    private var tcp: Socket? = null

    private var loopJob: Job? = null

    /**
     * 发送队列：UDP 发送不能在主线程做（Android 会抛 NetworkOnMainThreadException），
     * 触摸回调只入队，由 [senderJob] 在 IO 线程按顺序发出。
     */
    private val sendQueue = Channel<ByteArray>(SEND_QUEUE_CAPACITY)

    private val senderJob: Job = scope.launch(Dispatchers.IO) {
        for (frame in sendQueue) {
            val socket = udp
            if (state != State.READY || socket == null) continue
            try {
                socket.send(DatagramPacket(frame, frame.size))
            } catch (e: Exception) {
                // 发送失败 = 链路已断：立即降级（运行期命令暂停），并触发自动重连。
                // 只关「本次失败那个 socket」：IP 变化时新循环可能已经换了新的 udp 实例
                publish(State.DOWN, "命令发送失败：${e.message ?: e.javaClass.simpleName}")
                synchronized(this@WifiLink) {
                    if (udp === socket) closeSockets()
                }
            }
        }
    }

    /** 回程 TCP 是字节流，需要自己定界；复用协议层同一个解析器 */
    private val codec = FrameCodec(
        onFrame = { frame -> onFrame(frame) },
        onError = { /* 单帧非法：丢帧重扫即可，不影响链路判定 */ }
    )

    /** 当前状态（非 UI 响应式，仅内部/调试读取；UI 请用 MainViewModel.commandChannel） */
    val currentState: State get() = state

    /** 编一帧 CMD（供会话握手等不走命令记录的帧使用） */
    fun encodeCommandFrame(kindType: Int, payload: ByteArray, seq: Byte): ByteArray? =
        FrameCodec.encodeFrame(M.KIND_CMD, kindType, seq, payload)

    // ==================== 对外入口 ====================

    /**
     * 指定主板地址并（重新）建立通道。地址变化会先断开旧连接。
     * 地址来自 `0x87 DAT_INIT_STATE`，这里不猜、不写死。
     */
    fun attach(ip: String) {
        synchronized(this) {
            if (ip == targetIp && state != State.DOWN) return
            targetIp = ip
            generation++
            closeSockets()
            loopJob?.cancel()
            val gen = generation
            loopJob = scope.launch(Dispatchers.IO) { runLoop(ip, gen) }
        }
    }

    /** 断开并回到 IDLE（例如板子地址变成 0.0.0.0） */
    fun detach() {
        synchronized(this) {
            targetIp = null
            generation++
            closeSockets()
            loopJob?.cancel()
            loopJob = null
            publish(State.IDLE, "未配置板子地址")
        }
    }

    /**
     * 发一帧命令（UDP 8081，一包一帧）。只入队，实际发送在 IO 线程。
     *
     * @return true = 已排队；false = 通道不可用或队列已满（调用方应放弃本帧并提示）
     */
    fun send(frame: ByteArray): Boolean {
        if (state != State.READY) return false
        return sendQueue.trySend(frame).isSuccess
    }

    // ==================== 连接 / 重连循环 ====================

    private suspend fun runLoop(ip: String, gen: Int) {
        var attempt = 0
        while (scope.isActive) {
            if (gen != generation) return
            attempt++
            publish(State.CONNECTING, "正在连接 $ip", gen)

            val address = runCatching { InetAddress.getByName(ip) }.getOrNull()
            if (address == null) {
                publish(State.DOWN, "板子地址无效：$ip", gen)
                delay(retryDelay(attempt))
                continue
            }

            // ---- ① UDP 命令口：只要端口能开就能发，连不上由发送异常暴露 ----
            val udpSocket = runCatching {
                DatagramSocket().apply { connect(address, M.CMD_PORT) }
            }.getOrNull()
            if (udpSocket == null) {
                publish(State.DOWN, "UDP ${M.CMD_PORT} 打开失败", gen)
                delay(retryDelay(attempt))
                continue
            }

            // ---- ② TCP 回程口：连上才算 READY（也是板子在线与否的判据） ----
            val tcpSocket = runCatching {
                Socket().apply {
                    tcpNoDelay = true
                    connect(InetSocketAddress(address, M.CTRL_PORT), CONNECT_TIMEOUT_MS)
                    soTimeout = READ_TIMEOUT_MS
                }
            }.getOrNull()
            if (tcpSocket == null) {
                runCatching { udpSocket.close() }
                publish(State.DOWN, "WiFi 回程（TCP ${M.CTRL_PORT}）未建立", gen)
                delay(retryDelay(attempt))
                continue
            }

            udp = udpSocket
            tcp = tcpSocket
            codec.reset()
            attempt = 0
            publish(State.READY, "WiFi 直连 $ip", gen)

            // ---- ③ 阻塞读回程，直到断链 / 长时间无数据 ----
            readStream(tcpSocket)
            // 先判代次再关 socket：IP 变化时新循环可能已经 attach 了新的 udp/tcp，
            // 旧循环这里不能再把它们关掉
            if (gen != generation) return
            closeSockets()
            publish(State.DOWN, "WiFi 回程断开或长时间无数据", gen)
            attempt++
            delay(retryDelay(attempt))
        }
    }

    /**
     * 读 TCP 8080 字节流。
     *
     * 读超时不算立刻失败（DAT/LOG 有合批空档），连续 [STALE_LIMIT] 次都没有任何字节
     * 才判「长时间收不到数据」并返回，交给外层重连。
     */
    private suspend fun readStream(socket: Socket) = withContext(Dispatchers.IO) {
        val input = try {
            socket.getInputStream()
        } catch (_: Exception) {
            return@withContext
        }
        val buffer = ByteArray(READ_BUFFER_BYTES)
        var stale = 0
        while (!socket.isClosed) {
            val count = try {
                input.read(buffer)
            } catch (_: SocketTimeoutException) {
                stale++
                if (stale >= STALE_LIMIT) return@withContext
                continue
            } catch (_: Exception) {
                return@withContext
            }
            if (count < 0) return@withContext
            stale = 0
            codec.receive(buffer.copyOf(count))
        }
    }

    // ==================== 内部工具 ====================

    private fun publish(next: State, reason: String, gen: Int = generation) {
        if (gen != generation && next != State.IDLE) return
        if (state == next && next != State.DOWN) return
        state = next
        onState(next, reason)
    }

    private fun closeSockets() {
        runCatching { udp?.close() }
        udp = null
        runCatching { tcp?.close() }
        tcp = null
    }

    /** 退避：1 s / 2 s / 4 s / 8 s 封顶，一直重试不放弃 */
    private fun retryDelay(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, 3)
        return (BASE_RETRY_MS shl shift).coerceAtMost(MAX_RETRY_MS)
    }

    companion object {
        /** 命令主通道 UDP 端口（文档《总览》§2.3 定稿：UDP 8081） */
        private const val CONNECT_TIMEOUT_MS = 3_000

        /** 单次读超时；连续 [STALE_LIMIT] 次无数据 → 判链路已死（合计约 9 s） */
        private const val READ_TIMEOUT_MS = 3_000

        /**
         * 回程有周期 DAT（电量 5 s 一帧）在跑，正常最长静默约 5 s；
         * 连续 3 次读超时（约 9 s）没有任何字节，就认为板子掉线/断电，
         * 立即置 DOWN（`canSendCommand()` 随之为 false）并自动重连。
         * 之前是 10 s × 3 = 30 s，板子断电后 App 会有半分钟"假装还能发命令"。
         */
        private const val STALE_LIMIT = 3
        private const val READ_BUFFER_BYTES = 1_024

        private const val BASE_RETRY_MS = 1_000L
        private const val MAX_RETRY_MS = 8_000L

        /** 待发队列容量：5 Hz 流式帧下足够吸收 12 s 的抖动；满了说明链路已死，本帧直接丢弃 */
        private const val SEND_QUEUE_CAPACITY = 64
    }
}
