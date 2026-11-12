package com.example.jiangling.ble

import com.example.jiangling.util.M
import com.example.jiangling.util.U

/**
 * ======================================================================
 *  报文对象（协议里的四类帧：CMD / ACK / LOG / DAT）
 * ======================================================================
 *
 * 一条报文 = SEQ + TYPE + PAYLOAD；KIND（大类）由 TYPE 的高 2 bit 决定。
 * 设备端推什么，上层就收到对应的子类对象，不需要再看字节。
 *
 * 协议 21：`AA | SEQ | TYPE | LEN | PAYLOAD(LEN) | CRC8`，帧总长 = 5 + LEN。
 */
sealed class Frame {
    abstract val seq: Byte

    /** TYPE 的低 6 bit（分区 + 类型）；LOG 帧没有单一 TYPE，固定返回 -1 */
    abstract val kindType: Int

    /** 命令（只允许 App→板；App 收到 CMD 属于方向非法，按 ACK_ST_ERROR 拒绝） */
    data class Cmd(
        override val seq: Byte,
        override val kindType: Int,
        val payload: ByteArray
    ) : Frame()

    /** 应答（对某条 CMD 的回复；PAYLOAD 固定 2 B） */
    data class Ack(
        override val seq: Byte,
        override val kindType: Int,
        val payload: ByteArray
    ) : Frame()

    /**
     * 日志（只有小车会推，App 端不产生）。
     * PAYLOAD 里是若干条 `[记录TYPE 1B][记录LEN 1B][数据]`，条数可变（最多 8 条、无空槽）。
     */
    data class Log(
        override val seq: Byte,
        val records: List<LogRecord>,
        /** 原始记录区（整段 PAYLOAD），用于保留原始字节、排查码表不同步 */
        val raw: ByteArray
    ) : Frame() {
        override val kindType: Int get() = -1
    }

    /** 传感器数据（小车主动推） */
    data class Dat(
        override val seq: Byte,
        override val kindType: Int,
        val payload: ByteArray
    ) : Frame()
}

/**
 * 一条 LOG 记录：记录 TYPE（等级 + 模块）+ 单字节参数。
 * 记录 LEN 为 1 时参数就是唯一的数据字节；LEN 更大时取首字节，其余保留在 [Frame.Log.raw]。
 */
data class LogRecord(val type: Byte, val param: Byte)

/**
 * ======================================================================
 *  FrameCodec —— 帧处理器
 * ======================================================================
 *
 *  【职责】只做"字节 ⇄ Frame"这一件事，**不依赖任何传输实现、无状态、不碰数据库**：
 *    - 接收：把收到的字节块追加进缓冲区，找帧头、按 LEN 定界、校验 CRC、拆出 Frame
 *    - 发送：把 kind/kindType/seq/payload 编成完整二进制帧
 *
 *  【帧格式（协议 21，带 LEN）】
 *    HEAD(0xAA) | SEQ(1B) | TYPE(1B) | LEN(1B) | PAYLOAD(LEN B) | CRC8(1B)
 *    整帧长度 = 5 + LEN；LEN 只表示 PAYLOAD 字节数，不含自身、不含 CRC。
 *
 *    - 收到第 4 个字节就能按 `5 + LEN` 定界，可跨 TCP 分片重组，不再靠超时猜边界；
 *    - TYPE + LEN 组合必须落在 [M.payloadLenRange] 允许范围，否则整帧丢弃并逐字节重扫 0xAA；
 *    - LOG 帧容器 TYPE 固定 `0x42`，记录循环解析直到 LEN 用完；
 *    - CRC8 覆盖 HEAD(0xAA) 到最后一个 PAYLOAD 字节（**包含 LEN**）。
 *
 *  @param onFrame 解析出一条完整报文时回调（在调用 receive() 的线程上同步回调）
 *  @param onError 报文异常时回调（未知 TYPE、LEN 越界、LOG 记录区不完整等）
 */
