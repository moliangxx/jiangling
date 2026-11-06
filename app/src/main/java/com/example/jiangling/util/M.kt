package com.example.jiangling.util

import java.util.UUID

/**
 * 全局常量 + CRC8 工具。
 *
 * 协议版本：V1.10（定长查表帧）
 * - 帧头 0xAA，帧结构 `AA | SEQ | TYPE | PAYLOAD | CRC8`，**没有 LEN 字段**
 * - 帧长由 [payloadLen] / [frameLen] 的静态表按 TYPE 决定，收发两端硬编码同一张表
 * - TYPE = KIND(bit7-6) + 分区(bit5-4) + 类型(bit3-0)，每个 KIND 有 4×16 = 64 个类型
 * - LOG 帧特例：`AA | SEQ | [TYPE 1B][参数 1B] × 8 | CRC8`，固定 19 字节
 * - ACK 三种 TYPE（固件已定义）：`ACK_RESULT(0x00)` 载荷 `[CMD 完整 TYPE, 状态码]`、
 *   `ACK_SESSION_INIT(0x01)` 载荷 `[状态码, resetDone]`、`ACK_ERROR(0x10)` 载荷 `[错误码, 保留]`（当前不发）
 * - 每次连接第一帧必须是 CMD_SESSION_INIT（SEQ=0，PAYLOAD 只有 protocolVersion=20）
 * - 运动参数统一为百分比：速度挡位 0~100（默认 100），原地转弯幅度 0~100
 * - DAT 定长定点数一律**大端**：电压 0.01V、轮转速 0.01 r/s
 * - **小车只交原始量**：DAT_BAT 只报电压，电量百分比由 App 按曲线换算（换硬件只改本文件）
 * - V1.10 起 `PAYLOAD_MAX` 不再存在：任何长度都查 [payloadLen]，未知 TYPE 整帧丢弃
 */
object M {

    // ==================== Nordic UART Service（传输层不变）====================

    val BLE_SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    val BLE_RX_UUID: UUID     = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    val BLE_TX_UUID: UUID     = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    val BLE_CCCD_UUID: UUID   = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    const val BLE_MTU          = 247
    const val BLE_DEFAULT_MTU  = 23
    const val BLE_SCAN_TIME_MS = 15_000L

    // ==================== 运动参数单位 ====================

    /** 速度挡位：0~100，含义是"最大速度的百分比"；0 = 停止。App 默认发 100 */
    const val SPEED_MAX = 100

    /** 原地转弯幅度：0~100，含义是"最大回转角速度的百分比"；0 = 不转 */
    const val TURN_MAX = 100

    /** 轮胎直径（米）：6.8 cm，用于轮转速 → 线速度换算 */
    const val WHEEL_DIAMETER_M = 0.068

    /** 命令发起对象（相对手机而言）：发送 = 手机发出，接收 = 手机收到 */
    const val ROLE_SEND = "发送"
    const val ROLE_ACCEPT = "接收"

    /*
     * 帧结构 V1.10（**没有 LEN**）
     * [HEAD][SEQ][TYPE][PAYLOAD][CRC8]
     * PAYLOAD 长度 = payloadLen(TYPE)，整帧长度 = 3 + 长度 + 1
     */

    /** 帧同步头，固定 0xAA */
    val FRAME_HEAD: Byte = 0xAA.toByte()

    /**
     * 序列号规则：
     * - CMD：1~255（0 专用于 CMD_SESSION_INIT，不入 64 条去重维护列表）
     * - LOG / DAT / ACK：0~255（ACK 原样回显对应 CMD 的 SEQ）
     */
    const val SEQ_INIT: Byte = 0
    val SEQ_MAX: Byte = 0xFF.toByte()

    // ==================== TYPE 布局（V1.10）====================

    /*
     * bit7-6  KIND      00=ACK  01=LOG  10=DAT  11=CMD
     * bit5-4  分区      同一 KIND 下分 4 个子域
     * bit3-0  类型      子域内具体类型，0~15
     * 每个 KIND 有 4×16 = 64 个 TYPE，全协议共 256 个
     */

