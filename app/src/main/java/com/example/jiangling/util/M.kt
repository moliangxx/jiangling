package com.example.jiangling.util

/**
 * 全局常量 + CRC8 工具。
 *
 * 协议版本：21（带 LEN 的变长帧）
 * - 帧头 0xAA，帧结构 `AA | SEQ | TYPE | LEN | PAYLOAD | CRC8`，帧总长 = 5 + LEN
 * - LEN = PAYLOAD 字节数（不含 LEN 自身、不含 CRC），接收端按 5 + LEN 定界
 * - CRC-8/ATM 覆盖 `0xAA` 到最后一个 PAYLOAD 字节（**包含 LEN**），不含 CRC 自身
 * - TYPE = KIND(bit7-6) + 分区(bit5-4) + 类型(bit3-0)，每个 KIND 有 4×16 = 64 个类型
 * - [payloadLenRange] 只校验"LEN 是否落在该 TYPE 允许范围"，实际长度一律取帧内 LEN
 * - LOG 帧：容器 TYPE 固定 `0x42`，PAYLOAD 是若干条 `[记录TYPE 1B][记录LEN 1B][数据]`，
 *   条数可变、最多 8 条、无空槽；当前记录数据恒为 1 B（记录 LEN = 1）
 * - ACK 三种 TYPE：`ACK_RESULT(0x00)` 载荷 `[CMD 完整 TYPE, 状态码]`、
 *   `ACK_SESSION_INIT(0x01)` 载荷 `[状态码, resetDone]`、`ACK_ERROR(0x10)` 载荷 `[被应答 CMD 的 TYPE, 错误码]`
 * - 每次连接第一帧必须是 CMD_SESSION_INIT（SEQ=0，PAYLOAD 只有 protocolVersion=21）
 * - 运动参数统一为百分比：速度挡位 0~100（默认 100），原地转弯幅度 0~100
 * - DAT 定长定点数一律**大端**：电压 0.01V、轮转速 0.01 r/s
 * - **小车只交原始量**：DAT_BAT 只报电压，电量百分比由 App 按曲线换算（换硬件只改本文件）
 * - 未知 TYPE 或 LEN 与允许范围不符：整帧丢弃并逐字节重扫 0xAA
 */
object M {

    // ==================== WiFi 传输层 ====================

    /**
     * 兜底 AP 的预期网关：只有板子临时开 AP（换网 / 连不上默认网络）时手机才连这个地址。
     * 常态是手机热点 + 板子 STA，主板地址唯一来源是 `DAT_INIT_STATE(0x87)` 上报值。
     */
    const val BOARD_AP_IP = "192.168.4.1"

    /** 命令下行口（UDP） */
    const val CMD_PORT = 8081

    /** 回程/视频控制口（TCP） */
    const val CTRL_PORT = 8080

    /**
     * 板子地址广播口（UDP），**App 监听、板子只发不收**。
     *
     * 板子 STA 拿到 IP 后向 `255.255.255.255:8082` 与本网段广播地址各发一帧 V21
     * `0x87 DAT_INIT_STATE`（LEN=9，payload 与 TCP 完全一致）；App 取 `ipv4` 建 TCP 回程。
     * 详见《总览.md》§2.3「地址发现」。
     */
    const val DISCOVERY_PORT = 8082

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
     * 帧结构（协议 21，带 LEN）
     * [HEAD][SEQ][TYPE][LEN][PAYLOAD × LEN][CRC8]
     * 整帧长度 = 5 + LEN；CRC 覆盖 HEAD 到最后一个 PAYLOAD 字节（含 LEN）
     */
    const val FRAME_OVERHEAD = 5
    const val MAX_FRAME_BYTES = 5 + 255
    const val LEN_OFFSET = 3

    /** 帧同步头，固定 0xAA */
    val FRAME_HEAD: Byte = 0xAA.toByte()

