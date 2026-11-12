package com.example.jiangling.util

import android.content.Context
import android.widget.Toast
import com.example.jiangling.data.log.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 通用工具方法 + 全局提示通道 + 字节解析工具。
 *
 * - U.t(context, text) → Toast（需要 Context）
 * - U.s(text)         → 全局提示通道，任何地方都能调，UI 层 collectAsState() 自动弹出
 * - U.u8 / u16be / i16be / u32be ... → 报文里的字节解析，避免业务代码满屏 `and 0xFF`、`shl`；**多字节一律大端**
 *
 * 麦克风权限在首页/操控页点语音开关时各自申请（见 HomeCompose / ControlCompose）。
 */
object U {

    fun formatTime(time: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(time))

    fun formatDate(time: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(time))

    /** 把 "yyyy-MM-dd" 解析成这一天 0 点的时间戳；解析失败返回 null */
    fun parseDateStart(date: String): Long? = runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .apply { isLenient = false }
            .parse(date)?.time
    }.getOrNull()

    /** 创建一条日志记录（时间 = 手机收到这条日志的时刻） */
    fun makeLog(content: String, level: String = M.LOG_LEVEL_DEBUG): Log =
        Log(time = System.currentTimeMillis(), content = content, level = level)

    /** Toast，需要 Context */
    fun t(context: Context, text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    // ==================== 字节解析（协议报文用） ====================
    //
    // 这一组的作用：把"取第几个字节、无符号、大小端"这些样板代码收进来，
    // 业务层只写「读电量 → 读电压」这种流程，不再出现 `and 0xFF` / `shl`。
    //
    // 命名规则：
    //   u8  = 1 字节无符号     u16 = 2 字节无符号（返回 Int）
    //   le  = 小端（低字节在前） be = 大端（高字节在前）
    //   越界时返回 default，不会抛异常，方便处理残缺报文。

    /** 1 字节无符号：把 -128..127 的 Byte 变成 0..255 的 Int */
    fun u8(b: Byte): Int = b.toInt() and 0xFF

    /** 取第 index 个字节并转无符号；越界返回 default */
    fun u8(bytes: ByteArray, index: Int, default: Int = 0): Int =
        bytes.getOrNull(index)?.let { it.toInt() and 0xFF } ?: default

    /**
     * 2 字节无符号，大端（高字节在前）。
     * 协议铁律：PAYLOAD 内所有多字节数值一律大端，工程内**只保留大端**解析函数，
     * 避免有人误用小端把 `00 44`（68 mm）读成 17408。
     */
    fun u16be(bytes: ByteArray, index: Int = 0): Int {
        val hi = u8(bytes, index)
        val lo = u8(bytes, index + 1)
        return (hi shl 8) or lo
    }

    /** 2 字节有符号，大端（高字节在前）；用于带方向的轮转速等 */
    fun i16be(bytes: ByteArray, index: Int = 0): Int {
        val raw = u16be(bytes, index)
        return if (raw >= 0x8000) raw - 0x10000 else raw
    }

    /** 4 字节无符号，大端（V1.8 DAT_MEM 运行时长用） */
    fun u32be(bytes: ByteArray, index: Int = 0): Long =
        u16be(bytes, index).toLong().shl(16) or u16be(bytes, index + 2).toLong()

    /** 固定长度 ASCII（不足补 0）：截掉尾部 '\u0000' 并 trim */
    fun ascii(bytes: ByteArray, start: Int, len: Int): String =
        slice(bytes, start, len).toString(Charsets.ISO_8859_1)
            .trimEnd('\u0000').trim()

    /** 取子数组 [start, start+len)；越界自动截断到实际长度 */
    fun slice(bytes: ByteArray, start: Int, len: Int): ByteArray {
        if (start >= bytes.size || len <= 0) return ByteArray(0)
        val end = (start + len).coerceAtMost(bytes.size)
        return bytes.copyOfRange(start, end)
    }

    /** ByteArray → UTF-8 字符串 */
    fun text(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)

    /** ByteArray → 十六进制字符串，调试打印用（例如 "AA 01 03 00"） */
    fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it) }

    // ==================== 全局提示通道 ====================

    private val _snackbar = Channel<String>(Channel.BUFFERED)

    /** Compose 层 collect 这个 Flow 来弹 Snackbar。消费端要在 LaunchedEffect 里收到后自动 dismiss。 */
    val snackbar: Flow<String> = _snackbar.receiveAsFlow()

    /**
     * 全局提示窗口。任何地方调 U.s("内容") 就能弹出——不需要 Context。
     * Compose 层需要在某个地方监听 [snackbar] 来显示，否则只是把消息放进了队列。
     */
    fun s(text: String) {
        _snackbar.trySend(text)
    }
}
