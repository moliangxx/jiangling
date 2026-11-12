package com.example.jiangling.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.jiangling.data.command.Command
import com.example.jiangling.data.command.CommandRepository
import com.example.jiangling.data.log.Log
import com.example.jiangling.data.log.LogRepository
import com.example.jiangling.net.BoardDiscovery
import com.example.jiangling.net.WifiLink
import com.example.jiangling.util.M
import com.example.jiangling.util.U
import com.example.jiangling.voice.VoiceCallManager
import com.example.jiangling.voice.VoiceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ======================================================================
 *  MainViewModel —— UI 的薄入口（命令通道 + 只读查询）
 * ======================================================================
 *
 *  【职责】
 *    1) WiFi 通道状态（UDP 8081 + TCP 8080）；
 *    2) 命令、日志的只读查询（页面筛选、排序、删除）；
 *    3) 把 [BleDataViewModel] 的遥测数据拼进唯一的 [status] 流，供页面直接展示。
 *
 *  【不负责】（已拆走）
 *    - 帧解析与分发 → WifiLink.onFrame + BleDataViewModel
 *    - 实体组装、入库、ACK 匹配、去重、日志等级判定 → BleDataViewModel
 *    - 字节 ⇄ 帧编解码 → FrameCodec
 *
 *  【通道】App 只有 WiFi 一条通道：WiFi READY（UDP 已开 + TCP 8080 回程已连）
 *          才允许下发命令；断开即暂停，不回落任何其他链路。
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    // ======================================================================
    //  只读查询用的仓库（写库全部在 BleDataViewModel）
    // ======================================================================
    private val database = AppDatabase.getInstance(app)
    private val logRepository = LogRepository(database.logDao())
    private val commandRepository = CommandRepository(database.commandDao())

    /** 数据层 ViewModel：唯一处理帧、入库、ACK 匹配的地方 */
    private val data = BleDataViewModel(app)

    // ----------------------------------------------------------------------
    //  日志查询（默认起点由本类初始化给定，WiFi 首次连上时可覆盖一次）
    // ----------------------------------------------------------------------

    /**
     * 日志默认查询的时间起点（本次 App 启动时刻）。
     *
     * 只有 WiFi 首次连上时会被覆盖一次（[firstConnectTimeSetByWifi] 保证只改一次）。
     */
    var firstConnectTime: Long = System.currentTimeMillis()
        private set

    /** WiFi 是否已经用它自己的时间覆盖过默认值（保证"只改一次"） */
    private var firstConnectTimeSetByWifi = false

    /** 默认查询范围在界面上的文案（首次进入 / 恢复默认都显示"当前"） */
    val defaultRangeLabel: String = "当前"

    // ----------------------------------------------------------------------
    //  命令列表（只保留"当前显示的列表"，筛选条件由页面自己拿着）
    // ----------------------------------------------------------------------

    /** "是否有回复"筛选（只是查询参数，不是状态） */
    enum class ReplyFilter(val label: String) {
        ALL("全部"),
        ANSWERED("有回复"),
        UNANSWERED("没有")
    }

    private val _commands = MutableStateFlow<List<Command>>(emptyList())
    val commands: StateFlow<List<Command>> = _commands.asStateFlow()

    /** 当前命令订阅任务：换条件时取消旧的，避免旧结果覆盖新结果 */
    private var commandsJob: Job? = null

    /**
     * 查询命令。参数全部由页面传进来——本类不保存筛选条件。
     *
     * @param time    基准时间点（一般是被选中那天的 0 点）
     * @param isAfter true = 查 time 之后；false = 查 time 之前
     * @param asc     true = 时间升序，false = 降序
     * @param filter  是否有回复
     */
    fun queryCommands(time: Long, isAfter: Boolean, asc: Boolean, filter: ReplyFilter) {
        commandsJob?.cancel()
        commandsJob = viewModelScope.launch {
            commandRepository.query(time, isAfter).collect { list ->
                val filtered = when (filter) {
                    ReplyFilter.ALL -> list
                    ReplyFilter.ANSWERED -> list.filter { it.reply.isNotBlank() }
                    ReplyFilter.UNANSWERED -> list.filter { it.reply.isBlank() }
                }
                _commands.value = if (asc) {
                    filtered.sortedWith(compareBy({ it.sendTime }, { it.id }))
                } else {
                    filtered.sortedWith(compareByDescending<Command> { it.sendTime }.thenByDescending { it.id })
                }
            }
        }
    }

    /** 有过命令记录的日期列表（yyyy-MM-dd，倒序），供"按时间查询"弹窗使用 */
    val commandDates: StateFlow<List<String>> = commandRepository.times
        .map { times -> times.map { U.formatDate(it) }.distinct() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 删除命令：只删当前列表（判断规则和查询完全一致，参数由页面传） */
    fun deleteCommands(time: Long, isAfter: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { commandRepository.delete(time, isAfter) }
    }

    // ----------------------------------------------------------------------
    //  日志列表（只保留"当前显示的列表"，筛选条件由页面自己拿着）
    // ----------------------------------------------------------------------

    private val _logs = MutableStateFlow<List<Log>>(emptyList())
    val logs: StateFlow<List<Log>> = _logs.asStateFlow()

    private var logsJob: Job? = null

    /**
     * 查询日志。参数全部由页面传进来——本类不保存筛选条件。
     *
     * @param time    基准时间点（一般是被选中那天的 0 点）
     * @param isAfter true = 查 time 之后；false = 查 time 之前
     * @param asc     true = 时间升序，false = 降序
     */
    fun queryLogs(time: Long, isAfter: Boolean, asc: Boolean) {
        logsJob?.cancel()
        logsJob = viewModelScope.launch {
            logRepository.query(time, isAfter).collect { list ->
                _logs.value = if (asc) {
                    list.sortedWith(compareBy({ it.time }, { it.id }))
                } else {
                    list.sortedWith(compareByDescending<Log> { it.time }.thenByDescending { it.id })
                }
            }
        }
    }

    /** 删除日志：只删当前列表（判断规则和查询完全一致，参数由页面传） */
    fun deleteLogs(time: Long, isAfter: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { logRepository.delete(time, isAfter) }
    }

    /** 数据库里出现过日志的日期列表（yyyy-MM-dd，倒序），供"按时间查询"弹窗选择 */
    val logDates: StateFlow<List<String>> = logRepository.times
        .map { times -> times.map { U.formatDate(it) }.distinct() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ======================================================================
    //  小车状态（UI 唯一的状态源）
    // ======================================================================

    /** 命令通道状态源：READY 才允许下发（也是 UI「已连接」的唯一依据） */
    private val _wifiState = MutableStateFlow(WifiLink.State.IDLE)

    /**
     * UI 看到的小车状态 = 通道字段（本类维护）+ 遥测字段（[BleDataViewModel] 的 DAT 帧）。
     *
     * `connected` 现在只表示 WiFi 命令通道 READY，不再有蓝牙链路含义。
     */
    val status: StateFlow<CarStatus> = combine(_wifiState, data.carStatus) { wifiState, telemetry ->
        val ready = wifiState == WifiLink.State.READY
        telemetry.copy(
            connected = ready,
            runStatus = when (wifiState) {
                WifiLink.State.READY -> "已连接"
                WifiLink.State.CONNECTING -> "连接中"
                WifiLink.State.DOWN -> "已断开"
                WifiLink.State.IDLE -> "未连接"
            }
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, CarStatus())

    /**
     * 设备静态信息（只读给 UI）：DAT_INFO 三个子帧合并后的缓存。
     * 未收到为 null；每次握手成功固件会重发一轮，App 不需要周期查询。
     */
    val deviceInfo: StateFlow<DeviceInfo?> get() = data.deviceInfo

    /** 通用错误/提示消息（通过 U.s Snackbar + U.t Toast 弹出） */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    // ======================================================================
    //  命令通道：只有 WiFi（UDP 8081 下行 + TCP 8080 回程）
    //
    //  【判定规则】（界面上必须显示，不允许静默切换）
    //    WiFi READY（UDP 已开 + TCP 8080 回程已连）→「WiFi 已连接」
    //    其余                                       →「未连接」，命令一律暂停
    // ======================================================================

    enum class CommandChannel(val label: String) {
        NONE("未连接"),
        WIFI("WiFi 已连接")
    }

    /** 当前命令通道（两态，UI 直接显示） */
    val commandChannel: StateFlow<CommandChannel> = _wifiState
        .map { if (it == WifiLink.State.READY) CommandChannel.WIFI else CommandChannel.NONE }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CommandChannel.NONE)

    /**
     * 主板地址（App 尝试建链的目标）：
     * ① 板子广播 `0x87`（UDP 8082，最快，推荐来源）；
     * ② TCP 上收到的 `0x87`（权威来源，与广播同值）；
     * ③ 用户手填（10 s 没广播时的兜底）；
     * ④ 临时 AP 期间才用预期网关 `192.168.4.1`。
     *
     * 常态下**不再无条件拿 `192.168.4.1` 试连**——那不是热点里的地址，只会一直连不上。
     */
    private val _boardIp = MutableStateFlow<String?>(null)
    val boardIp: StateFlow<String?> = _boardIp.asStateFlow()

    /** 上一次 WiFi 状态：只在上线/掉线翻转时提示，重试过程不刷屏 */
    private var lastWifiState = WifiLink.State.IDLE

    private val wifi = WifiLink(
        scope = viewModelScope,
        onFrame = { frame -> data.onExternalFrame(frame) },
        onState = { state, reason -> onWifiStateChanged(state, reason) }
    )

    /**
     * 板子地址广播监听（UDP 8082）：随 ViewModel 常驻，切后台不停。
     *
     * 收到合法广播（已由 [BoardDiscovery] 做来源校验与同 IP 2 s 去重）就更新 [boardIp]，
     * 由下面的地址汇总任务重建 UDP 8081 / TCP 8080 通道。
     */
    private val discovery = BoardDiscovery(viewModelScope) { ip -> _boardIp.value = ip }

    private fun onWifiStateChanged(state: WifiLink.State, reason: String) {
        _wifiState.value = state
        when {
            state == WifiLink.State.READY && lastWifiState != WifiLink.State.READY -> {
                // WiFi 会话握手：UDP 8081 首帧必须是 `0xC0`（SEQ=0），见 命令.md `0xC0` 行
                sendWifiSessionInit()
                _message.value = "命令通道：WiFi 已连接（${boardIp.value ?: "已连接"}）"
            }

            // 运行期只有 WiFi 一条通道：断开就暂停下发，不假装还有兜底
            state == WifiLink.State.DOWN && lastWifiState == WifiLink.State.READY -> {
                if (apSwitchPending) {
                    apSwitchSawDown = true
                    _message.value = "板子正在临时开 AP（换网用），连接断开属正常；请把手机连到它的临时热点 $HOTSPOT_SSID"
                } else {
                    _message.value = "WiFi 断开（$reason），命令已暂停，正在自动重连"
                }
            }
        }
        lastWifiState = state
    }

    /**
     * WiFi 会话握手：直接用 `SEQ=0` 编一帧 `0xC0` 发出。
     *
     * 不占用 [BleDataViewModel] 的命令 SEQ 计数器、不建数据库记录——
     * 它只是会话建立帧，不是用户命令。
     */
    private fun sendWifiSessionInit() {
        val frame = wifi.encodeCommandFrame(
            M.CMD_SESSION_INIT,
            M.cmdPayload(M.CMD_SESSION_INIT, 0),
            M.SEQ_INIT
        ) ?: return
        wifi.send(frame)
    }

    /**
     * 是否有可用命令通道。
     *
     * **只认 WiFi**（READY = UDP 8081 已开 + TCP 8080 回程已连）。
     */
    fun canSendCommand(): Boolean = _wifiState.value == WifiLink.State.READY

    /**
     * 最近一条 ACK（调试页「发送命令」卡片下方就地展示回执用）。
     *
     * 只是转发 [BleDataViewModel.ackEvents]，不额外维护第二份状态。
     */
    val lastAck = data.ackEvents

    // ======================================================================
    //  命令发送（薄封装：真正的发送 + 入库 + 等 ACK 都在 BleDataViewModel）
    // ======================================================================

    /**
     * 发送命令。
     *
     * 本类只做"有没有连接"的判断和界面提示；
     * 发帧、建命令记录、记录当前等 ACK 的 SEQ 都由 [BleDataViewModel] 完成。
     *
     * @param kindType CMD 的 KIND_TYPE（见 M.kt 的 CMD_* 常量）
     * @param param 命令参数（V1.8 分三类）：
     *              运动命令 = 0~100 挡位/转弯幅度百分比；`CMD_PITCH`/`CMD_YAW` = 0~255 角度映射；
     *              `CMD_QUERY_SEQ` = 0~255 目标 SEQ；`CMD_AUDIO_TEST` = 0/1/2；`CMD_STOP` 无参数
     */
    fun sendCommand(kindType: Int, param: Int = 0): Boolean {
        if (!canSendCommand()) {
            _message.value = "WiFi 未连接，命令已暂停"
            U.s("WiFi 未连接，命令已暂停")
            return false
        }
        if (data.sendCommand(kindType, param) == null) {
            _message.value = "命令发送失败（需要 WiFi 已连接）"
            U.s("命令发送失败")
            return false
        }
        return true
    }

    /**
     * 命令路由：只有 WiFi 一条出口。
     *
     * 所有命令（含 `0xC0` 会话握手与 `0xC4`~`0xCA` 初始化/配网）都走 UDP 8081；
     * 未 READY 时直接返回 false，由上层提示暂停。
     */
    private fun routeCommand(kindType: Int, payload: ByteArray, seq: Byte): Boolean =
        sendViaWifi(kindType, payload, seq)

    /** WiFi 出口：帧字节由 [WifiLink.encodeCommandFrame] 生成 */
    private fun sendViaWifi(kindType: Int, payload: ByteArray, seq: Byte): Boolean {
        if (_wifiState.value != WifiLink.State.READY) return false
        val frame = wifi.encodeCommandFrame(kindType, payload, seq) ?: return false
        return wifi.send(frame)
    }

    /**
     * 运动矢量（`0xEB`）流式帧：`drive`/`turn` 各 `-100`~`+100`，**`turn` 正 = 左转**。
     *
     * 200ms 节拍调用，失败不弹提示（避免断连时每 200ms 刷一次 Toast）。
     */
    fun sendMoveVector(drive: Int, turn: Int): Boolean =
        canSendCommand() && data.sendMoveVector(drive, turn)

    /** 云台矢量（`0xEC`）流式帧：`pan` 正 = 右、`tilt` 正 = 上；与移动独立 */
    fun sendGimbalVector(pan: Int, tilt: Int): Boolean =
        canSendCommand() && data.sendGimbalVector(pan, tilt)

    /**
     * 流式命令（轮盘/云台）发不出去时的可见反馈。
     *
     * 5 Hz 下不能每帧都弹，3 秒内只提示一次；上机时看到这条就说明链路没通或队列满了，
     * 不是「拖了没反应」。
     */
    fun notifyCommandDropped() {
        val now = System.currentTimeMillis()
        if (now - lastDropNoticeAt < DROP_NOTICE_INTERVAL_MS) return
        lastDropNoticeAt = now
        _message.value = if (canSendCommand()) {
            "命令未发出：发送队列已满，正在重连通道"
        } else {
            "命令未发出：WiFi 通道未就绪"
        }
    }

    /** 最近一次「命令未发出」提示时刻，用于节流 */
    private var lastDropNoticeAt = 0L

    /**
     * WiFi 配置（`0xC4~0xC7`）转发：先做连接判断，再由 [BleDataViewModel] 组 PAYLOAD 发送。
     * 凭据是变长结构（`[ssidLen][SSID][密码]`），不走通用 int 参数入口。
     */
    fun sendWifiMode(mode: Int): Boolean = sendWifi { data.sendWifiMode(mode) }

    fun sendWifiStaConfig(ssid: String, password: String): Boolean =
        sendWifi { data.sendWifiStaConfig(ssid, password) }

    fun sendWifiApConfig(ssid: String, password: String): Boolean =
        sendWifi { data.sendWifiApConfig(ssid, password) }

    private inline fun sendWifi(block: () -> Byte?): Boolean {
        if (!canSendCommand()) {
            _message.value = "WiFi 未连接"
            U.s("WiFi 未连接")
            return false
        }
        if (block() == null) {
            _message.value = "命令发送失败"
            U.s("命令发送失败")
            return false
        }
        return true
    }

    // ======================================================================
    //  给板子配网：凭据由 App 记忆，只在下发时提交（不自动补发）
    //
    //  【常态拓扑】手机开热点 `jianglingAPP` / `31957172yz`（2.4GHz），
    //    板子上电以 STA 连入，App 也连在同一热点，双方同网段走 UDP 8081 / TCP 8080。
    //    板子**不是**热点；AP 只在连不上默认网络（20 s × 3 + 30 s × 3，约两分钟）或 App 主动换网时短时开启。
    //    - 手机热点档：固定凭据（系统热点不能指定 SSID/密码，App 只做引导）；
    //    - 路由器档：用户填 SSID/密码，App 记住后手动下发。
    //  App → 主板：CMD_WIFI_STA_CONFIG(0xC5) 写凭据 → CMD_INIT_COMMIT(0xC8, STA) 提交；
    //  主板 → 副板：凭据由主板经串口同步，App 不参与。
    // ======================================================================

    /** 配网模式（两者协议侧都是 STA，仅凭据来源不同） */
    enum class WifiMode { HOTSPOT, ROUTER }

    /** App 侧记忆的一组凭据 */
    data class WifiCredential(val mode: WifiMode, val ssid: String, val password: String)

    /** 配网流程状态（UI 直接展示） */
    sealed interface ProvisionState {
        /** 还没有记忆 / 已清空 */
        data object Idle : ProvisionState

        /** 凭据 + 提交命令已发出，等主板回执 */
        data class Sending(val auto: Boolean) : ProvisionState

        /**
         * 已受理「临时开 AP 换网」（`0xC8=1` 回了 ACK 0），板子正在离开 STA、重起 AP。
         *
         * 这段时间链路会**主动断开**，属于正常切换，不能当故障；
         * AP 就绪后手机需要连到板子的临时热点 `jianglingAPP`，App 用 `0xC9` 探针确认到位。
         */
        data object SwitchingToAp : ProvisionState

        /** 主板已受理（ACK 0），正在联网，最多等约 25 秒 */
        data class WaitingNet(val ssid: String, val auto: Boolean) : ProvisionState

        /** 完成。[toAp] = true 表示这次是「临时开 AP 换网」，板子已重新起好热点 */
        data class Success(
            val ssid: String,
            val auto: Boolean,
            val toAp: Boolean = false
        ) : ProvisionState

        /** 失败：原因直接给用户看，页面保留输入可重试 */
        data class Failed(val reason: String) : ProvisionState
    }

    private val provisionPrefs =
        app.getSharedPreferences("wifi_provision", Application.MODE_PRIVATE)

    /**
     * 用户手填的主板地址（持久化）。
     *
     * 只在**没有广播、也没有 `0x87` 上报**时才拿来兜底：典型是热点没转发广播，
     * 或者板子跑的是还没实现广播的旧固件。有广播后自动让位（广播值优先级更高）。
     */
    private val _manualIp = MutableStateFlow(loadManualBoardIp())

    /** 手填地址是否设置过（UI 用来显示「手动」标记） */
    val manualBoardIp: StateFlow<String?> = _manualIp.asStateFlow()

    /**
     * 保存/清除手填的主板地址。
     *
     * @param text 空串 = 清除；否则必须是合法的 `A.B.C.D`
     * @return false 表示格式不合法，未保存（原因已进 [message]）
     */
    fun setManualBoardIp(text: String): Boolean {
        val ip = text.trim()
        if (ip.isEmpty()) {
            _manualIp.value = null
            provisionPrefs.edit().remove(KEY_MANUAL_IP).apply()
            _message.value = "已清除手填地址，等待板子广播"
            return true
        }
        if (!isValidIpv4(ip)) {
            _message.value = "IP 格式不对：请填 A.B.C.D，每段 0~255"
            return false
        }
        _manualIp.value = ip
        provisionPrefs.edit().putString(KEY_MANUAL_IP, ip).apply()
        _message.value = "已改为手动地址 $ip，正在重连"
        return true
    }

    private fun loadManualBoardIp(): String? =
        provisionPrefs.getString(KEY_MANUAL_IP, null)?.takeIf { isValidIpv4(it) }

    private fun isValidIpv4(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } && p.toInt() in 0..255 }
    }

    /** App 记忆的凭据；null = 用户清空过，此后不再自动补发 */
    private val _credential = MutableStateFlow(loadCredential())
    val savedCredential: StateFlow<WifiCredential?> = _credential.asStateFlow()

    private val _provisionState = MutableStateFlow<ProvisionState>(ProvisionState.Idle)
    val provisionState: StateFlow<ProvisionState> = _provisionState.asStateFlow()

    /** 正在等待回执的「提交初始化」SEQ；凭据命令的 ACK 不参与成功判定 */
    private var commitSeq: Int? = null

    /** 正在等待回执的 `0xC4`（切 AP 预检）SEQ */
    private var modeSeq: Int? = null

    /** 正在等待回执的 `0xC9`（AP 就绪探针）SEQ */
    private var probeSeq: Int? = null

    /** 正在等待回执的 `0xCA`（清凭据重启）SEQ */
    private var factorySeq: Int? = null

    /** 已受理回 AP，正在等板子重起热点（期间断链属正常） */
    private var apSwitchPending = false

    /** 切 AP 期间是否真的断过链（没断过说明板子还没开始切，不发探针） */
    private var apSwitchSawDown = false

    /** 回 AP 的受理时刻：切换后短时间内链路可能还 READY，先别急着发探针 */
    private var apSwitchAtMs = 0L
    private var probeJob: Job? = null
    private var probeAttempt = 0

    private var provisioningSsid: String = ""
    private var provisioningAuto = false
    private var netPollJob: Job? = null

    init {
        // 唯一命令出口：WiFi（UDP 8081 下行）
        data.transport = { kindType, payload, seq -> routeCommand(kindType, payload, seq) }
        data.rawTransport = { bytes -> wifi.send(bytes) }
        data.onNotice = { text -> _message.value = text }

        // 地址来源四选一（见 [boardIp]）：广播 > 手填 > 临时 AP 预期网关。
        // TCP 上的 0x87 只用来「抢先知道」——它本身需要地址，通路建好后才有。
        viewModelScope.launch {
            data.carStatus.map { it.boardIp }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { ip -> _boardIp.value = ip }
        }
        viewModelScope.launch {
            combine(_boardIp, _manualIp, _provisionState) { ip, manual, prov ->
                ip ?: manual ?: if (
                    prov is ProvisionState.SwitchingToAp ||
                    (prov is ProvisionState.Success && prov.toAp)
                ) M.BOARD_AP_IP else null
            }
                .distinctUntilChanged()
                .collect { target ->
                    if (target == null) wifi.detach() else wifi.attach(target)
                }
        }
        discovery.start()

        // WiFi 首次连上时，用该时刻覆盖日志默认查询起点（只改一次）
        viewModelScope.launch {
            commandChannel.collect { channel ->
                if (channel == CommandChannel.WIFI && !firstConnectTimeSetByWifi) {
                    firstConnectTimeSetByWifi = true
                    firstConnectTime = System.currentTimeMillis()
                }
            }
        }

        // 只认自己发出的这几条命令的回执（0xC4 / 0xC8 / 0xC9 / 0xCA），
        // 避免和调试页其它命令的 ACK 混淆
        viewModelScope.launch {
            data.ackEvents.collect { event ->
                if (event == null) return@collect
                val seq = event.seq.toInt()
                when (seq) {
                    modeSeq -> handleModeAck(event.status)
                    commitSeq -> handleCommitAck(event.status)
                    probeSeq -> handleProbeAck(event.status)
                    factorySeq -> handleFactoryAck(event.status)
                }
            }
        }

        // 链路重新 READY 时：如果正在等板子回 AP，补一次 0xC9 探针
        // （切换瞬间的 ACK 3 往往随旧链路一起丢了）
        viewModelScope.launch {
            status.map { it.connected }
                .distinctUntilChanged()
                .collect { connected ->
                    if (!connected) {
                        // 回 AP 期间的断开是主动切换，不取消等待、不报故障
                        if (!apSwitchPending) {
                            netPollJob?.cancel()
                            netPollJob = null
                            commitSeq = null
                        }
                    } else if (apSwitchPending) {
                        probeApReady()
                    }
                }
        }
    }

    // ======================================================================
    //  配网回执处理（三段式：0xC4 → ACK3 → 0xC8 → ACK0 → ACK3）
    // ======================================================================

    /** `0xC4` 回执：3 = 预检通过，继续发 `0xC8 [1][0]`；5 只表示模式被拒，与凭据无关 */
    private fun handleModeAck(status: Int) {
        modeSeq = null
        if (status != M.ACK_ST_DONE) {
            failProvision(modeRejectReason(status))
            return
        }
        // 0xC4 只改 RAM，真正切模式靠 0xC8；AP 模式不需要先下发 STA 凭据
        val seq = data.sendInitCommit(M.WIFI_MODE_AP)
        if (seq == null) {
            failProvision("换网命令下发失败，请重试")
            return
        }
        commitSeq = seq.toInt()
        apSwitchPending = true
        apSwitchSawDown = false
        apSwitchAtMs = System.currentTimeMillis()
        probeAttempt = 0
        _provisionState.value = ProvisionState.SwitchingToAp
        _message.value = "板子已受理，正在临时切到 AP（本次连接会断开）"
    }

    /** `0xC8` 回执：0 = 已受理、开始切模式（不是失败）；3 = 目标网络/AP 已就绪 */
    private fun handleCommitAck(status: Int) {
        if (apSwitchPending) {
            when (status) {
                M.ACK_ST_RECEIVED -> {
                    commitSeq = null
                    apSwitchAtMs = System.currentTimeMillis()
                    _provisionState.value = ProvisionState.SwitchingToAp
                    _message.value =
                        "板子正在离开 STA、重起临时 AP；请把手机连到它的临时热点 ${HOTSPOT_SSID}"
                }
                M.ACK_ST_DONE -> finishApSwitch()
                else -> {
                    commitSeq = null
                    apSwitchPending = false
                    failProvision(commitRejectReason(status, toAp = true))
                }
            }
            return
        }

        when (status) {
            M.ACK_ST_RECEIVED ->
                _provisionState.value = ProvisionState.WaitingNet(provisioningSsid, provisioningAuto)

            M.ACK_ST_DONE -> {
                // 板子在新网络就绪后才主动回 ACK 3（= 初始化完成）
                commitSeq = null
                netPollJob?.cancel()
                netPollJob = null
                _provisionState.value =
                    ProvisionState.Success(provisioningSsid, provisioningAuto)
                _message.value = "初始化完成（新网络已就绪）"
            }

            else -> {
                commitSeq = null
                failProvision(commitRejectReason(status, toAp = false))
            }
        }
    }

    /** 切换完成：板子的临时 AP 已就绪（配完要回 STA，不是常态） */
    private fun finishApSwitch() {
        commitSeq = null
        probeSeq = null
        probeJob?.cancel()
        probeJob = null
        apSwitchPending = false
        apSwitchSawDown = false
        probeAttempt = 0
        _provisionState.value = ProvisionState.Success(HOTSPOT_SSID, auto = false, toAp = true)
        _message.value = "板子临时 AP 已就绪：${HOTSPOT_SSID}，可继续配网（配完回 STA）"
    }

    /**
     * `0xC9` 探针：链路重新连上后确认板子是否已完成切换。
     *
     * 只在「切 AP 期间确实断过链」且断开后重新 READY 时才发，避免拿旧网络的 READY 误判。
     */
    private fun probeApReady() {
        if (!apSwitchPending || !canSendCommand()) return
        if (!apSwitchSawDown) return
        if (System.currentTimeMillis() - apSwitchAtMs < AP_SWITCH_GRACE_MS) return
        // 探针只能发一次：还没收到上一轮回执时不要叠加
        if (probeSeq != null) return

        val seq = data.sendInitStatus() ?: return
        probeSeq = seq.toInt()
        probeAttempt++
        probeJob?.cancel()
        probeJob = viewModelScope.launch {
            delay(PROBE_INTERVAL_MS)
            if (probeSeq == seq.toInt()) {
                probeSeq = null
                retryOrGiveUpApProbe()
            }
        }
    }

    private fun handleProbeAck(status: Int) {
        probeSeq = null
        probeJob?.cancel()
        probeJob = null
        when (status) {
            M.ACK_ST_DONE -> finishApSwitch()
            // 0 = 已收到 0xC8 但 WiFi 未就绪；4 = 没提交过 0xC8：继续等下一轮
            M.ACK_ST_RECEIVED, M.ACK_ST_UNKNOWN -> retryOrGiveUpApProbe()
            else -> {
                apSwitchPending = false
                failProvision(commitRejectReason(status, toAp = true))
            }
        }
    }

    private fun retryOrGiveUpApProbe() {
        if (probeAttempt >= MAX_AP_PROBE) {
            apSwitchPending = false
            failProvision("板子没有进入临时 AP（0xC9 连续 ${MAX_AP_PROBE} 次未确认），请给板子断电重启后再试")
        } else {
            probeApReady()
        }
    }

    /** `0xCA` 回执：3 = 已清空并重启（之后先用默认凭据重连手机热点） */
    private fun handleFactoryAck(status: Int) {
        factorySeq = null
        if (status == M.ACK_ST_DONE) {
            _provisionState.value = ProvisionState.Idle
            _message.value =
                "板子已清空配网数据，重启后先用默认凭据重连热点 ${HOTSPOT_SSID}；连不上才会临时开 AP"
        } else {
            failProvision("清除失败：状态码 $status")
        }
    }

    private fun modeRejectReason(status: Int): String = when (status) {
        M.ACK_ST_UNKNOWN -> "固件不认识 0xC4（版本可能过旧），无法切 AP"
        M.ACK_ST_ERROR -> "板子拒绝了 AP 模式（0xC4 返回 5：模式值非法或固件未启用 AP）"
        M.ACK_ST_CANNOT -> "板子正忙，请稍后重试"
        M.ACK_ST_TIMEOUT -> "超时：板子 ${M.ACK_TIMEOUT_MS / 1000} 秒未回 0xC4"
        else -> "换网预检失败：状态码 $status"
    }

    private fun commitRejectReason(status: Int, toAp: Boolean): String = when (status) {
        M.ACK_ST_UNKNOWN -> "主板不认识初始化命令（固件版本可能过旧）"
        // 缺 STA 凭据的校验在 0xC8 这一步，不在 0xC4
        M.ACK_ST_ERROR ->
            if (toAp) "板子拒绝切 AP（0xC8 返回 5）"
            else "板子没有可用 STA 凭据，或凭据被拒（0xC8 返回 5），请重新下发"
        M.ACK_ST_CANNOT -> "板子正在初始化，请稍后重试"
        M.ACK_ST_TIMEOUT -> "超时：主板 ${M.ACK_TIMEOUT_MS / 1000} 秒未回应"
        else -> "配网失败：状态码 $status"
    }

    private fun failProvision(reason: String) {
        _provisionState.value = ProvisionState.Failed(reason)
        _message.value = reason
    }

    /**
     * 保存用户选择并立即下发。
     *
     * 手机热点模式忽略传入的 SSID/密码，固定用 [HOTSPOT_SSID] / [HOTSPOT_PASSWORD]。
     */
    fun applyCredential(mode: WifiMode, ssid: String, password: String): Boolean {
        val credential = if (mode == WifiMode.HOTSPOT) {
            WifiCredential(WifiMode.HOTSPOT, HOTSPOT_SSID, HOTSPOT_PASSWORD)
        } else {
            WifiCredential(WifiMode.ROUTER, ssid.trim(), password)
        }
        if (M.wifiCredentialPayload(credential.ssid, credential.password) == null) {
            _provisionState.value = ProvisionState.Failed(
                "名称或密码不合法：SSID 需 1~32 字节，密码留空或 8~63 字节，总长不超过 96 字节"
            )
            return false
        }
        saveCredential(credential)
        return sendCredential(credential, auto = false)
    }

    /** 重发当前记忆的凭据（改了密码 / 上次失败后重试用），不重复写盘 */
    fun resendCredential(): Boolean {
        val credential = _credential.value ?: return false
        return sendCredential(credential, auto = false)
    }

    /**
     * 换网入口一：**让板子临时开 AP 换网**（不重启、不清凭据）。
     *
     * 这是兜底档，不是常态：常态是手机开热点、板子以 STA 连入。
     * 时序（只用现有命令字）：
     * ```
     * 0xC4 [1]        → ACK 3   预检，仅 RAM
     * 0xC8 [1][0]     → ACK 0   离开 STA、重起 AP（连接会断，不是失败）
     *                 → ACK 3   AP 就绪 = 初始化完成
     * ```
     * 断开期间链路状态显示「正在切 AP」，不弹故障；手机需手动连到板子的临时热点 `jianglingAPP`，
     * 这一档的预期网关才是 `192.168.4.1`。
     */
    fun returnToApForProvision(): Boolean {
        if (!canSendCommand()) {
            failProvision("WiFi 未连接：请确认手机开着热点 $HOTSPOT_SSID，且手机和板子都在这个热点里")
            return false
        }
        resetProvisionState()
        val seq = data.sendWifiMode(M.WIFI_MODE_AP)
        if (seq == null) {
            failProvision("换网命令下发失败，请重试")
            return false
        }
        modeSeq = seq.toInt()
        _provisionState.value = ProvisionState.Sending(auto = false)
        _message.value = "正在让板子临时开 AP（换网用）…"
        return true
    }

    /**
     * 换网入口二：**清凭据并重启**（`0xCA`）。
     *
     * 和「开 AP 换网」是两个入口：这条会丢掉板子 RAM 里的全部配网数据并重启，
     * 重启后先用默认凭据连手机热点，连不上才临时开 AP；前者不重启、不清数据，只是切模式。
     */
    fun factoryReset(): Boolean {
        if (!canSendCommand()) {
            failProvision("WiFi 未连接：请确认手机开着热点 $HOTSPOT_SSID，且手机和板子都在这个热点里")
            return false
        }
        resetProvisionState()
        val seq = data.sendInitFactoryReset()
        if (seq == null) {
            failProvision("清除命令下发失败，请重试")
            return false
        }
        factorySeq = seq.toInt()
        _provisionState.value = ProvisionState.SwitchingToAp
        _message.value = "已下发清除，板子会重启并先用默认凭据重连热点 ${HOTSPOT_SSID}"
        return true
    }

    /**
     * 清空 App 侧记忆：此后不再自动补发。
     *
     * 注意：板子当前仍在用旧凭据联网，直到它断电重启；重启后需要重新保存并下发。
     */
    fun clearCredential() {
        provisionPrefs.edit().clear().apply()
        _credential.value = null
        commitSeq = null
        netPollJob?.cancel()
        netPollJob = null
        _provisionState.value = ProvisionState.Idle
        _message.value = "已清空配网记忆，重启板子后不会再自动补发"
    }

    /** 关掉失败提示（不清记忆） */
    fun resetProvisionState() {
        modeSeq = null
        commitSeq = null
        probeSeq = null
        factorySeq = null
        probeJob?.cancel()
        probeJob = null
        probeAttempt = 0
        apSwitchPending = false
        apSwitchSawDown = false
        netPollJob?.cancel()
        netPollJob = null
        _provisionState.value = ProvisionState.Idle
    }

    /** 真正下发：写凭据 + 提交初始化（全程走 WiFi；手机开热点、板子和 App 都连在这个热点里） */
    private fun sendCredential(credential: WifiCredential, auto: Boolean): Boolean {
        if (!canSendCommand()) {
            // 自动补发失败不打扰用户（连接瞬间的抖动会自行重连）
            if (!auto) {
                _provisionState.value = ProvisionState.Failed(
                    "WiFi 未连接：请确认手机开着热点 $HOTSPOT_SSID，且手机和板子都在这个热点里"
                )
            }
            return false
        }
        netPollJob?.cancel()
        commitSeq = null
        provisioningSsid = credential.ssid
        provisioningAuto = auto
        _provisionState.value = ProvisionState.Sending(auto)
        // 先写凭据，再提交初始化（提交时会读已写入的 STA 凭据）
        if (data.sendWifiStaConfig(credential.ssid, credential.password) == null) {
            _provisionState.value = ProvisionState.Failed("凭据下发失败：WiFi 发送失败，请重试")
            return false
        }
        val seq = data.sendInitCommit(M.WIFI_MODE_STA)
        if (seq == null) {
            _provisionState.value = ProvisionState.Failed("初始化命令下发失败，请重试")
            return false
        }
        commitSeq = seq.toInt()
        _provisionState.value = ProvisionState.WaitingNet(credential.ssid, auto)
        if (auto) _message.value = "已自动补发配网凭据：${credential.ssid}"
        startNetPoll()
        return true
    }

    /**
     * 提交后的超时看门狗：25 秒内没等到 `ACK(0xC8,3)` 才判失败。
     *
     * 成功路径由板子主动回 ACK 3 触发（见上面的 ackEvents 收集），不再轮询 `0x86`。
     * 协议没有单独的「认证失败 / 找不到网络」错误码，所以超时文案如实说明几种可能，
     * 不谎报成某一种确定原因。
     */
    private fun startNetPoll() {
        netPollJob?.cancel()
        netPollJob = viewModelScope.launch {
            delay(POLL_TIMEOUT_MS)
            if (_provisionState.value is ProvisionState.WaitingNet) {
                val hint = if (provisioningAuto) "，可点「重新下发」重试" else ""
                commitSeq = null
                _provisionState.value = ProvisionState.Failed(
                    "超时：板子没连上「$provisioningSsid」。常见原因：密码错误（认证失败）、" +
                        "热点不在范围内、热点不是 2.4GHz（ESP32 不支持 5G）$hint"
                )
            }
        }
    }

    private fun saveCredential(credential: WifiCredential) {
        provisionPrefs.edit()
            .putString(KEY_MODE, credential.mode.name)
            .putString(KEY_SSID, credential.ssid)
            .putString(KEY_PASSWORD, credential.password)
            .apply()
        _credential.value = credential
    }

    private fun loadCredential(): WifiCredential? {
        val mode = provisionPrefs.getString(KEY_MODE, null) ?: return null
        val ssid = provisionPrefs.getString(KEY_SSID, null) ?: return null
        val password = provisionPrefs.getString(KEY_PASSWORD, "") ?: ""
        val parsed = runCatching { WifiMode.valueOf(mode) }.getOrNull() ?: return null
        return WifiCredential(parsed, ssid, password)
    }

    // ======================================================================
    //  语音链路（首页左上角圆形开关）
    //  采集实现和失败复位都在 VoiceCallManager，本类只做转发
    // ======================================================================

    private val voiceCall = VoiceCallManager(app) { text ->
        _message.value = text
        U.s(text)
    }

    /** 语音开关状态：Off / On（失败会直接复位成 Off 并弹一次提示） */
    val voiceState: StateFlow<VoiceState> = voiceCall.state

    /**
     * 麦克风是否被语音通话占用。
     *
     * 语音与 AI 对话由主板判定互斥（语音首包一到，主板自己把 AI 让出），
     * App 侧不提供 AI 开关，只把占用状态如实显示，避免出现两边同时「可用」的假象。
     */
    val micOccupied: StateFlow<Boolean> = voiceCall.state
        .map { it is VoiceState.On }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun hasVoicePermission(): Boolean = voiceCall.hasPermission()

    /** 开启采集；false 表示权限/设备/启动失败，原因已进 message */
    fun startVoice(): Boolean = voiceCall.start()

    fun stopVoice() = voiceCall.stop()

    /** 录音权限被拒：明确提示，开关保持关闭 */
    fun reportVoicePermissionDenied() = voiceCall.reportPermissionDenied()

    /** 消费掉 message（弹完 Toast 后调） */
    fun consumeMessage() { _message.value = null }

    /** Activity 销毁（ViewModel 被系统杀死）时调 */
    override fun onCleared() {
        discovery.stop()
        wifi.detach()
        data.dispose()
        voiceCall.release()
        super.onCleared()
    }

    companion object {
        /**
         * 默认拓扑的固定凭据：手机开这个热点，板子以 STA 连入，App 也连在同一个热点。
         *
         * App **不负责开热点**：`LocalOnlyHotspot` 的 SSID/密码由系统随机生成，
         * API 33 传入配置也保留覆盖权，所以只能把固定值展示给用户，让他在系统设置里照着开。
         * 板子自己的 AP 只在兜底换网时短时开启，也用这组名称/密码。
         */
        const val HOTSPOT_SSID = "jianglingAPP"
        const val HOTSPOT_PASSWORD = "31957172yz"

        private const val KEY_MODE = "provision_mode"
        private const val KEY_SSID = "provision_ssid"
        private const val KEY_PASSWORD = "provision_password"

        /** 手填主板地址（只在没广播时兜底，见 [setManualBoardIp]） */
        private const val KEY_MANUAL_IP = "manual_board_ip"

        /** 提交后等 `ACK(0xC8,3)` 的上限（板子要切网络 + 写凭据） */
        private const val POLL_TIMEOUT_MS = 25_000L

        /** 收到 `0xC8` 受理后，多久之内不算「链路该断了」——避免拿旧链路误发探针 */
        private const val AP_SWITCH_GRACE_MS = 3_000L

        /** `0xC9` 探针的间隔 / 最多探几次 */
        private const val PROBE_INTERVAL_MS = 2_500L
        private const val MAX_AP_PROBE = 6

        /** 「命令未发出」提示的最小间隔（5 Hz 流式帧下防止刷屏） */
        private const val DROP_NOTICE_INTERVAL_MS = 3_000L
    }
}
