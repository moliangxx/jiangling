package com.example.jiangling.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.jiangling.ble.Frame
import com.example.jiangling.ble.FrameCodec
import com.example.jiangling.data.command.Command
import com.example.jiangling.data.command.CommandRepository
import com.example.jiangling.data.log.LogRepository
import com.example.jiangling.util.M
import com.example.jiangling.util.U
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设备静态信息（DAT_INFO 三个子帧合并后的结果，App 内存缓存）。
 * 只读；未收到的字段为默认值，容量类字段可能为 null（板子没有该硬件）。
 */
data class DeviceInfo(
    val fwMajor: Int = 0,
    val fwMinor: Int = 0,
    val fwPatch: Int = 0,
    val protocolVersion: Int = 0,
    val model: String = "",

    val batteryCells: Int = 0,
    val batteryChemistry: Int = 0,   // 0=锂离子 1=磷酸铁锂 2=镍氢 3=铅酸
    val wheelDiameterMm: Int = 0,
    val maxGear: Int = 0,            // 最高速度百分比 0~100（100 = 满速），不是挡位个数

    val flashMb: Int? = null,
    val psramKb: Int? = null,
    val capabilityBits: Int = 0,

    /** 最近一次收到任一子帧的本机时间，UI 可据此显示"信息已过期" */
    val receivedAt: Long = 0
)

/**
 * ======================================================================
 *  BleDataViewModel —— 协议数据的"唯一处理者"（数据层，仅 WiFi）
 * ======================================================================
 *
 *  【职责】
 *    1) 接收 WifiLink 解析出的帧（CMD / ACK / LOG / DAT）；
 *    2) 把帧翻译成业务对象并落库：
 *       - ACK → 按 SEQ 回填命令回复（回复时间、回复状态）
 *       - 板→App 的 CMD → 建命令记录 + 回 ACK（含 64 条 SEQ 去重）
 *       - LOG → 按 KIND_TYPE 判定等级后写日志表
 *       - DAT → 填充 [carStatus]（只在内存，不落库）
 *    3) 发送命令时负责 "发出去 + 建记录 + 入待确认队列"。
 *
 *  【不负责】
 *    - WiFi 连接生命周期（[com.example.jiangling.net.WifiLink] 负责）
 *    - 帧的字节解析（FrameCodec 负责）
 *    - 页面查询（MainViewModel 用仓库做只读查询）
 *
 *  【协作关系】
 *    WifiLink --frames--> 本类 --仓库--> Room
 *                         --carStatus--> MainViewModel 合并进 status 给 UI
 *    本类**不引用 MainViewModel**，所以不会形成循环依赖。
 *
 *  【行为约定（V1.7）】
 *    - 发出去的命令只发一次：不自动重发、不自动查序列号；
 *    - App 侧维护**有界待确认队列**（最多 64 条：seq / 命令类型 / 发送时间 / 状态），
 *      收到 ACK 出队；断连保留，重连后仍能看出哪些命令没回复（Command 表也保留空回复记录）；
 *    - **CMD / LOG / DAT 各自维护最近 64 条 SEQ 窗口**，重复 SEQ 直接丢弃（CMD 仍回 ACK 不重复执行）；
 *    - ACK 不维护列表（ACK 丢失后重发的 ACK 直接接受）；
 *    - 只有板子复位（ACK `resetDone=1`）或进程重启才清空窗口与待确认队列。
 */
class BleDataViewModel(app: Application) : AndroidViewModel(app) {

    private val database = AppDatabase.getInstance(app)
    private val commandRepository = CommandRepository(database.commandDao())
    private val logRepository = LogRepository(database.logDao())

    // ======================================================================
    //  命令出口（只有 WiFi；本类只管组帧前的 PAYLOAD 与记账）
    // ======================================================================

    /**
     * 命令出口：由 MainViewModel 注入（WiFi 的 UDP 8081）。
     * 返回 true = 已交给传输层。
     */
    var transport: ((kindType: Int, payload: ByteArray, seq: Byte) -> Boolean)? = null

    /**
     * 已编好字节的原始出口：用于 ACK 这类不走命令记录的帧。
     * MainViewModel 注入 `wifi.send`，和 [transport] 走同一条 WiFi 链路。
     */
    var rawTransport: ((bytes: ByteArray) -> Boolean)? = null

    /** CMD 全局 SEQ：全 App 唯一的一套（协议要求 App 侧 SEQ 唯一） */
    private var cmdSeqCounter: Byte = 0

    private fun nextCmdSeq(): Byte {
        cmdSeqCounter = if (cmdSeqCounter >= 255) 1 else (cmdSeqCounter + 1).toByte()
        return cmdSeqCounter
    }

    // ======================================================================
    //  小车实时运行状态（DAT 帧填充，只在内存）
    // ======================================================================

    /**
     * 小车遥测状态（只包含 DAT 帧能填的字段，连接字段由 MainViewModel 维护）。
     *
     * 数据来源：WifiLink 解析出的 DAT 帧。
     * WiFi 断开后不再收到 DAT，字段停在最后一次收到的值；
     * 进程重启则回到默认值。
     */
    private val _carStatus = MutableStateFlow(CarStatus())
    val carStatus: StateFlow<CarStatus> = _carStatus.asStateFlow()