    /**
     * 序列号规则：
     * - CMD：1~255（0 专用于 CMD_SESSION_INIT，不入 64 条去重维护列表）
     * - LOG / DAT / ACK：0~255（ACK 原样回显对应 CMD 的 SEQ）
     */
    const val SEQ_INIT: Byte = 0
    val SEQ_MAX: Byte = 0xFF.toByte()

    // ==================== TYPE 布局（协议 21）====================

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
    /**
     * 选择 WiFi 模式：PAYLOAD 1B，0=STA（连路由器）/ 1=AP（起热点）/ 2=APSTA。
     * 模式持久化到 NVS；非法值回 `ACK_ST_ERROR`。
     */
    const val CMD_WIFI_MODE        = 0x04
    /** WiFi 模式取值：STA = 板子连手机热点/路由器（常态工作模式） */
    const val WIFI_MODE_STA   = 0
    /** WiFi 模式取值：AP = 板子临时自己起热点 `jianglingAPP`（换网/连不上默认网络时才用，不是常态） */
    const val WIFI_MODE_AP    = 1
    /** WiFi 模式取值：APSTA = 同时开热点并连路由器 */
    const val WIFI_MODE_APSTA = 2
    /**
     * 写入路由器凭据：PAYLOAD 变长 `[ssidLen 1B][SSID][密码]`，LEN 2~96。
     * `ssidLen` 1~32；密码 0 B 或 8~63 B（开放网络传 0 B）。
     */
    const val CMD_WIFI_STA_CONFIG  = 0x05
    /**
     * 写入热点凭据：PAYLOAD 变长 `[ssidLen 1B][SSID][密码]`，LEN 2~96，规则同上。
     */
    const val CMD_WIFI_AP_CONFIG   = 0x06
    /** 查询 WiFi 状态：PAYLOAD 0B；ACK 为「已执行」后紧跟一帧 `DAT_WIFI_STATUS(0x86)` */
    const val CMD_WIFI_STATUS      = 0x07
    /**
     * 提交并执行初始化（配网提交点）：PAYLOAD 2B `[mode][保留=0]`。
     * mode 0=STA / 1=AP / 2=APSTA；凭据必须先由 `CMD_WIFI_STA_CONFIG` 下发，本命令不携带。
     * 成功回「已执行」+ `DAT_INIT_STATE`，约 300ms 后主板切到新网络（旧链路断开）；失败回「错误异常」并保持连接。
     */
    const val CMD_INIT_COMMIT      = 0x08
    /** 查询初始化状态：PAYLOAD 0B；ACK「已执行」后补一帧 `DAT_INIT_STATE(0x87)`，只读不改状态 */
    const val CMD_INIT_STATUS      = 0x09
    /**
     * 清空本次运行的配网数据：PAYLOAD 1B `[magic]`，固定 `0x5A`，不符回状态 5。
     * 只清 RAM（无 NVS 可清）；回 ACK 3 后重启，重启先用默认凭据重连手机热点；
     * 连不上才会在约两分钟后临时开 AP（`jianglingAPP`）等待重新配网。
     */
    const val CMD_INIT_FACTORY_RESET = 0x0A
    // 类型 11 原 `CMD_BLE_CONTROL(0xCB)`，蓝牙通道取消后作废，不再分配
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
    /**
     * 差速轮盘运动矢量（`0xEB`，LEN=2）：`[0] drive` int8、`[1] turn` int8，各 `-100`~`+100`。
     * `drive` 正 = 前进；`turn` 正 = **左转**（俯视逆时针）。
     * 流式命令：按住约 200 ms 一帧，松手停发不补帧，车端 300 ms 无新帧自停。
     */
    const val CMD_MOVE_VECTOR   = 0x2B
    /**
     * 云台轮盘矢量（`0xEC`，LEN=2）：`[0] pan` int8、`[1] tilt` int8，各 `-100`~`+100`。
     * `pan` 正 = 向右（俯视顺时针）；`tilt` 正 = 向上。与移动完全独立，超时只停转不回中。
     */
    const val CMD_GIMBAL_VECTOR = 0x2C
    // 类型 13~15 预留