    const val KIND_ACK = 0   // 00
    const val KIND_LOG = 1   // 01
    const val KIND_DAT = 2   // 10
    const val KIND_CMD = 3   // 11

    /** 分区号上限（0~3） */
    const val ZONE_MAX = 3

    /** 分区内类型号上限（0~15） */
    const val ITEM_MAX = 15

    /** TYPE 拼装：KIND + 分区 + 类型 */
    fun typeOf(kind: Int, zone: Int, item: Int): Byte =
        ((kind shl 6) or ((zone and ZONE_MAX) shl 4) or (item and ITEM_MAX)).toByte()

    /** TYPE 拼装：KIND + KIND_TYPE（低 6 bit = 分区 + 类型） */
    fun typeOf(kind: Int, kindType: Int): Byte =
        ((kind shl 6) or (kindType and 0x3F)).toByte()

    /** KIND_TYPE 拼装：分区 + 类型 → 低 6 bit */
    fun kindTypeOf(zone: Int, item: Int): Int =
        ((zone and ZONE_MAX) shl 4) or (item and ITEM_MAX)

    /** TYPE 拆包：高 2 bit = KIND（0=ACK 1=LOG 2=DAT 3=CMD） */
    fun kindOf(type: Byte): Int = (type.toInt() and 0xFF) ushr 6

    /** TYPE 拆包：低 6 bit = KIND_TYPE（分区 + 类型） */
    fun kindTypeOf(type: Byte): Int = type.toInt() and 0x3F

    /** KIND_TYPE 拆包：分区（bit5-4） */
    fun zoneOf(kindType: Int): Int = (kindType shr 4) and ZONE_MAX

    /** KIND_TYPE 拆包：类型（bit3-0） */
    fun itemOf(kindType: Int): Int = kindType and ITEM_MAX

    // ==================== CMD（分区 0=系统 / 1=音频 / 2=运动 / 3=预留）====================

    const val CMD_ZONE_SYSTEM = 0
    const val CMD_ZONE_AUDIO  = 1
    const val CMD_ZONE_MOTION = 2
    const val CMD_ZONE_RESERVED = 3

    // ---- 分区 0：系统 / 信息 ----
    /** 会话初始化，SEQ=0，每次连接第一帧（PAYLOAD 1B = 协议版本） */
    const val CMD_SESSION_INIT = 0x00
    /** 查询序列号：参数 1B = 要查询的 SEQ；应答状态为「有」/「无」 */
    const val CMD_QUERY_SEQ    = 0x01
    /** 请求设备静态信息：PAYLOAD 0B；板子收到后重新发一轮 DAT_INFO */
    const val CMD_REQ_INFO     = 0x02
    // 类型 3 / 4 预留给语音方案（语音识别、语音播报），本协议不实现、不占用

    // ---- 分区 1：音频 ----
    /** 音频自检：参数 0=启动提示音、1=1 kHz 测试音、2=立即停止 */
    const val CMD_AUDIO_TEST   = 0x10
    /** 音量调节：参数 0~100 音量百分比（0=静音，100=满量程）；与固件 `CMD_VOLUME` 同名同值 */
    const val CMD_VOLUME       = 0x11
    /** 录音：参数 1~10 秒，录完由设备自动回放 */
    const val CMD_RECORD       = 0x12
    /**
     * 请求开始录音（`0xD3`）：由小车 `DAT_REQ`(请求码 2) 触发，App 发出。
     * 固件当前**未实现**，收到只回 `ACK_ST_UNKNOWN`。
     */
    const val CMD_REQ_RECORD   = 0x13

