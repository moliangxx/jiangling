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
 * V1.10：TYPE = KIND(bit7-6) + 分区(bit5-4) + 类型(bit3-0)，长度不再由 LEN 字段携带。
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

    /** 应答（对某条 CMD 的回复；PAYLOAD[0] = 回复状态，SEQ=0 时 PAYLOAD[1] = resetDone） */
    data class Ack(
        override val seq: Byte,
        override val kindType: Int,
        val payload: ByteArray
    ) : Frame()

    /**
     * 日志（只有小车会推，App 端不产生）。
     * 一帧固定 8 条记录，每条 = TYPE(1B) + 参数(1B)。
     */
    data class Log(
        override val seq: Byte,
        val records: List<LogRecord>,
        /** 16 字节原始记录区（8×2），用于保留原始字节、排查码表不同步 */
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

/** 一条 LOG 记录：TYPE（含 kind=LOG + 等级 + 模块）+ 单字节参数 */
data class LogRecord(val type: Byte, val param: Byte) {
    /** 空槽：TYPE=0x00 且参数=0x00（不满 8 条时的填充） */
    val isEmpty: Boolean get() = (type.toInt() and 0xFF) == 0 && (param.toInt() and 0xFF) == 0
}

/**
 * ======================================================================
 *  FrameCodec —— 帧处理器
 * ======================================================================
 *
 *  【职责】只做"字节 ⇄ Frame"这一件事，**完全不依赖 Android 蓝牙 API、无状态、不碰数据库**：
 *    - 接收：把收到的字节块追加进缓冲区，自动找帧头、校验 CRC、拆出 Frame
 *    - 发送：把 kind/kindType/seq/payload 编成完整二进制帧
 *
 *  【协作关系】
 *    唯一调用方是 [BleManager]：收到通知 → codec.receive()；要发送 → codec.encode()。
 *    解析出的 Frame 由 BleManager 转发到 frames，再由 BleDataViewModel 处理业务与入库。
 *    本类不认识 ViewModel、仓库、Room。
 *
 *  【帧格式 V1.10（没有 LEN）】
 *    普通帧：HEAD(0xAA) | SEQ(1B) | TYPE(1B) | PAYLOAD(静态表长度) | CRC8(1B)
 *    LOG 帧：HEAD(0xAA) | SEQ(1B) | [TYPE(1B) 参数(1B)] × 8 | CRC8(1B)  = 19 字节
 *
 *    - 收到第 3 个字节即可通过 [M.frameLen] 得知整帧长度，可跨 BLE 通知分片重组；
 *    - LOG 帧的记录从槽 0 开始**连续左对齐填充**，空槽只出现在尾部，
 *      因此第 3 字节一定是带 kind=LOG 的记录 TYPE，不会被误判成 ACK；
 *    - 未知 TYPE：**整帧丢弃**，从下一个字节开始重扫 0xAA（不猜长度）；
 *    - CRC8 覆盖从 HEAD(0xAA) 起、到最后一个 PAYLOAD 字节（长度 = 整帧长 - 1）。
 *
 *  @param onFrame 解析出一条完整报文时回调（在调用 receive() 的线程上同步回调）
 *  @param onError 报文异常时回调（未知 TYPE、CRC 连续失败等）
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
     * 收到一块数据（BLE 一个 notify 就是一块，可能半帧、也可能多帧粘在一起）。
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
     * 逐条处理：找头 → 查表得整帧长度 → 校验 CRC → 取出 PAYLOAD → 移除已解析部分 → 回调。
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

            // ---- ② 连 TYPE 字节都没到：等下一块；超过 1 s 说明是半帧，丢 1 字节重扫 ----
            if (offset < 3) {
                if (partialTimedOut(now)) { dropHead(); continue }
                markPartial(now)
                return
            }

            // ---- ③ 查静态长度表（LOG 固定 19，其余按 TYPE 决定）----
            val seq = buffer[1]
            val type = buffer[2]
            val frameLen = M.frameLen(type.toInt() and 0xFF)
            if (frameLen < 0) {
                // 未知 TYPE：不猜长度，整帧丢弃，逐字节重扫 0xAA
                onError("未知 TYPE 0x${"%02X".format(type)}，已丢弃并重新同步")
                dropHead()
                continue
            }

            // 数据还没到齐，等下一块；超时同样丢 1 字节重扫
            if (offset < frameLen) {
                if (partialTimedOut(now)) { dropHead(); continue }
                markPartial(now)
                return
            }

            // ---- ④ CRC 校验：从 0xAA 起覆盖到最后一个 PAYLOAD 字节，共 frameLen-1 字节 ----
            if (M.crc8(buffer, frameLen - 1) != buffer[frameLen - 1]) {
                dropHead()
                continue
            }

            // ---- ⑤ 先把内容拷出来（必须在移除帧之前拷，避免多帧粘包时读脏数据）----
            val kind = M.kindOf(type)
            val kindType = M.kindTypeOf(type)

            // 普通帧：PAYLOAD 从第 4 字节开始，长度 = frameLen - 4（HEAD+SEQ+TYPE+CRC8）
            // LOG 帧：16 字节记录区从第 3 字节开始（HEAD+SEQ 之后，没有 TYPE 字段）
            val payload = if (kind == M.KIND_LOG) ByteArray(0) else U.slice(buffer, 3, frameLen - 4)
            val logRaw = if (kind == M.KIND_LOG) U.slice(buffer, 2, LOG_RECORDS_BYTES) else ByteArray(0)

            // ---- ⑥ 移除已解析的这一帧 ----
            System.arraycopy(buffer, frameLen, buffer, 0, offset - frameLen)
            offset -= frameLen
            waitingPartial = false

            // ---- ⑦ 按 KIND 分流 ----
            when (kind) {
                M.KIND_ACK -> onFrame(Frame.Ack(seq, kindType, payload))
                M.KIND_DAT -> onFrame(Frame.Dat(seq, kindType, payload))
                M.KIND_CMD -> onFrame(Frame.Cmd(seq, kindType, payload))
                M.KIND_LOG -> {
                    val records = ArrayList<LogRecord>(M.LOG_FRAME_RECORDS)
                    for (i in 0 until M.LOG_FRAME_RECORDS) {
                        records.add(LogRecord(logRaw[i * 2], logRaw[i * 2 + 1]))
                    }
                    onFrame(Frame.Log(seq, records, logRaw))
                }
                else -> onError("未知 KIND: $kind")
            }
        }
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
     * 把数据编成完整二进制帧。只负责拼字节，不负责写蓝牙。
     *
     * @return 编好的帧；TYPE 未登记，或 PAYLOAD 长度与静态表不一致时返回 null
     */
    fun encode(kind: Int, kindType: Int, seq: Byte, payload: ByteArray): ByteArray? {
        val type = M.typeOf(kind, kindType)
        val expected = M.payloadLen(type.toInt() and 0xFF)
        if (expected < 0 || payload.size != expected) return null

        val frameLen = 3 + payload.size + 1  // HEAD+SEQ+TYPE+PAYLOAD+CRC8
        val frame = ByteArray(frameLen)
        frame[0] = M.FRAME_HEAD
        frame[1] = seq
        frame[2] = type
        System.arraycopy(payload, 0, frame, 3, payload.size)
        frame[frameLen - 1] = M.crc8(frame, frameLen - 1)
        return frame
    }

    private companion object {
        /** 接收缓冲上限：够放很多帧，正常远不会满 */
        const val MAX_RECEIVE_BUFFER = 2_048

        /** LOG 帧记录区字节数：8 条 × 每条 2 字节（TYPE + 参数） */
        const val LOG_RECORDS_BYTES = M.LOG_FRAME_RECORDS * 2

        /** 半帧等待上限：超过就按文档丢 1 字节重扫，避免残缺帧长期占住缓冲区 */
        const val FRAME_TIMEOUT_MS = 1_000L
    }
}