    /**
     * 命令 KIND_TYPE → 中文含义（"命令表"对照）。
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
        CMD_MOVE_VECTOR  -> "运动矢量"
        CMD_GIMBAL_VECTOR -> "云台矢量"
        CMD_QUERY_SEQ    -> "查询序列号"
        CMD_REQ_INFO     -> "请求设备信息"
        CMD_WIFI_MODE    -> "WiFi 模式"
        CMD_WIFI_STA_CONFIG -> "WiFi 路由器凭据"
        CMD_WIFI_AP_CONFIG  -> "WiFi 热点凭据"
        CMD_WIFI_STATUS  -> "查询 WiFi 状态"
        CMD_INIT_COMMIT  -> "提交初始化"
        CMD_INIT_STATUS  -> "查询初始化状态"
        CMD_INIT_FACTORY_RESET -> "清空配网数据"
        CMD_AUDIO_TEST   -> "音频测试"
        CMD_VOLUME       -> "音量调节"
        CMD_RECORD       -> "录音"
        else             -> "未知命令($kindType)"
    }

    /**
     * CMD 的**单值** PAYLOAD 长度（调试页输入校验用）。
     * 只有长度唯一的命令才返回具体值；`0xC5/0xC6` 是变长凭据命令，返回 -1（走专用发送入口）。
     */
    fun cmdLen(kindType: Int): Int {
        val range = payloadLenRange(0xC0 or (kindType and 0x3F)) ?: return -1
        return if (range.first == range.last) range.first else -1
    }

    /** CMD 允许的 LEN 范围；未登记返回 null */
    fun cmdLenRange(kindType: Int): IntRange? = payloadLenRange(0xC0 or (kindType and 0x3F))

    /**
     * 凭据 PAYLOAD 组装：`[ssidLen 1B][SSID][密码]`，变长，不做 96 字节填充。
     *
     * @return 合法 PAYLOAD；SSID 长度不在 1~32、密码长度不在 0 或 8~63、或总长超过 96 时返回 null
     */
    fun wifiCredentialPayload(ssid: String, password: String): ByteArray? {
        val ssidBytes = ssid.toByteArray(Charsets.UTF_8)
        val pwdBytes = password.toByteArray(Charsets.UTF_8)
        if (ssidBytes.size !in 1..32) return null
        if (pwdBytes.isNotEmpty() && pwdBytes.size !in 8..63) return null
        val len = 1 + ssidBytes.size + pwdBytes.size
        if (len !in 2..96) return null
        return byteArrayOf(ssidBytes.size.toByte()) + ssidBytes + pwdBytes
    }

    /**
     * CMD 参数 → `PAYLOAD`（发送与调试页共用的**唯一组装入口**）。
     *
     * - 0 字节：`CMD_STOP` / `CMD_REQ_RECORD` / `CMD_REQ_INFO` / `CMD_WIFI_STATUS`；
     * - 固定版本：`CMD_SESSION_INIT` 只放协议版本；
     * - 运动命令：0~100 挡位百分比；
     * - 音量调节：0~100，越界裁剪；录音：1~10 秒，越界裁剪；
     * - WiFi 模式：0~2，越界裁剪；
     * - `0xC5/0xC6` 凭据命令是变长结构，不走本入口（见 [wifiCredentialPayload]）；
     * - 其余已登记命令（云台、查询 SEQ、音频自检）：0~255 原样发送。
     */
    fun cmdPayload(kindType: Int, param: Int): ByteArray = when (kindType) {
        CMD_STOP, CMD_REQ_RECORD, CMD_REQ_INFO, CMD_WIFI_STATUS, CMD_INIT_STATUS -> ByteArray(0)
        CMD_SESSION_INIT -> byteArrayOf(PROTOCOL_VERSION)
        CMD_WIFI_MODE -> byteArrayOf(param.coerceIn(0, 2).toByte())
        // 提交初始化：[mode][保留 0]，凭据由 0xC5/0xC6 预先下发
        CMD_INIT_COMMIT -> byteArrayOf(param.coerceIn(0, 2).toByte(), 0)
        // 清空本次运行配网数据：[magic]=0x5A，固定值
        CMD_INIT_FACTORY_RESET -> byteArrayOf(0x5A)
        CMD_WIFI_STA_CONFIG, CMD_WIFI_AP_CONFIG -> ByteArray(0)
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
        value in 0xC0..0xFF -> (value and 0x3F).takeIf { cmdLen(value) >= 0 }
        else -> null
    }