    // ---- 分区 2：动作 / 运动（**与 ESP32 固件一致：STOP 占类型 0**）----
    /** 急停，最高优先级，PAYLOAD 0B。固件已落地 `CMD_STOP = 0xE0` */
    const val CMD_STOP         = 0x20
    const val CMD_FORWARD      = 0x21
    const val CMD_BACK         = 0x22
    const val CMD_LEFT         = 0x23
    const val CMD_RIGHT        = 0x24
    /** 云台俯仰：参数 0~255 线性映射 0°~180°（与固件一致：`0xE5`） */
    const val CMD_PITCH        = 0x25
    /** 云台水平：参数 0~255 线性映射 0°~360°（与固件一致：`0xE6`） */
    const val CMD_YAW          = 0x26
    /**
     * 麦轮/全向轮横向左移：参数 0~100，横移速度百分比（`0xE7`）。
     * 固件当前**未实现**，收到只回 `ACK_ST_UNKNOWN`。
     */
    const val CMD_LEFT_SHIFT   = 0x27
    /**
     * 麦轮/全向轮横向右移：参数 0~100，横移速度百分比（`0xE8`）。
     * 固件当前**未实现**，收到只回 `ACK_ST_UNKNOWN`。
     */
    const val CMD_RIGHT_SHIFT  = 0x28
    // 类型 9~15 预留

    /**
     * 命令 KIND_TYPE → 中文含义（"蓝牙表"对照）。
     * 数据库里的 content 字段存的就是这里返回的文字，而不是原始字节。
     */
    fun cmdName(kindType: Int): String = when (kindType) {
        CMD_STOP         -> "停止"
        CMD_FORWARD      -> "前进"
        CMD_BACK         -> "后退"
        CMD_LEFT         -> "左转"
        CMD_RIGHT        -> "右转"
        CMD_PITCH        -> "俯仰"
        CMD_YAW          -> "偏航"
        CMD_REQ_RECORD   -> "请求录制"
        CMD_SESSION_INIT -> "会话初始化"
        CMD_LEFT_SHIFT   -> "左移"
        CMD_RIGHT_SHIFT  -> "右移"
        CMD_QUERY_SEQ    -> "查询序列号"
        CMD_REQ_INFO     -> "请求设备信息"
        CMD_AUDIO_TEST   -> "音频测试"
        CMD_VOLUME       -> "音量调节"
        CMD_RECORD       -> "录音"
        else             -> "未知命令($kindType)"
    }

    /**
     * CMD 的固定 PAYLOAD 长度（发送与调试页输入校验共用这一处）。
     * 未登记的 CMD 类型返回 -1（V1.10 没有"兜底 1 字节"，长度一律查表）。
     */
    fun cmdLen(kindType: Int): Int = payloadLen((0xC0 or (kindType and 0x3F)))

    /**
     * CMD 参数 → `PAYLOAD`（发送与调试页共用的**唯一组装入口**）。
     *
     * - 0 字节：`CMD_STOP` / `CMD_REQ_RECORD` / `CMD_REQ_INFO`；
     * - 固定版本：`CMD_SESSION_INIT` 只放协议版本；
     * - 运动命令：0~100 挡位百分比；
     * - 音量调节：0~100，越界裁剪；录音：1~10 秒，越界裁剪；
     * - 其余已登记命令（云台、查询 SEQ、音频自检）：0~255 原样发送。
     */
    fun cmdPayload(kindType: Int, param: Int): ByteArray = when (kindType) {
        CMD_STOP, CMD_REQ_RECORD, CMD_REQ_INFO -> ByteArray(0)
        CMD_SESSION_INIT -> byteArrayOf(PROTOCOL_VERSION)
        CMD_FORWARD, CMD_BACK, CMD_LEFT, CMD_RIGHT, CMD_LEFT_SHIFT, CMD_RIGHT_SHIFT ->
            byteArrayOf(param.coerceIn(0, SPEED_MAX).toByte())
        CMD_VOLUME -> byteArrayOf(param.coerceIn(0, 100).toByte())
        CMD_RECORD -> byteArrayOf(param.coerceIn(1, 10).toByte())
        else -> byteArrayOf(param.coerceIn(0, 255).toByte())
    }

    /**
     * 调试页 TYPE 输入归一化：接受完整 CMD TYPE（`0xC0~0xFF`），返回 KIND_TYPE（0~63）。
     *
     * - `0xC0~0xFF`：完整 CMD TYPE，取低 6 bit；未登记在长度表内的类型返回 null；
     * - 其他值属于 ACK / LOG / DAT 或非法值，拒绝发送。
     */
    fun parseCmdKindType(value: Int): Int? = when {
        value < 0 || value > 0xFF -> null
        value in 0xC0..0xFF -> (value and 0x3F).takeIf { payloadLen(value) >= 0 }
        else -> null
    }