class FrameCodec(
    private val onFrame: (Frame) -> Unit,
    private val onError: (String) -> Unit
) {
    /** 接收缓冲区：容量够放几十帧，正常不会满 */
    private val buffer = ByteArray(MAX_RECEIVE_BUFFER)
    private var offset = 0

    /** 半帧超时计时：只在本帧凑不齐时计时，凑齐或丢弃后复位 */
    private var partialAtMs = 0L
    private var waitingPartial = false

    // ==================== 接收 ====================

    /**
     * 收到一块数据（TCP 一次读 / UDP 一个报文就是一块，可能半帧、也可能多帧粘在一起）。
     * 内部会追加到缓冲区然后循环解析，解析出来的报文通过 [onFrame] 回调。
     */
    fun receive(chunk: ByteArray) {
        if (chunk.isEmpty()) return

        // 缓冲区快满时丢掉前一半，只保留最近的一半继续解析
        if (offset + chunk.size > buffer.size) {
            val keep = minOf(offset, MAX_RECEIVE_BUFFER / 2)
            System.arraycopy(buffer, offset - keep, buffer, 0, keep)
            offset = keep
            waitingPartial = false
        }
        System.arraycopy(chunk, 0, buffer, offset, chunk.size)
        offset += chunk.size
        parse()
    }

    /**
     * 解析缓冲区里的所有完整帧。
     * 逐条处理：找头 → 读 TYPE/LEN 定界 → 校验 CRC → 取出 PAYLOAD → 移除已解析部分 → 回调。
     */
    private fun parse() {
        while (true) {
            val now = System.nanoTime() / 1_000_000L

            // ---- ① 找帧头 0xAA，前面的垃圾数据直接丢掉 ----
            var headIndex = -1
            for (i in 0 until offset) {
                if (buffer[i] == M.FRAME_HEAD) { headIndex = i; break }
            }
            if (headIndex < 0) {
                offset = 0
                waitingPartial = false
                return
            }
            if (headIndex > 0) {
                System.arraycopy(buffer, headIndex, buffer, 0, offset - headIndex)
                offset -= headIndex
                waitingPartial = false
            }

            // ---- ② TYPE / LEN 都还没到：等下一块；超过 1 s 说明是残帧，丢 1 字节重扫 ----
            if (offset < M.FRAME_OVERHEAD - 1) {
                if (partialTimedOut(now)) { dropHead(); continue }
                markPartial(now)
                return
            }

            // ---- ③ 读 LEN 定界：整帧长度 = 5 + LEN，并校验 TYPE+LEN 组合 ----
            val seq = buffer[1]
            val type = buffer[2].toInt() and 0xFF
            val len = buffer[M.LEN_OFFSET].toInt() and 0xFF
            val frameLen = M.frameLen(type, len)
            if (frameLen < 0) {
                // 未知 TYPE 或 LEN 越界：不猜长度，整帧丢弃，逐字节重扫 0xAA
                onError("TYPE 0x${"%02X".format(type)} 的 LEN=$len 非法，已丢弃并重新同步")
                dropHead()
                continue
            }

            // LEN 没到头之前不能动已缓存字节，等下一块；超时同样丢 1 字节重扫
            if (offset < frameLen) {
                if (partialTimedOut(now)) { dropHead(); continue }
                markPartial(now)
                return
            }

            // ---- ④ CRC 校验：从 0xAA 起覆盖到最后一个 PAYLOAD 字节（含 LEN），共 frameLen-1 字节 ----
            if (M.crc8(buffer, frameLen - 1) != buffer[frameLen - 1]) {
                dropHead()
                continue
            }

            // ---- ⑤ 先把 PAYLOAD 拷出来（必须在移除帧之前拷，避免多帧粘包时读脏数据）----
            val kind = M.kindOf(type.toByte())
            val kindType = type and 0x3F
            val payload = U.slice(buffer, M.FRAME_OVERHEAD - 1, len)

            // ---- ⑥ 移除已解析的这一帧 ----
            System.arraycopy(buffer, frameLen, buffer, 0, offset - frameLen)
            offset -= frameLen
            waitingPartial = false

            // ---- ⑦ 按 KIND 分流；LOG 帧的 PAYLOAD 需要循环拆记录 ----
            when (kind) {
                M.KIND_ACK -> onFrame(Frame.Ack(seq, kindType, payload))
                M.KIND_DAT -> onFrame(Frame.Dat(seq, kindType, payload))
                M.KIND_CMD -> onFrame(Frame.Cmd(seq, kindType, payload))
                M.KIND_LOG -> {
                    val records = parseLogRecords(payload)
                    if (records == null) {
                        onError("LOG 记录区不完整，整帧丢弃")
                        continue
                    }
                    onFrame(Frame.Log(seq, records, payload))
                }
                else -> onError("未知 KIND: $kind")
            }
        }
    }

    /**
     * 循环解析 LOG PAYLOAD：`[记录TYPE 1B][记录LEN 1B][数据 LEN B]`，
     * 一直解析到 LEN 用完；记录长度非法、条数为 0 或超过 8 条时返回 null（整帧丢弃）。
     */
    private fun parseLogRecords(payload: ByteArray): List<LogRecord>? {
        val records = ArrayList<LogRecord>(M.LOG_MAX_RECORDS)
        var pos = 0
        while (pos < payload.size) {
            // 连记录头都不够，记录区不完整
            if (pos + M.LOG_RECORD_HEADER_BYTES > payload.size) return null
            val recordType = payload[pos]
            val recordLen = payload[pos + 1].toInt() and 0xFF
            // 记录 LEN 至少 1 B，且不能越出本帧 PAYLOAD
            if (recordLen <= 0) return null
            if (pos + M.LOG_RECORD_HEADER_BYTES + recordLen > payload.size) return null
            if (records.size >= M.LOG_MAX_RECORDS) return null

            val dataIndex = pos + M.LOG_RECORD_HEADER_BYTES
            records.add(LogRecord(recordType, payload[dataIndex]))
            pos = dataIndex + recordLen
        }
        return records.takeIf { it.isNotEmpty() }
    }

    /** 记录"这一帧开始等后续字节"的时刻；已经记过就不覆盖，保证超时从半帧开始算 */
    private fun markPartial(nowMs: Long) {
        if (!waitingPartial) {
            waitingPartial = true
            partialAtMs = nowMs
        }
    }

    private fun partialTimedOut(nowMs: Long): Boolean =
        waitingPartial && nowMs - partialAtMs >= FRAME_TIMEOUT_MS

    /** 丢掉当前帧头所在的 1 个字节，重新找下一个 0xAA */
    private fun dropHead() {
        System.arraycopy(buffer, 1, buffer, 0, offset - 1)
        offset -= 1
        waitingPartial = false
    }

    /** 断开/重连时清空缓冲区，避免上一条链路的残留数据混进来 */
    fun reset() {
        offset = 0
        waitingPartial = false
    }

    // ==================== 发送 ====================

    /**
     * 把数据编成完整二进制帧。只负责拼字节，不负责发送。
     *
     * @return 编好的帧；TYPE 未登记，或 PAYLOAD 长度不在该 TYPE 允许范围时返回 null
     */
    fun encode(kind: Int, kindType: Int, seq: Byte, payload: ByteArray): ByteArray? =
        encodeFrame(kind, kindType, seq, payload)

    companion object {
        /** 接收缓冲上限：够放很多帧（单帧最大 260 B），正常远不会满 */
        private const val MAX_RECEIVE_BUFFER = 2_048

        /** 半帧等待上限：超过就按文档丢 1 字节重扫，避免残缺帧长期占住缓冲区 */
        private const val FRAME_TIMEOUT_MS = 1_000L

        /**
         * 无状态编码入口：不需要解析器实例，供"只发不收"的调用点（握手、ACK）使用。
         *
         * @return 编好的帧；TYPE 未登记，或 PAYLOAD 长度不在该 TYPE 允许范围时返回 null
         */
        fun encodeFrame(kind: Int, kindType: Int, seq: Byte, payload: ByteArray): ByteArray? {
            val type = M.typeOf(kind, kindType)
            if (!M.payloadLenValid(type.toInt() and 0xFF, payload.size)) return null

            val frameLen = M.FRAME_OVERHEAD + payload.size
            val frame = ByteArray(frameLen)
            frame[0] = M.FRAME_HEAD
            frame[1] = seq
            frame[2] = type
            frame[M.LEN_OFFSET] = payload.size.toByte()
            System.arraycopy(payload, 0, frame, M.FRAME_OVERHEAD - 1, payload.size)
            frame[frameLen - 1] = M.crc8(frame, frameLen - 1)
            return frame
        }
    }
}