    // ==================== 长度范围（协议 21：LEN 在帧内，表只做合法性校验）====================

    /** LOG 帧容器 TYPE：PAYLOAD 里是若干条 `[记录TYPE 1B][记录LEN 1B][数据 LEN B]` */
    const val LOG_FRAME_TYPE = 0x42

    /** 一帧最多记录的条数（文档上限 8 条） */
    const val LOG_MAX_RECORDS = 8

    /** 一条记录的固定头部：记录 TYPE 1B + 记录 LEN 1B */
    const val LOG_RECORD_HEADER_BYTES = 2

    /**
     * 按 **完整 TYPE 字节** 返回允许的 LEN 范围；未登记 TYPE 返回 null。
     *
     * 只做"这个 LEN 对当前 TYPE 是否合法"的校验，**实际长度一律取帧内 LEN**。
     */
    fun payloadLenRange(type: Int): IntRange? {
        val t = type and 0xFF
        val kindType = t and 0x3F
        return when (t ushr 6) {
            KIND_CMD -> when (kindType) {
                CMD_STOP, CMD_REQ_INFO, CMD_REQ_RECORD, CMD_WIFI_STATUS, CMD_INIT_STATUS -> 0..0
                CMD_WIFI_STA_CONFIG, CMD_WIFI_AP_CONFIG -> 2..96
                CMD_INIT_COMMIT -> 2..2
                CMD_INIT_FACTORY_RESET -> 1..1
                CMD_MOVE_VECTOR, CMD_GIMBAL_VECTOR -> 2..2
                CMD_SESSION_INIT, CMD_QUERY_SEQ, CMD_WIFI_MODE,
                CMD_AUDIO_TEST, CMD_VOLUME, CMD_RECORD,
                CMD_FORWARD, CMD_BACK, CMD_LEFT, CMD_RIGHT,
                CMD_PITCH, CMD_YAW, CMD_LEFT_SHIFT, CMD_RIGHT_SHIFT -> 1..1
                else -> null
            }
            KIND_ACK -> when (kindType) {
                ACK_RESULT, ACK_SESSION_INIT, ACK_ERROR -> 2..2
                else -> null
            }
            KIND_DAT -> when (kindType) {
                DAT_BAT, DAT_US, DAT_REQ -> 2..2
                DAT_WHEEL -> 4..4
                DAT_INFO_POWER -> 5..5
                DAT_INFO_STORAGE -> 6..6
                DAT_WIFI_STATUS -> 8..8
                DAT_INIT_STATE -> 9..9
                DAT_MEM -> 16..16
                DAT_INFO_VERSION -> 20..20
                else -> null
            }
            KIND_LOG -> when (kindType) {
                (LOG_FRAME_TYPE and 0x3F) -> 3..24
                else -> null
            }
            else -> null
        }
    }

    /**
     * LEN 是否合法：必须命中 [payloadLenRange]。
     * LOG 帧额外要求 `LEN = 3 × 条数`（3~24 且为 3 的整数倍），与固件一致。
     */
    fun payloadLenValid(type: Int, len: Int): Boolean {
        if (len < 0 || len > 255) return false
        if (type and 0xFF == LOG_FRAME_TYPE && len % 3 != 0) return false
        val range = payloadLenRange(type) ?: return false
        return len in range
    }