    // ==================== 长度表（V1.10：没有 LEN，长度一律查表）====================

    /** LOG 帧固定 8 条记录 */
    const val LOG_FRAME_RECORDS = 8

    /** LOG 整帧长度：HEAD(1) + SEQ(1) + 8×2 + CRC(1) = 19 */
    const val LOG_FRAME_LEN = 19

    /**
     * 按 **完整 TYPE 字节** 查 PAYLOAD 字节数。
     *
     * @return PAYLOAD 长度；未登记 / 未知 / 非法 TYPE 返回 -1（调用方必须整帧丢弃并重扫 0xAA）
     */
    fun payloadLen(type: Int): Int {
        val t = type and 0xFF
        val kindType = t and 0x3F
        return when (t ushr 6) {
            KIND_CMD -> when (kindType) {
                CMD_STOP, CMD_REQ_RECORD, CMD_REQ_INFO -> 0
                CMD_SESSION_INIT, CMD_QUERY_SEQ,
                CMD_AUDIO_TEST, CMD_VOLUME, CMD_RECORD,
                CMD_FORWARD, CMD_BACK, CMD_LEFT, CMD_RIGHT,
                CMD_LEFT_SHIFT, CMD_RIGHT_SHIFT, CMD_PITCH, CMD_YAW -> 1
                else -> -1
            }
            // ACK 三种（固件定稿）：RESULT / SESSION_INIT / ERROR，PAYLOAD 均为 2 B
            KIND_ACK -> when (kindType) {
                ACK_RESULT, ACK_SESSION_INIT, ACK_ERROR -> 2
                else -> -1
            }
            KIND_DAT -> when (kindType) {
                DAT_BAT, DAT_US, DAT_REQ -> 2
                DAT_WHEEL -> 4
                DAT_MEM -> 16
                DAT_INFO_VERSION -> 20
                DAT_INFO_POWER -> 5
                DAT_INFO_STORAGE -> 6
                else -> -1
            }
            // LOG 的第三字节是第一条记录的 TYPE，整帧固定 19 字节
            else -> -1
        }
    }

    /**
     * 按 **完整 TYPE 字节** 查整帧字节数（含 HEAD / SEQ / TYPE / PAYLOAD / CRC8）。
     *
     * @return 整帧长度；未知 TYPE 返回 -1（整帧丢弃，逐字节重扫 0xAA）
     */
    fun frameLen(type: Int): Int {
        val t = type and 0xFF
        if ((t ushr 6) == KIND_LOG) return LOG_FRAME_LEN
        val payloadLen = payloadLen(t)
        return if (payloadLen < 0) -1 else 3 + payloadLen + 1
    }

    // ==================== ACK（固件定稿：两种 TYPE，PAYLOAD 均 2 B）====================

    /**
     * 普通结果应答：`TYPE=0x00`，`PAYLOAD=[被应答 CMD 的完整 TYPE(1B), 状态码(1B)]`。
     * 固件已落地 `ACK_RESULT = 0x00`；ACK 不按命令编号派生 TYPE，只靠 PAYLOAD 里的 CMD TYPE 对账。
     */
    const val ACK_RESULT = 0x00

    /**
     * 会话初始化应答：`TYPE=0x01`，`PAYLOAD=[状态码(1B), resetDone(1B)]`。
     * 固件已落地 `ACK_SESSION_INIT = 0x01`。
     */
    const val ACK_SESSION_INIT = 0x01

    /**
     * 错误应答（`0x10`，`PAYLOAD=[错误码(1B), 保留(1B)]`）。
     * 固件已定义但**当前不发**；App 侧按长度表接收、按错误码展示，避免以后启用时解析失败。
     */
    const val ACK_ERROR = 0x10

    /** 普通命令 3~5 s 收不到任何 ACK 就判失败（固件对未登记 TYPE 是静默丢弃，不能死等） */
    const val ACK_TIMEOUT_MS = 4_000L

    /** 收到（普通确认，表示这条命令已经进板子的接收队列） */
    const val ACK_ST_RECEIVED = 0