    /**
     * 设备静态信息（DAT_INFO，**每次握手成功发一轮，之后不再周期发送**）。
     *
     * App 侧缓存责任：收到就存在内存里，UI 只读；未收到为 null。
     * 进程重启后重连会再走一次握手，所以即使小车没重启也能重新拿到（无需持久化）。
     * 若 App 重装/清缓存后仍拿不到（例如握手时帧丢失），用 `CMD_REQ_INFO` 主动补拉。
     */
    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo.asStateFlow()

    /** 非重复型 DAT_REQ 的上次触发时间（请求码 → 时间戳），用于防抖 */
    private val lastReqTime = mutableMapOf<Int, Long>()

    /** 需要 UI 提示的消息出口（由 MainViewModel 接到自己的 message 流） */
    var onNotice: ((String) -> Unit)? = null

    /**
     * 最近一次 ACK 结果（按 SEQ 区分），供「配网」这类需要明确成功/失败反馈的流程使用。
     *
     * - [AckEvent.status] 为协议 ACK 状态（0~6，见 `M.ACK_ST_*`）；
     * - 超时兜底也发一条，[AckEvent.status] = [ACK_STATUS_TIMEOUT]（-1），理由在 [AckEvent.statusText]。
     */
    data class AckEvent(
        val seq: Int,
        val kindType: Int,
        val status: Int,
        val statusText: String
    )

    private val _ackEvents = MutableStateFlow<AckEvent?>(null)
    val ackEvents: StateFlow<AckEvent?> = _ackEvents.asStateFlow()

    /**
     * 是否允许解析 DAT。
     *
     * 《通信协议》第 6.4 / 12.3 节：协议版本不匹配时握手 ACK 回 ACK_ST_ERROR(5)、resetDone=0，
     * 双方都**不得再按 V1.10 格式解析 DAT**。默认放行（ACK 可能丢包而 DAT 已经到达），
     * 只有握手明确失败时才关闭，下一次握手成功再打开。
     */
    private var datParseEnabled = true

    init {
        // 帧统一由 WifiLink 解析后经 onExternalFrame 送进来，本类不直接连任何链路
    }

    // ======================================================================
    //  命令发送（只发一次，不做任何自动补发 / 自动重查）
    // ======================================================================

    /**
     * 发送命令并建本地记录（int 参数入口）。
     *
     * @return 本次实际使用的 SEQ；发送失败返回 null（由 MainViewModel 提示）
     */
    fun sendCommand(kindType: Int, param: Int): Byte? {
        val payload = payloadOf(kindType, param)
        return sendCommandRaw(
            kindType,
            payload,
            cmdContent(kindType, if (payload.isEmpty()) 0 else U.u8(payload, 0))
        )
    }