    /**
     * 整帧长度 = `5 + LEN`（含 HEAD / SEQ / TYPE / LEN / PAYLOAD / CRC8）。
     * @return 合法时返回整帧字节数；未知 TYPE 或 LEN 越界返回 -1
     */
    fun frameLen(type: Int, len: Int): Int =
        if (payloadLenValid(type, len)) FRAME_OVERHEAD + len else -1

    // ==================== ACK（固件定稿：三种 TYPE，PAYLOAD 均 2 B，整帧 7 B）====================

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
     * 错误应答（`0x10`，`PAYLOAD=[被应答 CMD 的完整 TYPE(1B), 错误码(1B)]`）。
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

    /** App 侧超时兜底状态（不在协议里）：[M.ACK_TIMEOUT_MS] 内没收到 ACK */
    const val ACK_ST_TIMEOUT = -1

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

    /**
     * 回执语义等级，只用于 UI 上色，不改变任何协议行为：
     * `0` = 成功/已受理（0、3，绿）；`1` = 中性（1/2 查询结果，灰）；`2` = 失败（4/5/6/超时，红）。
     *
     * 之前命令记录里所有回复一律用绿色，等于把「无法识别 / 错误异常 / 超时」也画成成功。
     */
    fun ackLevel(status: Int): Int = when (status) {
        ACK_ST_RECEIVED, ACK_ST_DONE -> 0
        ACK_ST_YES, ACK_ST_NO -> 1
        else -> 2
    }

    /** [ackStatusName] 的反向判定：已有记录只存了文案，按文案还原语义等级 */
    fun ackLevelOfText(reply: String): Int = when (reply) {
        ackStatusName(ACK_ST_RECEIVED), ackStatusName(ACK_ST_DONE) -> 0
        ackStatusName(ACK_ST_YES), ackStatusName(ACK_ST_NO) -> 1
        else -> 2
    }

    // ==================== LOG（分区 = 等级，类型 = 模块）====================

    /**
     * LOG 分区（等级）：00=D、01=E、10=W、11=I，已与固件、文档定稿一致。
     *
     * 取值来源：`index/江灵通信协议/日志.md`（定稿）。
     * 记录 TYPE = `0x40 | (等级位 << 4) | 模块号`，即 `0x4x=D`、`0x5x=E`、`0x6x=W`、`0x7x=I`。
     * I 占用原 `11` 预留位，D/E/W 的字节值未改动。
     */
    const val LOG_ZONE_D = 0
    const val LOG_ZONE_E = 1
    const val LOG_ZONE_W = 2
    const val LOG_ZONE_I = 3

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

    /** LOG 分区 → 数据库等级（D / I / W / E 四级） */
    fun logLevelOfZone(zone: Int): String = when (zone) {
        LOG_ZONE_I -> LOG_LEVEL_INFO
        LOG_ZONE_E -> LOG_LEVEL_ERROR
        LOG_ZONE_W -> LOG_LEVEL_WARNING
        else -> LOG_LEVEL_DEBUG
    }

    /**
     * 记录 TYPE（高 2 bit = 等级、低 4 bit = 模块）→ 数据库等级。
     * 例：`0x40`=D/SYSTEM、`0x51`=E/BLE、`0x60`=W/SYSTEM、`0x71`=I/BLE。
     */
    fun logLevelOfType(type: Int): String =
        logLevelOfZone((type ushr 4) and 0x03)