    /** 有（查询类命令的肯定结果） */
    const val ACK_ST_YES = 1

    /** 无（查询类命令的否定结果） */
    const val ACK_ST_NO = 2

    /** 已执行（命令执行完成） */
    const val ACK_ST_DONE = 3

    /** 无法识别（板子不认识这个 TYPE） */
    const val ACK_ST_UNKNOWN = 4

    /** 错误异常（执行过程中出错） */
    const val ACK_ST_ERROR = 5

    /** 无法执行（条件不满足，比如保护状态） */
    const val ACK_ST_CANNOT = 6

    /** 回复状态 → 中文，存进 Command.reply */
    fun ackStatusName(status: Int): String = when (status) {
        ACK_ST_RECEIVED -> "收到"
        ACK_ST_YES      -> "有"
        ACK_ST_NO       -> "无"
        ACK_ST_DONE     -> "已执行"
        ACK_ST_UNKNOWN  -> "无法识别"
        ACK_ST_ERROR    -> "错误异常"
        ACK_ST_CANNOT   -> "无法执行"
        else            -> "未知状态($status)"
    }

    // ==================== LOG（分区 = 等级，类型 = 模块）====================

    /** LOG 分区（等级）：00=D、01=E、10=W、11=预留 */
    const val LOG_ZONE_D = 0
    const val LOG_ZONE_E = 1
    const val LOG_ZONE_W = 2
    const val LOG_ZONE_RESERVED = 3

    /** LOG 模块编号（类型 0~15，**与 ESP32 固件一致**） */
    const val LOG_MOD_SYS     = 0
    const val LOG_MOD_BLE     = 1
    const val LOG_MOD_LOG     = 2   // 日志模块自身（已定义，固件暂不发）
    const val LOG_MOD_DAT     = 3
    const val LOG_MOD_TFT     = 4
    const val LOG_MOD_AUDIO   = 5
    const val LOG_MOD_MIC     = 6   // 已定义，固件暂不发
    const val LOG_MOD_SPEAKER = 7
    const val LOG_MOD_RECORD  = 8
    const val LOG_MOD_MOTOR   = 9
    // 10~15 预留

    /** LOG 分区 → 数据库等级（D / E / W） */
    fun logLevelOfZone(zone: Int): String = when (zone) {
        LOG_ZONE_E -> LOG_LEVEL_ERROR
        LOG_ZONE_W -> LOG_LEVEL_WARNING
        else -> LOG_LEVEL_DEBUG
    }

    /** LOG 模块编号 → 中文模块名（码表不同步时也用它，保证可读） */
    fun logModuleName(module: Int): String = when (module) {
        LOG_MOD_SYS     -> "系统"
        LOG_MOD_BLE     -> "蓝牙"
        LOG_MOD_LOG     -> "日志"
        LOG_MOD_DAT     -> "数据"
        LOG_MOD_TFT     -> "屏幕"
        LOG_MOD_AUDIO   -> "音频"
        LOG_MOD_MIC     -> "麦克风"
        LOG_MOD_SPEAKER -> "喇叭"
        LOG_MOD_RECORD  -> "录音"
        LOG_MOD_MOTOR   -> "电机"
        else            -> "预留模块$module"
    }