    /**
     * 直接按 PAYLOAD 发送（变长命令走这里，例如 WiFi 凭据）。
     *
     * @return 本次实际使用的 SEQ；发送失败返回 null
     */
    fun sendCommandRaw(kindType: Int, payload: ByteArray, content: String): Byte? {
        val seq = nextCmdSeq()
        if (!send(kindType, payload, seq)) return null

        // 先入待确认队列（内存，最多 64 条），再落库建记录
        enqueuePending(seq.toInt(), kindType)

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                commandRepository.add(
                    Command(
                        seq = seq.toInt(),
                        role = M.ROLE_SEND,
                        kindType = kindType,
                        content = content,
                        code = frameCode(M.KIND_CMD, kindType, payload),
                        sendTime = System.currentTimeMillis()
                    )
                )
            }
        }
        return seq
    }

    /**
     * 真正的发送出口：交给 MainViewModel 注入的 [transport]（WiFi UDP 8081）。
     * 帧字节由 WifiLink 用同一套 [FrameCodec.encode] 生成。
     */
    private fun send(kindType: Int, payload: ByteArray, seq: Byte): Boolean =
        transport?.invoke(kindType, payload, seq) ?: false

    // ---- WiFi 配置（0xC4~0xC7）专用入口，都不走通用 int 参数组装 ----

    /** 选择 WiFi 模式：0=STA / 1=AP / 2=APSTA，越界由 [M.cmdPayload] 裁剪 */
    fun sendWifiMode(mode: Int): Byte? = sendCommand(M.CMD_WIFI_MODE, mode)

    /** 下发路由器凭据（`0xC5`），PAYLOAD 为 `[ssidLen][SSID][密码]`，变长不补齐 */
    fun sendWifiStaConfig(ssid: String, password: String): Byte? =
        sendWifiCredential(M.CMD_WIFI_STA_CONFIG, ssid, password)

    /** 下发热点凭据（`0xC6`），布局同 `0xC5` */
    fun sendWifiApConfig(ssid: String, password: String): Byte? =
        sendWifiCredential(M.CMD_WIFI_AP_CONFIG, ssid, password)

    /**
     * 提交初始化（`0xC8`）：凭据必须先由 [sendWifiStaConfig] 下发。
     *
     * @param mode 0=STA（连路由器/手机热点）/ 1=AP / 2=APSTA
     */
    fun sendInitCommit(mode: Int): Byte? = sendCommand(M.CMD_INIT_COMMIT, mode)

    /** 查询初始化状态（`0xC9`）：只读，不改任何状态；结果由 `0x87 DAT_INIT_STATE` 补发 */
    fun sendInitStatus(): Byte? = sendCommand(M.CMD_INIT_STATUS, 0)

    /** 清空本次运行的配网数据（`0xCA`，PAYLOAD 固定 `0x5A`），板子回 ACK 3 后重启回 AP */
    fun sendInitFactoryReset(): Byte? = sendCommandRaw(
        M.CMD_INIT_FACTORY_RESET,
        M.cmdPayload(M.CMD_INIT_FACTORY_RESET, 0),
        "清空配网数据 0xCA"
    )

    /**
     * 运动矢量（`0xEB`）流式帧：`[drive][turn]`，各 int8 `-100`~`+100`。
     *
     * `drive` 正 = 前进；`turn` 正 = **左转**（与 [M.CMD_MOVE_VECTOR] 的定稿定义一致）。
     *
     * 流式帧刻意**不进待确认队列、不落库**：5 Hz 持续下发时不该按 4 s 超时刷提示，
     * 也不该每秒写 5 条无意义记录；App 只负责把帧发出去，超时自停是车端责任。
     */
    fun sendMoveVector(drive: Int, turn: Int): Boolean =
        send(M.CMD_MOVE_VECTOR, vectorPayload(drive, turn), nextCmdSeq())

    /**
     * 云台矢量（`0xEC`）流式帧：`[pan][tilt]`，各 int8 `-100`~`+100`。
     *
     * `pan` 正 = 向右；`tilt` 正 = 向上。与移动帧相互独立、独立计时。
     */
    fun sendGimbalVector(pan: Int, tilt: Int): Boolean =
        send(M.CMD_GIMBAL_VECTOR, vectorPayload(pan, tilt), nextCmdSeq())

    /** 两轴矢量 PAYLOAD：越界直接夹取，不报错（流式命令的约定） */
    private fun vectorPayload(a: Int, b: Int): ByteArray = byteArrayOf(
        a.coerceIn(-100, 100).toByte(),
        b.coerceIn(-100, 100).toByte()
    )

    /**
     * 凭据命令统一入口：先按 [M.wifiCredentialPayload] 校验并组装，再走变长发送。
     * 密码**不写进**数据库 content，避免明文落库。
     */
    private fun sendWifiCredential(kindType: Int, ssid: String, password: String): Byte? {
        val payload = M.wifiCredentialPayload(ssid, password)
        if (payload == null) {
            onNotice?.invoke("WiFi 凭据不合法：SSID 需 1~32 字节，密码留空或 8~63 字节，总长不超过 96 字节")
            return null
        }
        return sendCommandRaw(kindType, payload, "${M.cmdName(kindType)}（SSID=$ssid）")
    }

    /**
     * 按 KIND_TYPE 组装 PAYLOAD：**唯一组装入口在 [M.cmdPayload]**，
     * 调试页的长度校验也调 [M.cmdLenRange]，保证"校验的 LEN"和实际发出的 PAYLOAD 长度一致。
     *
     * - 无参数：`CMD_STOP` / `CMD_REQ_RECORD` / `CMD_REQ_INFO` / `CMD_WIFI_STATUS` → 0 字节；
     * - 运动命令（0~100 挡位）与云台/查询/音频（0~255）由 M.cmdPayload 按类型分组；
     * - `0xC5/0xC6` 是变长凭据命令，通用入口只给空 PAYLOAD，实际发送走 [sendWifiCredential]；
     * - 未知 TYPE 在 [M.payloadLenRange] 里没有，[FrameCodec.encode] 会拒绝；
     * - `CMD_SESSION_INIT` 固定发当前协议版本（正常流程由 MainViewModel 在 WiFi READY 时自动发送，这里只是兜底）。
     */
    private fun payloadOf(kindType: Int, param: Int): ByteArray = M.cmdPayload(kindType, param)

    /** 命令的中文含义（数据库 content 字段用它，不存原始字节） */
    private fun cmdContent(kindType: Int, param: Int): String {
        val name = M.cmdName(kindType)
        return when (kindType) {
            // 查询序列号：参数是被查询的 SEQ，不是百分比
            M.CMD_QUERY_SEQ -> "$name（SEQ $param）"
            // 云台：0~255 线性映射到角度
            M.CMD_PITCH, M.CMD_YAW -> "$name（$param/255）"
            // 音频自检：0/1/2 三个固定动作
            M.CMD_AUDIO_TEST -> when (param) {
                0 -> "$name（启动提示音）"
                1 -> "$name（1 kHz 测试音）"
                2 -> "$name（停止）"
                else -> "$name（参数 $param）"
            }
            // 音量调节：0~100 百分比，0 = 静音、100 = 满量程
            M.CMD_VOLUME -> "$name（$param%）"
            // 录音：时长（秒），录完设备自动回放
            M.CMD_RECORD -> "$name（${param} 秒）"
            // 运动命令：参数是 0~100 的百分比（速度挡位 / 原地转弯幅度 / 横移速度）
            M.CMD_FORWARD, M.CMD_BACK, M.CMD_LEFT, M.CMD_RIGHT,
            M.CMD_LEFT_SHIFT, M.CMD_RIGHT_SHIFT -> "$name（$param%）"
            // WiFi 模式：0=STA / 1=AP / 2=APSTA
            M.CMD_WIFI_MODE -> when (param) {
                0 -> "$name（STA 连路由器）"
                1 -> "$name（AP 自建热点）"
                2 -> "$name（APSTA）"
                else -> "$name（参数 $param）"
            }
            else -> name
        }
    }

    /**
     * 二进制码源码：TYPE + LEN + PAYLOAD 的十六进制（协议 21 带 LEN）。
     * KIND 已经编码在 TYPE 的高 2 位里，LEN 是 PAYLOAD 的字节数，一串码就能还原整条命令。
     */
    private fun frameCode(kind: Int, kindType: Int, payload: ByteArray): String {
        val body = byteArrayOf(M.typeOf(kind, kindType), payload.size.toByte()) + payload
        return U.hex(body)
    }

    // ======================================================================
    //  帧分发
    // ======================================================================

    /**
     * 帧分发 + **严格方向分流**（V1.8）：
     * - App 只接受 ACK / LOG / DAT（小车 → App）；
     * - CMD 只有 App → 小车，App 收到 CMD 属于方向非法：**不执行**，只回一条错误 ACK 后丢弃；
     * - LOG / DAT 的非法方向只会出现在小车侧，App 不做反向拦截（App 本身不发 LOG/DAT）。
     */
    private fun handleFrame(frame: Frame) {
        when (frame) {
            is Frame.Ack -> handleAckFrame(frame)
            is Frame.Log -> handleLogFrame(frame)
            is Frame.Dat -> handleDatFrame(frame)
            is Frame.Cmd -> handleIllegalCmdFrame(frame)
        }
    }

    /**
     * 方向非法：小车不得发送 CMD（命令只能由 App 发出）。
     *
     * 处理方式：**不建记录、不执行、不改状态**，仅回 `ACK_ST_ERROR`(5) 让对端知道这帧不合法。
     * 其他方向的非法帧（例如 App 侧收到 LOG/DAT）在本端都是合法方向，不需要拦截。
     */
    private fun handleIllegalCmdFrame(frame: Frame.Cmd) {
        val seq = frame.seq.toInt()
        onNotice?.invoke("收到方向非法的 CMD（SEQ=$seq），已拒绝执行")
        // 回 ACK_ST_ERROR：走与 transport 相同的 WiFi 链路，不建记录
        val bytes = FrameCodec.encodeFrame(
            M.KIND_ACK,
            M.ACK_RESULT,
            frame.seq,
            byteArrayOf(frame.kindType.toByte(), M.ACK_ST_ERROR.toByte())
        )
        if (bytes != null) rawTransport?.invoke(bytes)
    }

    /**
     * DAT 帧 → 填充 [carStatus]（不落库）。
     *
     * 协议 21：帧内 LEN 定界，PAYLOAD 长度已由 [FrameCodec] 按 TYPE 允许范围校验。
     * 定长定点数一律大端，`0xFFFF` = 无效；**只收原始量，换算全在 App**：
     * - DAT_BAT(0x80)   → PAYLOAD=[电压 u16 0.01V]（2 Byte）；电量由 [M.batteryPercentFromVoltage] 换算
     * - DAT_US(0x81)    → PAYLOAD=[距离 u16 cm]（2 Byte）
     * - DAT_WHEEL(0x82) → PAYLOAD=[左轮 int16 0.01 r/s, 右轮 int16 0.01 r/s]（4 Byte）
     *                  左右轮平均后按 v = r/s × π × 0.068 换成 m/s
     * - DAT_INFO_VERSION/POWER/STORAGE(0x83/0x84/0x85) → 静态信息，三个独立 TYPE（与固件一致）
     * - DAT_WIFI_STATUS(0x86) → WiFi 模式/状态/IP/RSSI（8 Byte，仅查询后补发一帧）
     * - DAT_MEM(0x90)  → 内存与运行状态（16 Byte，低频）
     * - DAT_REQ(0xA0)  → 小车请求 App 发命令（请求码 + 参数），App 映射成正常 CMD 发出
     *
     * SEQ 去重：DAT 维护最近 64 条 SEQ 窗口，重复 SEQ（断连重发/重传）直接丢弃。
     */
    private fun handleDatFrame(frame: Frame.Dat) {
        // 握手版本不匹配时不再解析 DAT（见《通信协议》ACK 与握手章节），避免把旧格式当新格式用
        if (!datParseEnabled) return

        val payload = frame.payload

        // ---- 静态信息 / 内存状态 / 小车请求 / WiFi 状态，不改变 carStatus 的公共分支 ----
        when (frame.kindType) {
            M.DAT_INFO_VERSION, M.DAT_INFO_POWER, M.DAT_INFO_STORAGE -> {
                if (!datSeqWindow.accept(frame.seq.toInt())) return
                parseDeviceInfo(frame.kindType, payload)
                return
            }
            M.DAT_WIFI_STATUS -> {
                if (!datSeqWindow.accept(frame.seq.toInt())) return
                _carStatus.value = parseWifiStatus(payload, _carStatus.value)
                return
            }
            M.DAT_INIT_STATE -> {
                if (!datSeqWindow.accept(frame.seq.toInt())) return
                _carStatus.value = parseInitState(payload, _carStatus.value)
                return
            }
            M.DAT_MEM -> {
                if (!datSeqWindow.accept(frame.seq.toInt())) return
                _carStatus.value = parseMem(payload, _carStatus.value)
                return
            }
            M.DAT_REQ -> {
                if (!datSeqWindow.accept(frame.seq.toInt())) return
                handleDatReq(payload)
                return
            }
        }

        // 帧合法后才占 SEQ 窗口：长度不符的帧不算"已处理"，否则会把同 SEQ 的补发也挡掉
        if (!datSeqWindow.accept(frame.seq.toInt())) return

        val current = _carStatus.value
        val updated = when (frame.kindType) {

            // ---- 电池：小车只报电压，电量百分比由 App 按曲线算 ----
            M.DAT_BAT -> {
                val voltageRaw = U.u16be(frame.payload, 0)
                if (voltageRaw == M.DAT_INVALID_U16) {
                    current
                } else {
                    val voltage = voltageRaw / M.DAT_BAT_VOLTAGE_SCALE
                    current.copy(
                        batteryVoltage = voltage,
                        battery = M.batteryPercentFromVoltage(voltage)
                    )
                }
            }

            // ---- 超声波（cm；0xFFFF = 无效，保持上一次的值）----
            M.DAT_US -> {
                val dist = U.u16be(frame.payload, 0)
                current.copy(obstacleDistance = if (dist != M.DAT_INVALID_U16) dist else current.obstacleDistance)
            }

            // ---- 轮转速（大端定点数 r/s；左右轮平均值 → 线速度 m/s）----
            M.DAT_WHEEL -> {
                val leftRaw = U.i16be(frame.payload, 0)
                val rightRaw = U.i16be(frame.payload, 2)
                val leftRps = if (leftRaw != M.DAT_INVALID_I16) leftRaw / M.DAT_WHEEL_RPS_SCALE else current.wheelLeftRps
                val rightRps = if (rightRaw != M.DAT_INVALID_I16) rightRaw / M.DAT_WHEEL_RPS_SCALE else current.wheelRightRps
                // 轮胎直径以 DAT_INFO 上报值为准（mm → m），未收到或无效才回退到 App 常量
                val diameterM = _deviceInfo.value?.wheelDiameterMm
                    ?.takeIf { it > 0 }
                    ?.div(1000.0)
                    ?: M.WHEEL_DIAMETER_M
                val speed = M.wheelRpsToMps((leftRps + rightRps) / 2.0, diameterM)
                current.copy(wheelLeftRps = leftRps, wheelRightRps = rightRps, speed = speed)
            }

            else -> current
        }
        _carStatus.value = updated
    }

    /**
     * DAT_INFO 静态信息：三个独立 TYPE 各自到达，App 按类型**合并缓存**到 [deviceInfo]。
     *
     * 固件只在"握手成功回完 ACK 之后"发一轮；App 负责缓存，不要求周期重发。
     * 三个 TYPE（与固件一致，**没有子类型字节**，TYPE 本身已区分）：
     * - `0x83` VERSION（20 B）：固件 major/minor/patch + 协议版本 + 型号 16 B
     * - `0x84` POWER（5 B）：电池串数 + 化学类型 + 轮径 u16(mm，大端) + 最高速度百分比
     * - `0x85` STORAGE（6 B）：Flash u16(MB) + PSRAM u16(KB) + 能力位图 u16
     */
    private fun parseDeviceInfo(kindType: Int, p: ByteArray) {
        val old = _deviceInfo.value ?: DeviceInfo()
        val now = System.currentTimeMillis()
        val updated = when (kindType) {
            M.DAT_INFO_VERSION -> old.copy(
                fwMajor = U.u8(p, 0),
                fwMinor = U.u8(p, 1),
                fwPatch = U.u8(p, 2),
                protocolVersion = U.u8(p, 3),
                model = U.ascii(p, 4, M.DAT_INFO_MODEL_BYTES),
                receivedAt = now
            )
            M.DAT_INFO_POWER -> old.copy(
                batteryCells = U.u8(p, 0),
                batteryChemistry = U.u8(p, 1),
                wheelDiameterMm = U.u16be(p, 2).takeUnless { it == M.DAT_INVALID_U16 } ?: 0,
                maxGear = U.u8(p, 4),
                receivedAt = now
            )
            M.DAT_INFO_STORAGE -> old.copy(
                flashMb = U.u16be(p, 0).takeUnless { it == M.DAT_INVALID_U16 },
                psramKb = U.u16be(p, 2).takeUnless { it == M.DAT_INVALID_U16 },
                capabilityBits = U.u16be(p, 4),
                receivedAt = now
            )
            else -> old
        }
        _deviceInfo.value = updated
    }

    /**
     * DAT_MEM 内存与运行状态（16 Byte）。
     *
     * `0xFFFF`（u16）/ `0xFFFFFFFF`（u32）/ 队列水位 `0xFF` = 无效：保留上一次的值，
     * 不用 0 冒充"没有内存"。内部 RAM 与 PSRAM **分开存**，不合并成一个 free。
     */
    private fun parseMem(p: ByteArray, current: CarStatus): CarStatus {
        fun keepU16(old: Int?, index: Int): Int? =
            U.u16be(p, index).takeUnless { it == M.DAT_INVALID_U16 } ?: old

        fun keepQueue(b: Byte, old: Int?): Int? =
            U.u8(b).takeUnless { it == 0xFF } ?: old

        val uptimeRaw = U.u32be(p, 8)
        return current.copy(
            ramFreeKb = keepU16(current.ramFreeKb, 0),
            psramFreeKb = keepU16(current.psramFreeKb, 2),
            ramMinKb = keepU16(current.ramMinKb, 4),
            psramMinKb = keepU16(current.psramMinKb, 6),
            uptimeSeconds = if (uptimeRaw == M.DAT_INVALID_U32) current.uptimeSeconds else uptimeRaw,
            logQueue = keepQueue(p[12], current.logQueue),
            datQueue = keepQueue(p[13], current.datQueue),
            logDropped = keepU16(current.logDropped, 14)
        )
    }

    /**
     * DAT_WIFI_STATUS（8 Byte）：`[mode 1B][state 1B][ipv4 4B][rssi 2B 大端]`。
     *
     * - mode：0=STA / 1=AP / 2=APSTA；
     * - state：0=未连路由器 / 1=已连无外网 / 2=有外网；
     * - ipv4：当前出口地址（AP 为 192.168.4.1），全 0 视为未分配；
     * - rssi：int16 大端 dBm，`0xFFFF` = 未连路由器（无效）。
     */
    private fun parseWifiStatus(p: ByteArray, current: CarStatus): CarStatus {
        val ipBytes = U.slice(p, 2, 4)
        val ip = if (ipBytes.size == 4 && ipBytes.any { it.toInt() != 0 }) {
            ipBytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
        } else {
            null
        }
        val rssiRaw = U.u16be(p, 6)
        return current.copy(
            wifiMode = U.u8(p, 0),
            wifiState = U.u8(p, 1),
            wifiIp = ip,
            wifiRssi = if (rssiRaw == M.DAT_INVALID_U16) null else U.i16be(p, 6)
        )
    }

    /**
     * DAT_INIT_STATE（9 Byte）：`[stage][wifiMode][wifiState][peerStage][flags][ipv4 4B]`。
     *
     * 只有 `ipv4` 是 App 直接要用的：它是**主板**出口地址，WiFi 命令通道往这里发。
     * `wifiState` 与 0x86 同口径，顺手一起更新，避免两帧不同步时提示抖动。
     */
    private fun parseInitState(p: ByteArray, current: CarStatus): CarStatus {
        val ipBytes = U.slice(p, 5, 4)
        val ip = if (ipBytes.size == 4 && ipBytes.any { it.toInt() != 0 }) {
            ipBytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
        } else {
            null
        }
        return current.copy(
            initStage = U.u8(p, 0),
            wifiMode = U.u8(p, 1),
            wifiState = U.u8(p, 2),
            boardIp = ip
        )
    }

    /** 把外部通道（WiFi/UDP）收到的字节交给同一套解析器，保证两条通道解析一致 */
    fun onExternalFrame(frame: Frame) {
        handleFrame(frame)
    }

    /**
     * DAT_REQ：小车请求 App 发一条命令。
     *
     * 命令始终由 App 单点发出：请求码 → CMD KIND_TYPE（[M.requestCmdKind]）→ 正常 [sendCommand]
     * （照常入待确认队列、落库、等板子 ACK）。**DAT_REQ 本身不回 ACK**（DAT 通道没有 ACK 语义）。
     * 未知请求码丢弃；非重复型请求码在 [M.DAT_REQ_REPEAT_GUARD_MS] 内只执行一次。
     */
    private fun handleDatReq(p: ByteArray) {
        val code = U.u8(p, 0)
        val param = U.u8(p, 1)
        val kindType = M.requestCmdKind(code)
        if (kindType == null) {
            onNotice?.invoke("未知的 DAT_REQ 请求码：$code")
            return
        }
        val now = System.currentTimeMillis()
        if (!M.requestAllowsRepeat(code)) {
            val last = lastReqTime[code]
            if (last != null && now - last < M.DAT_REQ_REPEAT_GUARD_MS) return
        }
        lastReqTime[code] = now

        val range = M.requestParamRange(code)
        sendCommand(kindType, param.coerceIn(range.first, range.last))
    }

    /**
     * ACK 帧处理（与 ESP32 固件定稿的两种 ACK 对齐）。
     *
     *  - `ACK_SESSION_INIT`（TYPE=0x01）：`PAYLOAD=[状态, resetDone]`，只用于握手；
     *  - `ACK_RESULT`（TYPE=0x00）：`PAYLOAD=[被应答 CMD 的完整 TYPE, 状态]`，普通命令结果；
     *  - 靠 **SEQ** 精确匹配命令记录；PAYLOAD[0] 的 CMD TYPE 只用于判断命令类型（如查询 SEQ 的「有/无」）。
     *
     * **不做自动重发 / 自动重查**；超时由 [enqueuePending] 里登记的 4 s 兜底判失败。
     */
    private fun handleAckFrame(frame: Frame.Ack) {
        val seq = frame.seq.toInt()

        // ---- ① 会话初始化应答：ACK_SESSION_INIT，PAYLOAD=[状态, resetDone] ----
        if (frame.kindType == M.ACK_SESSION_INIT) {
            val status = U.u8(frame.payload, 0)
            val statusText = M.ackStatusName(status)
            if (status != M.ACK_ST_RECEIVED && status != M.ACK_ST_DONE) {
                // 版本不匹配（状态 5，resetDone=0）：不再解析 DAT，等下一次握手成功
                datParseEnabled = false
                onNotice?.invoke("初始化失败：$statusText")
                return
            }
            datParseEnabled = true
            if (U.u8(frame.payload, 1) == 1) resetSeqTracking()
            return
        }

        // ---- ② 错误应答：ACK_ERROR，PAYLOAD=[被应答 CMD 的完整 TYPE, 错误码] ----
        if (frame.kindType == M.ACK_ERROR) {
            val cmdType = U.u8(frame.payload, 0)
            val errorCode = U.u8(frame.payload, 1)
            _ackEvents.value = AckEvent(
                seq = seq,
                kindType = M.kindTypeOf(cmdType.toByte()),
                status = M.ACK_ST_ERROR,
                statusText = "错误码 $errorCode"
            )
            onNotice?.invoke("收到错误应答：CMD 0x${"%02X".format(cmdType)}，错误码 $errorCode（SEQ=$seq）")
            return
        }

        // ---- ③ 普通结果应答：ACK_RESULT，PAYLOAD=[被应答 CMD 的完整 TYPE, 状态] ----
        if (frame.kindType != M.ACK_RESULT) return
        val cmdType = U.u8(frame.payload, 0)
        if (M.kindOf(cmdType.toByte()) != M.KIND_CMD) return   // 防御：PAYLOAD[0] 必须是 CMD TYPE
        val kindType = M.kindTypeOf(cmdType.toByte())
        val status = U.u8(frame.payload, 1)
        val statusText = M.ackStatusName(status)

        // 查询类命令的回复直接翻成「有 / 无」
        val replyText = if (kindType == M.CMD_QUERY_SEQ) {
            when (status) {
                M.ACK_ST_YES -> "有"
                M.ACK_ST_NO -> "无"
                else -> statusText
            }
        } else {
            statusText
        }

        // ---- ③ 待确认队列出队，再按 SEQ 回填命令记录 ----
        // 命中队列说明这条命令还在等回复；真正要改的记录按 SEQ 查库（最新一条未回复的手机发出命令）
        removePending(seq)

        // 把结果原样抛给上层（配网流程靠它给出「成功 / 失败原因」）
        _ackEvents.value = AckEvent(seq, kindType, status, statusText)

        viewModelScope.launch(Dispatchers.IO) {
            // 「未终结（待回复或只回了受理）+ 同 SEQ」才回填：ACK 0 → ACK 3 两段回执能落在
            // 同一条记录上；SEQ 只有 1~255 会回绕，已终结（3/4/5/6/超时）的历史记录不参与匹配，
            // 不会被下一轮循环的回执改写。
            val id = commandRepository.findOpenBySeq(seq)?.id ?: return@launch
            commandRepository.updateReply(id, replyText, System.currentTimeMillis())
        }
    }

    /**
     * LOG 帧：小车推来的日志 → 逐条翻译后入库。
     *
     * - 容器 TYPE 固定 `0x42`，记录区为若干条 `[记录TYPE 1B][记录LEN 1B][数据]`；
     *   条数可变（最多 8 条），**没有空槽**，[FrameCodec] 已循环解析到底；
     * - 记录 TYPE bit5-4 是等级（`0x4x=D` / `0x5x=E` / `0x6x=W` / `0x7x=I`），低 4 bit 是模块编号；
     * - 码表在 [M.logRecordText]：码表里没有的码显示 `UNKNOWN(模块/参数)` 并保留原始字节，不丢、不崩。
     *
     * SEQ 去重：LOG 维护最近 64 条 SEQ 窗口，重复 SEQ（断连补推/重传）直接丢弃，避免重复记录。
     *
     * ⚠ 全 App 唯一写日志的地方。App 自己绝不生成日志。
     */
    private fun handleLogFrame(frame: Frame.Log) {
        if (!logSeqWindow.accept(frame.seq.toInt())) return

        // 先在内存里拼好本帧所有记录，再一次性入库，避免一帧触发多次数据库事务
        val entries = frame.records.mapNotNull { record ->
            val content = M.logRecordText(record.type, record.param)
            if (content.isBlank()) return@mapNotNull null
            val level = M.logLevelOfType(record.type.toInt() and 0xFF)
            U.makeLog(content, level)
        }
        if (entries.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            logRepository.addAll(entries)
        }
    }

    // ======================================================================
    //  待确认队列 + 序列号窗口（全部只在内存，断连保留）
    // ======================================================================

    /**
     * App 侧的待确认命令（最多 [M.PENDING_MAX] 条）。
     *
     * @param seq      本次发送用的 CMD SEQ
     * @param kindType CMD 的 KIND_TYPE
     * @param sendTime 发送时刻（毫秒）
     * @param status   状态（[M.PENDING_WAITING] = 等待回复；收到 ACK 即出队，最终状态落在 Command 表）
     */
    data class PendingCmd(
        val seq: Int,
        val kindType: Int,
        val sendTime: Long,
        val status: Int = M.PENDING_WAITING
    )

    private val pendingCmds = ArrayDeque<PendingCmd>()

    private val _pendingCommands = MutableStateFlow<List<PendingCmd>>(emptyList())

    /** 待确认命令快照：断连保留，重连后仍能看出哪些命令没有回复 */
    val pendingCommands: StateFlow<List<PendingCmd>> = _pendingCommands.asStateFlow()

    /** 入队：同 SEQ 的手动重发覆盖旧条目；超过 64 条丢最旧的；同时启动 ACK 超时兜底 */
    private fun enqueuePending(seq: Int, kindType: Int) {
        val entry = PendingCmd(seq, kindType, System.currentTimeMillis())
        pendingCmds.removeAll { it.seq == seq }
        if (pendingCmds.size >= M.PENDING_MAX) pendingCmds.removeFirst()
        pendingCmds.addLast(entry)
        publishPending()
        startAckTimeout(entry)
    }

    /**
     * ACK 超时兜底（固件定稿行为：未登记 TYPE 的 CMD 整帧静默丢弃，**不会回 ACK**）。
     *
     * 4 s 内没有收到 ACK 就判失败：待确认队列出队、命令记录回填「超时无应答」。
     * **只标记失败，不重发、不自动查询**，避免与固件的静默丢弃策略打架。
     */
    private fun startAckTimeout(entry: PendingCmd) {
        viewModelScope.launch {
            delay(M.ACK_TIMEOUT_MS)
            val stillWaiting = pendingCmds.any { it.seq == entry.seq && it.sendTime == entry.sendTime }
            if (!stillWaiting) return@launch
            pendingCmds.removeAll { it.seq == entry.seq && it.sendTime == entry.sendTime }
            publishPending()
            _ackEvents.value = AckEvent(
                seq = entry.seq,
                kindType = entry.kindType,
                status = M.ACK_ST_TIMEOUT,
                statusText = "超时无应答"
            )
            withContext(Dispatchers.IO) {
                val id = commandRepository.findUnansweredBySeq(entry.seq)?.id ?: return@withContext
                commandRepository.updateReply(id, "超时无应答", System.currentTimeMillis())
            }
            onNotice?.invoke("SEQ=${entry.seq} 超时未收到 ACK")
        }
    }

    /** 收到 ACK：按 SEQ 出队 */
    private fun removePending(seq: Int) {
        if (pendingCmds.removeAll { it.seq == seq }) publishPending()
    }

    private fun publishPending() {
        _pendingCommands.value = pendingCmds.toList()
    }

    /**
     * 最近收到的 SEQ 窗口（最多 64 条，FIFO 淘汰）。
     *
     * App 侧只需要 LOG / DAT 两份（这两类方向合法）；CMD 只由 App 发出，
     * App 不再接收 CMD，所以 CMD 的 64 条接收去重表由**固件侧**维护。ACK 不判重。
     */
    private class SeqWindow(private val limit: Int = M.SEQ_WINDOW_MAX) {
        private val seqs = ArrayDeque<Int>()

        /** @return true = 首次收到（已记入窗口）；false = 重复 SEQ，调用方应丢弃 */
        fun accept(seq: Int): Boolean {
            if (seqs.contains(seq)) return false
            if (seqs.size >= limit) seqs.removeFirst()
            seqs.addLast(seq)
            return true
        }

        fun clear() = seqs.clear()
    }

    private val logSeqWindow = SeqWindow()
    private val datSeqWindow = SeqWindow()

    /** 板子复位（ACK `resetDone=1`）时清空窗口与待确认队列；断连**不清**，保留未回复记录 */
    private fun resetSeqTracking() {
        logSeqWindow.clear()
        datSeqWindow.clear()
        pendingCmds.clear()
        lastReqTime.clear()
        publishPending()
    }

    /** MainViewModel 销毁时调：停止收帧 */
    fun dispose() {
        viewModelScope.cancel()
    }
}