    /** 记录 TYPE 的低 4 bit = 模块编号 */
    fun logModuleOfType(type: Int): Int = type and 0x0F

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
     * 一条 LOG 记录（记录 TYPE 1B + 参数 1B）→ 中文文案。
     *
     * 记录 TYPE：bit5-4 = 等级（`0x4x=D` / `0x5x=E` / `0x6x=W` / `0x7x=I`），低 4 bit = 模块编号。
     * **码表里没有的码返回 `UNKNOWN(模块/参数)` 并保留原始字节**，不崩溃、不静默丢弃。
     * WiFi 事件集中在 SYSTEM 模块的 `0x20~0x35` 段。
     */
    fun logRecordText(type: Byte, param: Byte): String {
        val typeInt = type.toInt() and 0xFF
        val module = logModuleOfType(typeInt)
        val p = param.toInt() and 0xFF

        val text = when (module) {
            LOG_MOD_SYS -> when (p) {
                0x01 -> "启动横幅"
                0x02 -> "系统就绪"
                0x03 -> "启动阶段停止屏幕失败"
                0x04 -> "启动阶段停止蓝牙失败"
                0x05 -> "会话初始化成功"
                0x06 -> "会话协议版本不匹配"
                0x07 -> "查询 SEQ：命中"
                0x08 -> "查询 SEQ：未命中"
                0x20 -> "WiFi 无路由器凭据，起热点"
                0x21 -> "WiFi 已连路由器"
                0x22 -> "WiFi 路由器连接丢失"
                0x23 -> "WiFi 找不到 SSID"
                0x24 -> "WiFi 密码错误/鉴权失败"
                0x25 -> "WiFi 连接超时"
                0x26 -> "外网连通成功"
                0x27 -> "外网连通失败"
                0x28 -> "TCP 客户端接入"
                0x29 -> "TCP 客户端断开"
                0x2A -> "TCP 接收溢出"
                0x2B -> "蓝牙已连接，局域网让路"
                0x2C -> "开始连接路由器"
                0x2D -> "已切换到 STA"
                0x2E -> "已切换到 AP"
                0x2F -> "已切换到 APSTA"
                0x30 -> "热点启动失败"
                0x31 -> "STA 超时回落 AP"
                0x32 -> "配置已写入 NVS"
                0x33 -> "配置格式非法"
                0x34 -> "NVS 读写失败"
                0x35 -> "帧 LEN/TYPE 非法，已丢弃"
                else -> null
            }
            LOG_MOD_BLE -> when (p) {
                0x01 -> "蓝牙初始化失败"
                0x02 -> "蓝牙服务注册失败"
                0x03 -> "蓝牙广播启动失败"
                0x04 -> "蓝牙 TX 特征初始化失败"
                0x05 -> "蓝牙回调注册失败"
                0x06 -> "蓝牙设备名设置失败"
                0x07 -> "蓝牙 MTU 配置失败"
                0x08 -> "蓝牙服务器启动失败"
                0x09 -> "蓝牙接收缓冲溢出"
                0x0A -> "蓝牙单包超过 MTU"
                0x0B -> "蓝牙发送队列已满"
                0x0C -> "蓝牙客户端已连接"
                0x0D -> "蓝牙客户端已断开"
                0x0E -> "蓝牙收到 SEQ=0"
                0x0F -> "蓝牙会话未初始化"
                0x10 -> "蓝牙重复 SEQ，已去重"
                0x11 -> "0xD3 请求录音未实现"
                0x12 -> "横移命令未实现"
                0x13 -> "蓝牙收到命令"
                0x14 -> "蓝牙未知 TYPE 或 LEN 不符，已丢弃"
                in 0x20..0x2F -> "蓝牙未登记 CMD 0xC${(p and 0x0F).toString(16).uppercase()}"
                in 0x30..0x3F -> "蓝牙 CMD 0xC${(p and 0x0F).toString(16).uppercase()} 参数非法"
                else -> null
            }
            LOG_MOD_LOG -> when (p) {
                0x01 -> "日志队列已满"
                else -> null
            }
            LOG_MOD_DAT -> when (p) {
                0x01 -> "已发出 DAT_REQ"
                0x02 -> "DAT_REQ 被拒绝"
                0x03 -> "DAT_REQ 请求码未知"
                else -> null
            }
            LOG_MOD_TFT -> when (p) {
                0x01 -> "SPIFFS 挂载失败"
                0x02 -> "字体文件缺失"
                0x03 -> "字体加载失败"
                else -> null
            }
            LOG_MOD_AUDIO -> when (p) {
                0x01 -> "I2S 初始化失败"
                0x02 -> "麦克风异常"
                0x03 -> "音频未就绪"
                0x04 -> "录音忙"
                0x05 -> "提示音忙"
                else -> null
            }
            LOG_MOD_SPEAKER -> when (p) {
                0x01 -> "喇叭未就绪"
                else -> null
            }
            LOG_MOD_RECORD -> when (p) {
                0x01 -> "录音缓冲分配失败"
                0x02 -> "录音未就绪"
                0x03 -> "麦克风未就绪"
                0x04 -> "录音忙"
                0x05 -> "提示音忙"
                0x06 -> "录音超时长"
                0x07 -> "启动录音忙"
                0x08 -> "启动录音无麦克风"
                0x09 -> "启动录音超时长"
                0x0A -> "启动录音无缓冲"
                else -> null
            }
            LOG_MOD_MOTOR -> when (p) {
                0x01 -> "1 秒无新命令，自动停止"
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
    /**
     * WiFi 状态（8 B）：`[mode 1B][state 1B][ipv4 4B][rssi 2B 大端]`。
     * `mode` 0=STA / 1=AP / 2=APSTA；`rssi` 无效为 `0xFFFF`。
     */
    const val DAT_WIFI_STATUS   = 0x06
    /**
     * 初始化阶段与就绪回报（9 B）：`[stage][wifiMode][wifiState][peerStage][flags][ipv4 4B]`。
     * `ipv4` 是**主板**出口地址（无地址填 `0.0.0.0`），WiFi 命令通道就是往这个地址发；
     * 副板视频地址走 `DAT_VIDEO_ENDPOINT(0x88)`，不要从这里取。
     */
    const val DAT_INIT_STATE    = 0x07
    // 类型 8~15 预留：语音 / Servo / Encoder / Current / Temp / IR / Barometer

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
    const val REQ_RECORD      = 2    // → CMD_RECORD（秒数由 App 策略决定）
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
        REQ_RECORD      -> CMD_RECORD
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

    /** 当前 App 协议版本（用于 CMD_SESSION_INIT）：协议 21 用 u8 表示为 21 */
    const val PROTOCOL_VERSION: Byte = 21

    // ==================== 序列号窗口 ====================

    /** CMD / LOG / DAT 接收方的去重窗口长度（各自独立维护最近 64 条） */
    const val SEQ_WINDOW_MAX = 64

    /** App 侧待确认命令队列上限（仅内存，断连保留） */
    const val PENDING_MAX = 64

    /** 待确认命令的状态：等待 ACK（出队时该状态写入 Command 表，队列只留等待中的条目） */
    const val PENDING_WAITING = 0

    // ==================== 日志等级（数据库用）====================

    const val LOG_LEVEL_DEBUG   = "D"
    const val LOG_LEVEL_ERROR   = "E"
    const val LOG_LEVEL_WARNING = "W"
    const val LOG_LEVEL_INFO    = "I"

    fun logLevelText(level: String): String = when (level.uppercase()) {
        LOG_LEVEL_DEBUG   -> "调试"
        LOG_LEVEL_ERROR   -> "错误"
        LOG_LEVEL_WARNING -> "警告"
        LOG_LEVEL_INFO    -> "信息"
        else              -> "记录"
    }

    // ==================== 数据库 ====================

    const val DB_NAME = "JiangLing.db"

    // ==================== CRC-8/ATM ====================
    // 多项式 0x07，初始值 0x00，无反射，无结果异或
    // 测试向量 "123456789" → 0xF4
    // 协议 21 覆盖范围：HEAD(0xAA) → 最后一个 PAYLOAD 字节（**包含 LEN**），不含 CRC 自身
    // 覆盖长度 = 4 + LEN（HEAD/SEQ/TYPE/LEN + PAYLOAD）

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