    /**
     * 一条 LOG 记录（TYPE 1B + 参数 1B）→ 中文文案。
     *
     * 码表按「模块 × 参数」查；**码表里没有的码返回 `UNKNOWN(模块/参数)` 并保留原始字节**，
     * 不崩溃、不静默丢弃。空槽（TYPE=0x00 且参数=0x00）返回空串，调用方跳过。
     *
     * 需要带数值的日志走码段映射（如音频模块参数 `0x20~0x7F` = 音量 0~95）。
     */
    fun logRecordText(type: Byte, param: Byte): String {
        val typeInt = type.toInt() and 0xFF
        val module = typeInt and 0x0F
        val p = param.toInt() and 0xFF
        if (typeInt == 0 && p == 0) return ""

        // 数值型日志：音频模块 0x20~0x7F 表示音量 0~95
        if (module == LOG_MOD_AUDIO && p in 0x20..0x7F) return "音量 ${p - 0x20}"

        val text = when (module) {
            LOG_MOD_SYS -> when (p) {
                0 -> "开机"; 1 -> "复位"; 2 -> "看门狗复位"; 3 -> "进入待机"
                else -> null
            }
            LOG_MOD_BLE -> when (p) {
                0 -> "蓝牙已连接"; 1 -> "蓝牙已断开"; 2 -> "重连成功"; 3 -> "重连失败"
                4 -> "握手成功"; 5 -> "协议版本不兼容"; 6 -> "收到未知 TYPE，已丢弃"
                else -> null
            }
            LOG_MOD_LOG -> when (p) {
                0 -> "日志队列初始化"; 1 -> "日志队列满"; 2 -> "日志溢出丢弃"
                else -> null
            }
            LOG_MOD_DAT -> when (p) {
                0 -> "开始上报"; 1 -> "上报暂停"; 2 -> "DAT 队列溢出"; 3 -> "未知请求码"
                else -> null
            }
            LOG_MOD_TFT -> when (p) {
                0 -> "屏幕就绪"; 1 -> "屏幕异常"; 2 -> "刷新超时"
                else -> null
            }
            LOG_MOD_AUDIO -> when (p) {
                0 -> "音频初始化失败"; 1 -> "播放开始"; 2 -> "播放结束"; 3 -> "音频停止"
                7 -> "喇叭不可用"
                else -> null
            }
            LOG_MOD_MIC -> when (p) {
                0 -> "麦克风就绪"; 1 -> "麦克风异常"
                else -> null
            }
            LOG_MOD_SPEAKER -> when (p) {
                0 -> "喇叭就绪"; 1 -> "喇叭异常"
                else -> null
            }
            LOG_MOD_RECORD -> when (p) {
                0 -> "录音开始"; 1 -> "录音结束"; 2 -> "回放开始"; 3 -> "回放结束"
                4 -> "存储空间不足"; 5 -> "录音失败"
                else -> null
            }
            LOG_MOD_MOTOR -> when (p) {
                0 -> "电机启动"; 1 -> "电机停止"; 2 -> "堵转"; 3 -> "过流"; 4 -> "过热"
                5 -> "编码器异常"; 6 -> "左轮脉冲丢失"; 7 -> "右轮脉冲丢失"
                else -> null
            }
            else -> null
        }
        return text ?: "UNKNOWN(${logModuleName(module)}/$p) [TYPE 0x%02X P 0x%02X]"
            .format(typeInt, p)
    }

    // ==================== DAT（分区 0=状态 / 1=内存统计 / 2=请求 / 3=预留）====================

    const val DAT_ZONE_STATUS  = 0
    const val DAT_ZONE_MEMORY  = 1
    const val DAT_ZONE_REQUEST = 2
    const val DAT_ZONE_RESERVED = 3

    // ---- 分区 0：状态上报（**静态信息也在这里，与固件一致：0x83~0x85**）----
    /** 电池电压：u16 0.01 V；**没有电量百分比**，电量由 App 按电压曲线估算 */
    const val DAT_BAT   = 0x00
    const val DAT_US    = 0x01
    const val DAT_WHEEL = 0x02
    /** 设备静态信息：版本 + 型号（20 B） */
    const val DAT_INFO_VERSION  = 0x03
    /** 设备静态信息：电池配置 + 轮径 + 最高速度百分比（5 B） */
    const val DAT_INFO_POWER    = 0x04
    /** 设备静态信息：Flash + PSRAM + 能力位图（6 B） */
    const val DAT_INFO_STORAGE  = 0x05
    // 类型 6~15 预留：语音 / Servo / Encoder / Current / Temp / IR / Barometer

    // ---- 分区 1：内存 / 统计 ----
    /** 内存与运行状态（低频，默认 1 s） */
    const val DAT_MEM           = 0x10

    // ---- 分区 2：请求与应答 ----
    /**
     * 小车请求 App 发命令：`PAYLOAD=[请求码 u8, 参数 u8]`。
     * 小车**不得**直接发 CMD；所有命令都由 App 单点发出（见协议文档方向规则）。
     */
    const val DAT_REQ   = 0x20

