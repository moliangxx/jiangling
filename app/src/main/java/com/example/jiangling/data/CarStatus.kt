package com.example.jiangling.data

/**
 * 小车实时状态——进程态，**不持久化**。
 *
 * 【数据从哪来】
 * BleManager 收到板子发来的 DAT 帧后，由 BleDataViewModel.handleDatFrame() 解析 PAYLOAD，
 * 把数值写进这里。每个字段对应一种 DAT KIND_TYPE（见 M.kt 的 DAT_* 常量）。
 *
 * 【什么时候停止更新】
 * 蓝牙断开后 BleManager 不会再收到 DAT 帧 → 这个对象的字段就不再变化，
 * 页面上显示的数值会停留在"最后一次收到的值"。
 *
 * 【什么时候清零】
 * App 被杀进程或重新启动 → 对象重新创建 → 全部回到默认值。
 *
 * 【为什么用不可变 data class】
 * Compose 的 StateFlow 推荐用不可变对象：每次要更新就 copy() 一份新的，
 * 这样 Compose 能精确知道"哪部分变了"，只重组受影响的 UI。
 * 如果你习惯用 var 字段直接改值，也可以改回 mutable，但那样页面不会自动刷新，
 * 需要手动 trigger StateFlow 的 value 变更。
 *
 * 【新增字段怎么做】
 * 1) 在 M.kt 里加对应的 DAT_* KIND_TYPE 常量（如果协议里有）；
 * 2) 在这个 data class 里加字段，给个合理的默认值；
 * 3) 在 BleDataViewModel.handleDatFrame() 的 kindType when 分支里解析 PAYLOAD 并 copy()；
 * 4) 在对应的 Screen 里 collectAsState() 这个字段来显示。
 */
data class CarStatus(

    // ======= 连接相关（不是 DAT 帧，来自 GATT 连接状态）=======
    /** 蓝牙连接状态，true = 已连上（不一定握手成功） */
    val connected: Boolean = false,

    /** 板子名字，从 BLE GATT 读出来的 */
    val deviceName: String? = null,

    /** 当前协商好的 MTU，决定单帧最大 payload */
    val mtu: Int = 23,

    // ======= 运动数据（对应 M.DAT_WHEEL = 2）=======
    /** 左轮转速（r/s，允许负数表示反转） */
    val wheelLeftRps: Double = 0.0,

    /** 右轮转速（r/s，允许负数表示反转） */
    val wheelRightRps: Double = 0.0,

    /** 线速度（m/s）：由左右轮转速平均后按 v = r/s × π × 0.068 换算，只用于显示 */
    val speed: Double = 0.0,

    /** 运行状态描述，比如 "正常" / "急停触发" / "低电量" */
    val runStatus: String = "未连接",

    // ======= 电池数据（对应 M.DAT_BAT = 0）=======
    /** 电量百分比 0.0~100.0（**App 计算**）：小车只上报 [batteryVoltage]，本值由 `M.batteryPercentFromVoltage()` 换算；未收到 DAT 前为 0.0（界面显示 "—"） */
    val battery: Double = 0.0,

    /** 电池电压（V），分辨率 0.01V，来自 DAT_BAT 原始值；未收到 DAT 前为 0.0 */
    val batteryVoltage: Double = 0.0,

    // ======= 内存与运行状态（对应 M.DAT_MEM = 11，低频上报）=======
    /** ESP32 内部 RAM 空闲（KB）；未收到为 null */
    val ramFreeKb: Int? = null,

    /** PSRAM 空闲（KB）；无 PSRAM 或未收到为 null（不混用内部 RAM 的值） */
    val psramFreeKb: Int? = null,

    /** 内部 RAM 历史最低空闲（KB） */
    val ramMinKb: Int? = null,

    /** PSRAM 历史最低空闲（KB） */
    val psramMinKb: Int? = null,

    /** 运行时长（秒） */
    val uptimeSeconds: Long? = null,

    /** 日志队列水位（条） */
    val logQueue: Int? = null,

    /** DAT 队列水位（条） */
    val datQueue: Int? = null,

    /** 日志丢弃累计条数 */
    val logDropped: Int? = null,

    // ======= 预留：以后板子加了对应的 DAT 帧再启用 =======
    /** 超声波测距（单位 cm，对应 M.DAT_US = 1） */
    val obstacleDistance: Int? = null,

    /** IMU 姿态角（对应预留的 DAT_IMU = 3，板子没发之前为 null） */
    val imuPitch: Double? = null,
    val imuRoll: Double? = null,
    val imuYaw: Double? = null
)
