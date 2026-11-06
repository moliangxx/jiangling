package com.example.jiangling.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.jiangling.ble.BleDevice
import com.example.jiangling.ble.BleManager
import com.example.jiangling.ble.ConnectionState
import com.example.jiangling.data.command.Command
import com.example.jiangling.data.command.CommandRepository
import com.example.jiangling.data.log.Log
import com.example.jiangling.data.log.LogRepository
import com.example.jiangling.util.M
import com.example.jiangling.util.U
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ======================================================================
 *  MainViewModel —— UI 的薄入口（连接操作 + 只读查询）
 * ======================================================================
 *
 *  【职责】
 *    1) 蓝牙连接操作：扫描 / 连接 / 断开（都经 BleManager 单例）；
 *    2) BLE 链路状态（connected / deviceName / mtu）与首次连接时间；
 *    3) 命令、日志的只读查询（页面筛选、排序、删除）；
 *    4) 把 [BleDataViewModel] 的遥测数据拼进唯一的 [status] 流，供页面直接展示。
 *
 *  【不负责】（已拆走）
 *    - 帧解析与分发 → BleManager.frames + BleDataViewModel
 *    - 实体组装、入库、ACK 匹配、去重、日志等级判定 → BleDataViewModel
 *    - 字节 ⇄ 帧编解码 → FrameCodec
 *
 *  【协作关系】
 *    MainActivity → MainViewModel → BleManager（发指令）
 *                          └→ BleDataViewModel（数据）/ Room 仓库（查询）
 *    BleDataViewModel 不引用本类，依赖方向单一，不会循环。
 *
 *  ======================= 状态流一览 =======================
 *  status        UI 唯一的小车状态：
 *                  链路字段（connected / deviceName / mtu / runStatus）← 本类，GATT 事件驱动
 *                  遥测字段（battery / speed / obstacleDistance…）← BleDataViewModel，DAT 帧驱动
 *  commands/logs 当前页面显示的查询结果（本类维护）
 *
 *  ======================= 初始化握手 =======================
 *  BleManager 在链路可用后自动发 CMD_SESSION_INIT（SEQ=0），
 *  收到对应 ACK（状态为「收到」或「已执行」）后 isConnect 置 true；
 *  ACK 的 resetDone 由 BleDataViewModel 用来决定是否清空本地序列状态。
 *
 *  ======================= 命令回复 =======================
 *  发出去的 CMD 只发一次：**不自动重发、不自动查序列号**。
 *  收到 ACK 由 BleDataViewModel 按 SEQ 回填；没收到就显示「未收到回复」，调试页手动处理。
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
    //  日志查询（默认起点由本类初始化给定，蓝牙首次连接时可覆盖一次）
    // ----------------------------------------------------------------------

    /**
     * 日志默认查询的时间起点。
     *
     * 赋值规则（严格，只有两个地方能写）：
     *  ① **ViewModel 初始化时**：默认 = 本次 App 启动时刻。
     *  ② **蓝牙首次主动连接成功时**：改成连接成功的那一刻（整个生命周期只改这一次）。
     *
     * 自动重连不算"主动连接"，所以永远不会覆盖它。
     */
    var firstConnectTime: Long = System.currentTimeMillis()
        private set

    /** 蓝牙是否已经用它自己的时间覆盖过默认值（保证"只改一次"） */
    private var firstConnectTimeSetByBle = false

    /** 用户是否正在手动连接（判断 onConnected 是"主动连接"还是"自动重连"） */
    private var manualConnecting = false

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

    /** 链路字段（connected / deviceName / mtu / runStatus）：只由 GATT 事件驱动 */
    private val _link = MutableStateFlow(CarStatus())

    /**
     * UI 看到的小车状态 = 链路字段（本类维护）+ 遥测字段（[BleDataViewModel] 的 DAT 帧）。
     *
     * 合并成一个流，页面只 collect 这一个，避免 UI 同时订阅两份 CarStatus；
     * 本类不再解析 DAT 帧，只是把数据层的遥测值拼进来。
     */
    val status: StateFlow<CarStatus> = combine(_link, data.carStatus) { link, telemetry ->
        telemetry.copy(
            connected = link.connected,
            deviceName = link.deviceName,
            mtu = link.mtu,
            runStatus = link.runStatus
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, CarStatus())

    /**
     * 设备静态信息（只读给 UI）：DAT_INFO 三个子帧合并后的缓存。
     * 未收到为 null；每次握手成功固件会重发一轮，App 不需要周期查询。
     */
    val deviceInfo: StateFlow<DeviceInfo?> get() = data.deviceInfo

    /** App 主动补拉静态信息（`CMD_REQ_INFO`）：重装/清缓存后拿不到 DAT_INFO 时手动调用 */
    fun requestDeviceInfo(): Boolean = sendCommand(M.CMD_REQ_INFO)

    /**
     * 蓝牙连接三态（UI 唯一状态源）。
     *
     * 映射关系（全部来自 [BleManager] 现有事件，不另造连接逻辑）：
     * - `onConnected`            → [ConnectionState.CONNECTED]
     * - `onDisconnected(true)`   → [ConnectionState.RECONNECTING]（意外掉线，自动重连中）
     * - `onDisconnected(false)`  → [ConnectionState.DISCONNECTED]（主动断开 / 重连耗尽）
     */
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /**
     * 「主动连接 / 主动断开」开关状态（两处页面共享同一个 StateFlow）。
     *
     * - 用户打开开关 / 从扫描弹窗选设备 → true
     * - 用户主动断开 / 连接失败 / 重连耗尽 → false
     * - 意外掉线进入重连中 → 保持 true（开关仍表示"想连"）
     * - 自动重连成功 → true（回填）
     */
    private val _connectionEnabled = MutableStateFlow(false)
    val connectionEnabled: StateFlow<Boolean> = _connectionEnabled.asStateFlow()

    /** 扫描到的设备列表 */
    private val _devices = MutableStateFlow<List<BleDevice>>(emptyList())
    val devices: StateFlow<List<BleDevice>> = _devices.asStateFlow()

    /** 是否正在扫描 */
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** 通用错误/提示消息（通过 U.s Snackbar + U.t Toast 弹出） */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    // ======================================================================
    //  BLE 单例 + Listener（UI 只关心连接类事件；帧不经过这里）
    // ======================================================================

    private val uiListener = object : BleManager.Listener {

        override fun onDeviceFound(device: BleDevice) {
            // 去重 + RSSI 排序（信号强的排前面）
            _devices.value = (_devices.value.filterNot { it.address == device.address } + device)
                .sortedByDescending { it.rssi }
        }

        override fun onScanStateChanged(scanning: Boolean) {
            _scanning.value = scanning
        }

        override fun onConnected(deviceName: String, mtu: Int) {
            _scanning.value = false
            _link.value = CarStatus(connected = true, deviceName = deviceName, mtu = mtu)
            // 三态 + 开关回填：自动重连成功时开关也要变回"开"
            _connectionState.value = ConnectionState.CONNECTED
            _connectionEnabled.value = true
            // ⭐ 全 App 唯一能改 firstConnectTime 的地方（除初始化那一次）。
            // 条件：用户主动点的连接 + 握手成功 + 还没被蓝牙改过。
            // 自动重连走 BleManager 内部，manualConnecting = false，所以永远不会覆盖。
            if (manualConnecting) {
                manualConnecting = false
                if (!firstConnectTimeSetByBle) {
                    firstConnectTimeSetByBle = true
                    firstConnectTime = System.currentTimeMillis()
                }
            }
        }

        override fun onDisconnected(isAutoReconnecting: Boolean) {
            _link.value = _link.value.copy(
                connected = false,
                runStatus = if (isAutoReconnecting) "自动重连中" else "已断开"
            )
            // 意外掉线 → 重连中（开关保持"开"）；主动断开/重连耗尽 → 未连接（开关关）
            _connectionState.value =
                if (isAutoReconnecting) ConnectionState.RECONNECTING else ConnectionState.DISCONNECTED
            _connectionEnabled.value = isAutoReconnecting
            // 手动连接过程中断线 → 清掉标记，避免下次自动重连被误判成主动连接
            manualConnecting = false
        }

        override fun onError(message: String) {
            // 这里只做界面提示，【不写日志】——日志只能由小车推送
            _message.value = message
            U.s(message)
        }
    }

    private val ble = BleManager.BLE.also { manager ->
        manager.addListener(uiListener)
        // 数据层的提示（握手失败等）接到本类的消息流
        data.onNotice = { text -> _message.value = text }
    }

    // ======================================================================
    //  蓝牙操作（调用 BleManager 的封装）
    // ======================================================================

    /** 开始扫描。权限检查在 BleManager 内部，ViewModel 不关心。 */
    fun startScan() {
        _devices.value = emptyList()
        ble.startScan()
    }

    fun stopScan() = ble.stopScan()

    /**
     * 手动连接（用户主动点击）。连上后 BleManager 自动发初始化握手。
     * manualConnecting = true 只影响 firstConnectTime 的覆盖规则。
     */
    fun connect(address: String) {
        stopScan()
        manualConnecting = true
        if (ble.connect(address)) {
            // 已发起连接：开关立即为"开"，后续以 BleManager 事件回填
            _connectionEnabled.value = true
        }
    }

    /** 主动断开——BleManager 会同时抑制自动重连 */
    fun disconnect() {
        _connectionEnabled.value = false
        _connectionState.value = ConnectionState.DISCONNECTED
        ble.disconnect()
    }

    /**
     * 「主动连接 / 主动断开」开关的统一入口（两处页面都调它）。
     *
     * 打开：有上次成功连接过的地址就直接连；没有就返回 false，由页面引导去扫描选设备。
     * 关闭：主动断开，同时抑制自动重连。
     *
     * @return true = 已发起连接（或已断开）；false = 没有可直连的设备，需要扫描
     */
    fun setConnectionEnabled(enabled: Boolean): Boolean {
        if (!enabled) {
            disconnect()
            return true
        }
        if (_connectionEnabled.value) return true
        val address = ble.lastAddress
        if (address == null) {
            _message.value = "请先扫描并选择小车"
            return false
        }
        manualConnecting = true
        if (!ble.connect(address)) {
            _connectionEnabled.value = false
            return false
        }
        _connectionEnabled.value = true
        return true
    }

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
        if (!status.value.connected) {
            _message.value = "蓝牙未连接"
            U.s("蓝牙未连接")
            return false
        }
        if (data.sendCommand(kindType, param) == null) {
            _message.value = "命令发送失败"
            U.s("命令发送失败")
            return false
        }
        return true
    }

    /** 消费掉 message（弹完 Toast 后调） */
    fun consumeMessage() { _message.value = null }

    /** Activity 销毁（ViewModel 被系统杀死）时调 */
    override fun onCleared() {
        data.dispose()
        ble.removeListener(uiListener)
        BleManager.BLE.clear()
        super.onCleared()
    }
}