    // ---- DAT_REQ 请求码（→ 映射成一条正常 CMD，由 App 发出）----
    const val REQ_STOP        = 1    // → CMD_STOP（无参数，允许重复）
    const val REQ_RECORD      = 2    // → CMD_REQ_RECORD（无参数）
    const val REQ_SHIFT_LEFT  = 3    // → CMD_LEFT_SHIFT（0~100）
    const val REQ_SHIFT_RIGHT = 4    // → CMD_RIGHT_SHIFT（0~100）
    const val REQ_PITCH       = 5    // → CMD_PITCH（0~255）
    const val REQ_YAW         = 6    // → CMD_YAW（0~255）
    const val REQ_FORWARD     = 7    // → CMD_FORWARD（0~100）
    const val REQ_BACK        = 8    // → CMD_BACK（0~100）
    const val REQ_LEFT        = 9    // → CMD_LEFT（0~100）
    const val REQ_RIGHT       = 10   // → CMD_RIGHT（0~100）

    /** 请求码 → 对应的 CMD KIND_TYPE；未知请求码返回 null（App 丢弃，不发命令） */
    fun requestCmdKind(code: Int): Int? = when (code) {
        REQ_STOP        -> CMD_STOP
        REQ_RECORD      -> CMD_REQ_RECORD
        REQ_SHIFT_LEFT  -> CMD_LEFT_SHIFT
        REQ_SHIFT_RIGHT -> CMD_RIGHT_SHIFT
        REQ_PITCH       -> CMD_PITCH
        REQ_YAW         -> CMD_YAW
        REQ_FORWARD     -> CMD_FORWARD
        REQ_BACK        -> CMD_BACK
        REQ_LEFT        -> CMD_LEFT
        REQ_RIGHT       -> CMD_RIGHT
        else            -> null
    }

    /** 该请求码是否允许重复触发（否则 App 在 [DAT_REQ_REPEAT_GUARD_MS] 内只执行第一次） */
    fun requestAllowsRepeat(code: Int): Boolean = code == REQ_STOP

    /** 非重复型请求码的防抖窗口 */
    const val DAT_REQ_REPEAT_GUARD_MS = 1000L

    /** 请求码 → 允许的参数范围（仅文档/调试提示用；真正裁剪在 cmdPayload 里） */
    fun requestParamRange(code: Int): IntRange = when (code) {
        REQ_PITCH, REQ_YAW -> 0..255
        REQ_STOP, REQ_RECORD -> 0..0
        else -> 0..100
    }

    /** 型号字符串固定占用的字节数（ASCII，不足补 0） */
    const val DAT_INFO_MODEL_BYTES = 16

    // ---- 能力位图（DAT_INFO_STORAGE 的 DAT_INFO_CAPABILITY）----
    const val CAP_VOICE   = 1 shl 0   // 支持语音识别/播报
    const val CAP_GIMBAL  = 1 shl 1   // 支持云台
    const val CAP_DISPLAY = 1 shl 2   // 板载屏幕
    const val CAP_US      = 1 shl 3   // 超声波
    const val CAP_CAMERA  = 1 shl 4   // 摄像头
    const val CAP_IMU     = 1 shl 5   // IMU
    const val CAP_MECANUM = 1 shl 6   // 麦轮横移

    /** DAT_MEM 默认上报周期（固件可配；App 侧只做文档常量，方便对齐） */
    const val DAT_MEM_INTERVAL_MS = 1000

    // ---- DAT 定长定点数（大端；0xFFFF 表示无效）----

    /** DAT_BAT 电压刻度：raw / 100 = 伏特（分辨率 0.01V） */
    const val DAT_BAT_VOLTAGE_SCALE = 100.0

    /** DAT_WHEEL 轮转速刻度：raw / 100 = r/s（分辨率 0.01 r/s） */
    const val DAT_WHEEL_RPS_SCALE = 100.0

    /** DAT 里 u16 无效值：该字段未采样，App 保留上一次的值/显示 — */
    const val DAT_INVALID_U16 = 0xFFFF
    const val DAT_INVALID_I16 = -1

    /** DAT 里 u32 无效值（DAT_MEM_UPTIME 等未采样时使用） */
    const val DAT_INVALID_U32 = 0xFFFFFFFFL

    /**
     * 电量换算曲线（**只在 App 侧**）：电压（V）→ 电量百分比（%）。
     *
     * 默认按 2S 锂离子电池组标定（单节 3.00 V 空 ~ 4.20 V 满，整组 6.00~8.40 V），
     * 用单调递增的分段线性插值。**换电池/换电芯/换串数只改这两张表**，固件始终只报电压。
     *
     * - 两端外按 0% / 100% 截断；
     * - 数组必须等长、电压严格递增。
     */
    private val BAT_CURVE_VOLTAGE = doubleArrayOf(
        6.00, 6.40, 6.60, 6.80, 7.00, 7.20, 7.40, 7.60, 7.80, 8.00, 8.20, 8.40
    )

    private val BAT_CURVE_PERCENT = doubleArrayOf(
        0.0, 3.0, 6.0, 12.0, 22.0, 35.0, 50.0, 65.0, 78.0, 88.0, 95.0, 100.0
    )

    /** 电压 → 电量百分比（0~100），分段线性插值；越界截断 */
    fun batteryPercentFromVoltage(volts: Double): Double {
        if (volts <= BAT_CURVE_VOLTAGE.first()) return BAT_CURVE_PERCENT.first()
        if (volts >= BAT_CURVE_VOLTAGE.last()) return BAT_CURVE_PERCENT.last()
        for (i in 1 until BAT_CURVE_VOLTAGE.size) {
            if (volts <= BAT_CURVE_VOLTAGE[i]) {
                val v0 = BAT_CURVE_VOLTAGE[i - 1]
                val v1 = BAT_CURVE_VOLTAGE[i]
                val p0 = BAT_CURVE_PERCENT[i - 1]
                val p1 = BAT_CURVE_PERCENT[i]
                val ratio = (volts - v0) / (v1 - v0)
                return p0 + ratio * (p1 - p0)
            }
        }
        return BAT_CURVE_PERCENT.last()
    }

    /** 轮转速 r/s → 线速度 m/s：v = rps × π × 轮胎直径（直径默认用 App 常量，可用 DAT_INFO 覆盖） */
    fun wheelRpsToMps(rps: Double, diameterM: Double = WHEEL_DIAMETER_M): Double =
        rps * Math.PI * diameterM

    /** 当前 App 协议版本（用于 CMD_SESSION_INIT）：协议 V1.10 用 u8 表示为 20 */
    const val PROTOCOL_VERSION: Byte = 20

    // ==================== 序列号窗口 ====================

    /** CMD / LOG / DAT 接收方的去重窗口长度（各自独立维护最近 64 条） */
    const val SEQ_WINDOW_MAX = 64

    /** App 侧待确认命令队列上限（仅内存，断连保留） */
    const val PENDING_MAX = 64

    /** 待确认命令的状态：等待 ACK（出队时该状态写入 Command 表，队列只留等待中的条目） */
    const val PENDING_WAITING = 0

    // ==================== 日志等级（数据库用）====================

    const val LOG_LEVEL_DEBUG   = "D"
    const val LOG_LEVEL_WARNING = "W"
    const val LOG_LEVEL_ERROR   = "E"

    fun logLevelText(level: String): String = when (level.uppercase()) {
        LOG_LEVEL_WARNING -> "警告"
        LOG_LEVEL_ERROR   -> "错误"
        else              -> "记录"
    }

    // ==================== 数据库 ====================

    const val DB_NAME = "JiangLing.db"

    // ==================== CRC-8/ATM ====================
    // 多项式 0x07，初始值 0x00，无反射，无结果异或
    // 测试向量 "123456789" → 0xF4
    // V1.10 覆盖范围：从 HEAD(0xAA) 开始，直到最后一个 PAYLOAD 字节（不含 CRC 本身）

    fun crc8(data: ByteArray, len: Int = data.size): Byte {
        var crc = 0
        for (i in 0 until len) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x80 != 0) (crc shl 1) xor 0x07 else (crc shl 1)
            }
        }
        return crc.toByte()
    }
}
