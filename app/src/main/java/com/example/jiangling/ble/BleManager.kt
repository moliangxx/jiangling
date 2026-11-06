package com.example.jiangling.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.jiangling.util.M
import com.example.jiangling.util.U
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.min
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 扫描到的设备 */
data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int
)

/**
 * 蓝牙连接三态（UI 展示用）。
 *
 * 状态全部由 [BleManager] 现有事件推导，不额外维护连接逻辑：
 * - [DISCONNECTED]：从未连接 / 用户主动断开 / 自动重连耗尽
 * - [CONNECTED]：链路就绪（收到 onConnected）
 * - [RECONNECTING]：意外掉线，正在自动重连
 */
enum class ConnectionState(val label: String) {
    DISCONNECTED("未连接"),
    CONNECTED("已连接"),
    RECONNECTING("重连中")
}

/**
 * ======================================================================
 *  BleManager —— 连接生命周期管理器（只管"链路"，不管"报文内容"）
 * ======================================================================
 *
 *  【和 FrameCodec 的分工】
 *    本类：初始化 / 扫描 / 连接 / 断开 / 自动重连 / 写特征值 / MTU / CCCD / 握手判定
 *    FrameCodec：收到字节流 → 拆成 Frame；Frame → 编成字节流（纯工具，无状态无数据库）
 *
 *  也就是说：
 *    - 收到通知 → 只把字节丢给 codec.receive()，这里不解析报文内容
 *    - 要发数据 → 先 codec.encode() 编出字节，这里只负责 writeCharacteristic
 *
 *  ========================= 对外暴露 =========================
 *  - 连接类事件：Listener（可注册多个，全部回调在主线程）
 *  - 解析出的帧：frames（SharedFlow），只由 BleDataViewModel 收集
 *  - 发送：sendCmd / sendAck / sendSessionInit
 *  本类**不依赖任何 ViewModel / 数据库**，换 UI 或换存储都不影响这里。
 *
 *  ========================= 权限 =========================
 *  本类不申请权限。"蓝牙权限 + 打开蓝牙"全由 MainActivity 负责。
 *  这里只在使用时 try/catch SecurityException。
 *
 *  ========================= isConnect 状态机 =========================
 *  - App 启动：false（从未连上过）
 *  - 首次握手成功（收到 ACK[SEQ=0] 且状态为"收到/已执行"）：true
 *  - 意外断开 + true → 自动重连上次地址
 *  - 主动 disconnect() → 不自动重连
 *
 *  ========================= 外部调用 =========================
 *    BleManager.BLE.startScan() / connect(addr) / disconnect()
 *    BleManager.BLE.sendCmd(kindType, payload) / sendAckResult(seq, cmdType, status)
 *    BleManager.BLE.addListener(listener) / removeListener(listener)
 *    BleManager.BLE.frames.collect { frame -> ... }
 */
@SuppressLint("MissingPermission")
class BleManager private constructor(private val appContext: Context) {

    // ==================== Listener（只发"连接类"事件）====================

    interface Listener {
        fun onDeviceFound(device: BleDevice) {}
        fun onScanStateChanged(scanning: Boolean) {}
        fun onConnected(deviceName: String, mtu: Int) {}
        fun onDisconnected(isAutoReconnecting: Boolean) {}
        fun onError(message: String) {}
    }

    /** 允许多个观察者（UI 层用一个，数据层用一个），全部回调在主线程 */
    private val listeners = CopyOnWriteArrayList<Listener>()

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /**
     * 解析完成的帧流。
     * 只有 BleDataViewModel 收集它；BleManager 自己不解析、不落库。
     */
    private val _frames = MutableSharedFlow<Frame>(extraBufferCapacity = 64)
    val frames: SharedFlow<Frame> = _frames.asSharedFlow()

    // ==================== 内部状态 ====================

    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val mainHandler = Handler(Looper.getMainLooper())

    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private var gatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    private var currentMtu = M.BLE_DEFAULT_MTU
    private var cmdSeqCounter: Byte = 0

    /**
     * 帧编解码器：字节流 ⇄ Frame 的唯一入口。
     * onFrame 在 BLE 回调线程上同步回调 → [handleFrame] 只做握手判定后投递到 [frames]。
     */
    private val codec = FrameCodec(
        onFrame = { frame -> handleFrame(frame) },
        onError = { message -> post { onError(message) } }
    )

    /**
     * 是否已连接（握手成功后才为 true）。
     * 外部只读；@Volatile 保证 BLE 回调线程写、主线程读时的可见性。
     */
    @Volatile
    var isConnect: Boolean = false
        private set

    /** 最近一次连接的地址（自动重连 / 开关主动连接用） */
    private var lastConnectedAddress: String? = null

    /** 最近一次成功连上的地址（只读；没连过为 null） */
    val lastAddress: String? get() = lastConnectedAddress

    /** 蓝牙关着时挂起的重连地址，等蓝牙打开后由 MainActivity 触发继续 */
    private var pendingReconnectAddress: String? = null

    /** 用户主动断开：true 时抑制一切自动重连（区分"主动断开"与"意外掉线"） */
    @Volatile
    private var userInitiatedDisconnect = false

    /** 本次意外掉线已经尝试过的重连次数（连上后归零） */
    @Volatile
    private var reconnectAttempts = 0

    /** 已排进消息队列的延迟重连任务；主动断开时要先取消它 */
    private var reconnectRunnable: Runnable? = null

    private val gattActive: Boolean get() = gatt != null && rxCharacteristic != null

    private fun isBluetoothEnabled(): Boolean = try {
        adapter?.isEnabled == true
    } catch (e: SecurityException) { false }

    /**
     * 蓝牙被打开后由 MainActivity 调用：继续之前挂起的自动重连。
     * 不负责申请权限、不负责开蓝牙。
     */
    fun onBluetoothAvailable() {
        if (userInitiatedDisconnect) return
        val addr = pendingReconnectAddress ?: return
        pendingReconnectAddress = null
        connect(addr, isAutoReconnect = true)
    }

    // ======================================== 扫描 ===================================================

    fun startScan() {
        if (scanning) return
        val btAdapter = adapter ?: return post { onError("当前设备不支持蓝牙") }
        if (!btAdapter.isEnabled) return post { onError("请先开启蓝牙") }
        val leScanner = btAdapter.bluetoothLeScanner
            ?: return post { onError("蓝牙扫描不可用") }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            leScanner.startScan(null, settings, scanCallback)
            scanner = leScanner
            scanning = true
            post { onScanStateChanged(true) }
        } catch (e: SecurityException) {
            post { onError("缺少蓝牙扫描权限") }
        }
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        scanner = null
        post { onScanStateChanged(false) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName
                ?: try { result.device.name } catch (_: SecurityException) { null }
            post {
                onDeviceFound(
                    BleDevice(
                        name = name?.takeIf { it.isNotBlank() } ?: "未命名设备",
                        address = result.device.address,
                        rssi = result.rssi
                    )
                )
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false; scanner = null
            post { onScanStateChanged(false); onError("蓝牙扫描失败: $errorCode") }
        }
    }

    // ========================================== 连接 =================================================

    /**
     * 手动连接（用户点"连接" / 开关打开）。
     * 成功握手后 isConnect 置 true，之后意外断开会自动重连。
     *
     * @return true = 已发起连接；false = 没发起（蓝牙未开 / 地址无效 / 无权限）
     */
    fun connect(address: String): Boolean = connect(address, isAutoReconnect = false)

    private fun connect(address: String, isAutoReconnect: Boolean): Boolean {
        stopScan()
        userInitiatedDisconnect = false
        if (!isAutoReconnect) reconnectAttempts = 0
        if (!isBluetoothEnabled()) {
            // 蓝牙关着：先记下地址，等 MainActivity 打开蓝牙后调 onBluetoothAvailable()
            pendingReconnectAddress = address
            post { onError("蓝牙未开启") }
            return false
        }
        val btAdapter = adapter
        if (btAdapter == null) {
            post { onError("当前设备不支持蓝牙") }
            return false
        }
        val device: BluetoothDevice = try { btAdapter.getRemoteDevice(address) }
        catch (e: Exception) {
            post { onError("无效的蓝牙地址") }
            return false
        }

        closeGatt()
        return try {
            gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            true
        } catch (e: SecurityException) {
            post { onError("缺少蓝牙连接权限") }
            false
        }
    }

    /**
     * 主动断开——**抑制自动重连**，isConnect 不变。
     *
     * 两件事必须同时做，否则开关刚关掉、后台又把设备连回来：
     * 1) 取消已经排队的延迟重连任务；
     * 2) 置用户主动断开标记，之后任何掉线回调都不再走自动重连。
     */
    fun disconnect() {
        userInitiatedDisconnect = true
        cancelScheduledReconnect()
        closeGatt()
        post { onDisconnected(isAutoReconnecting = false) }
    }

    /** 取消延迟重连任务并清掉挂起地址（主动断开时用） */
    private fun cancelScheduledReconnect() {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
        pendingReconnectAddress = null
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                try { gatt.discoverServices() }
                catch (e: SecurityException) { post { onError("发现蓝牙服务失败") } }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val wasConnected = this@BleManager.isConnect && this@BleManager.gatt === gatt
                val hadActiveGatt = this@BleManager.gatt === gatt
                if (hadActiveGatt) { this@BleManager.gatt = null; clearCharacteristics() }
                try { gatt.close() } catch (_: Exception) {}

                // 用户主动断开 → 绝不自动重连（区分"主动断开"与"意外掉线"）
                if (userInitiatedDisconnect) {
                    post { onDisconnected(isAutoReconnecting = false) }
                } else if (wasConnected && lastConnectedAddress != null) {
                    // 曾经连上过 → 属于意外断开，走自动重连
                    post { onDisconnected(isAutoReconnecting = true) }
                    autoReconnect(lastConnectedAddress!!)
                } else {
                    post { onDisconnected(isAutoReconnecting = false) }
                }
            }
        }

        /**
         * 延迟 1.5s 再连，避免系统还没释放上一个 GATT。
         *
         * 有界重试：最多 [RECONNECT_MAX_ATTEMPTS] 次；耗尽后回到"未连接"，不再让 UI 一直转"重连中"。
         * 每次延迟任务都存进 [reconnectRunnable]，用户主动断开时能取消掉。
         */
        private fun autoReconnect(address: String) {
            if (userInitiatedDisconnect) return
            pendingReconnectAddress = address
            if (!isBluetoothEnabled()) {
                post { onError("蓝牙未开启，自动重连已挂起") }
                return
            }
            if (reconnectAttempts >= RECONNECT_MAX_ATTEMPTS) {
                reconnectAttempts = 0
                post { onDisconnected(isAutoReconnecting = false) }
                return
            }
            reconnectAttempts++
            val task = Runnable {
                reconnectRunnable = null
                if (userInitiatedDisconnect) return@Runnable
                if (isBluetoothEnabled()) connect(address, isAutoReconnect = true)
            }
            reconnectRunnable = task
            mainHandler.postDelayed(task, RECONNECT_DELAY_MS)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS)
                return post { onError("蓝牙服务发现失败: $status") }
            val service = gatt.getService(M.BLE_SERVICE_UUID)
            val rx = service?.getCharacteristic(M.BLE_RX_UUID)
            val tx = service?.getCharacteristic(M.BLE_TX_UUID)
            if (service == null || rx == null || tx == null) {
                post { onError("未找到 NUS 蓝牙服务") }
                try { gatt.disconnect() } catch (_: Exception) {}
                return
            }
            rxCharacteristic = rx
            txCharacteristic = tx
            codec.reset()

            try {
                gatt.setCharacteristicNotification(tx, true)
                val cccd = tx.getDescriptor(M.BLE_CCCD_UUID)
                if (cccd != null) {
                    val value = if (tx.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    gatt.writeDescriptor(cccd, value)
                } else {
                    // 没有 CCCD 就直接进入"链路就绪"
                    requestMtu(gatt)
                    post { onConnectedReady(deviceNameOf(gatt), currentMtu) }
                }
            } catch (e: SecurityException) { post { onError("订阅蓝牙通知失败") } }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != M.BLE_CCCD_UUID) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                requestMtu(gatt)
                post { onConnectedReady(deviceNameOf(gatt), currentMtu) }
            } else {
                post { onError("订阅蓝牙通知失败: $status") }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) currentMtu = mtu
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
        ) {
            if (characteristic.uuid != M.BLE_TX_UUID) return
            codec.receive(value)  // 解析交给 FrameCodec
        }
    }

    /**
     * 链路可用：通知上层，然后立刻发 CMD_SESSION_INIT 握手。
     * 握手成功的判定在 [handleFrame] 里（收到 SEQ=0 的 ACK，状态为「收到」或「已执行」）。
     */
    private fun onConnectedReady(deviceName: String, mtu: Int) {
        lastConnectedAddress = gatt?.device?.address ?: lastConnectedAddress
        reconnectAttempts = 0                 // 连上即重置重试计数
        reconnectRunnable = null
        post { onConnected(deviceName, mtu) }
        sendSessionInit()
    }

    // ======================================== 报文：收 =============================================

    /**
     * FrameCodec 解析出一条报文后的入口（运行在 BLE 回调线程）。
     *
     * 这里只做一件"连接相关"的事：识别握手成功 → isConnect = true。
     * 报文内容一律不处理，直接投递给 [frames]（BleDataViewModel 消费）。
     */
    private fun handleFrame(frame: Frame) {
        if (frame is Frame.Ack &&
            frame.seq == M.SEQ_INIT &&
            frame.kindType == M.ACK_SESSION_INIT &&
            (U.u8(frame.payload, 0) == M.ACK_ST_RECEIVED || U.u8(frame.payload, 0) == M.ACK_ST_DONE)
        ) {
            isConnect = true
        }
        _frames.tryEmit(frame)
    }

    // ======================================== 报文：发 =============================================

    /**
     * 发普通 CMD，返回本次实际使用的 SEQ（失败返回 null）。
     * 上层把 SEQ 存进命令记录，用于 ACK 回来时按 SEQ 匹配。
     * 本类不重发、不查询：命令只发一次，失败由上层提示后手动处理。
     */
    fun sendCmd(kindType: Int, payload: ByteArray = ByteArray(0), seq: Int = -1): Byte? {
        val s = if (seq >= 0) seq.toByte() else nextCmdSeq()
        return if (writeFrame(M.KIND_CMD, kindType, s, payload)) s else null
    }

    /**
     * 发初始化握手命令（CMD_SESSION_INIT, SEQ=0）。
     * PAYLOAD 只有 protocolVersion 1 Byte。
     */
    fun sendSessionInit(): Boolean =
        writeFrame(M.KIND_CMD, M.CMD_SESSION_INIT, M.SEQ_INIT, byteArrayOf(M.PROTOCOL_VERSION))

    /**
     * 回普通结果 ACK（固件定稿格式 `ACK_RESULT`）。
     *
     * @param seq     必须和收到的那条 CMD 的 SEQ 一致
     * @param cmdType 被应答 CMD 的**完整 TYPE 字节**（例如 `0xE0`），放进 PAYLOAD[0]
     * @param status  回复状态（ACK_ST_*），放进 PAYLOAD[1]
     */
    fun sendAckResult(seq: Int, cmdType: Byte, status: Byte): Boolean =
        writeFrame(M.KIND_ACK, M.ACK_RESULT, seq.toByte(), byteArrayOf(cmdType, status))

    /**
     * 编码（codec.encode）+ 真正写特征值。
     * 这里只管"能不能发出去"，不关心报文内容。
     */
    private fun writeFrame(kind: Int, kindType: Int, seq: Byte, payload: ByteArray): Boolean {
        val g = gatt ?: return false.also { post { onError("蓝牙未连接") } }
        val characteristic = rxCharacteristic ?: return false

        // 当前 MTU 允许的单包上限（MTU-3，至少 20）。
        // V1.10 没有 PAYLOAD_MAX：实际长度由 TYPE 查表决定，这里只兜住蓝牙单包物理上限。
        val maxPayload = (currentMtu - 3).coerceAtLeast(20)
        if (payload.size > maxPayload) {
            post { onError("消息过长（${payload.size} 字节），上限 $maxPayload") }
            return false
        }

        val frame = codec.encode(kind, kindType, seq, payload)
            ?: return false.also { post { onError("TYPE 未登记或 PAYLOAD 长度与长度表不符") } }

        val writeType = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

        return try {
            val status = g.writeCharacteristic(characteristic, frame, writeType)
            status == BluetoothStatusCodes.SUCCESS
        } catch (e: SecurityException) {
            post { onError("缺少蓝牙连接权限") }; false
        } catch (e: Exception) {
            post { onError("发送失败: ${e.message ?: "未知"}") }; false
        }
    }

    /** SEQ 循环 1..255（0 留给初始化握手） */
    private fun nextCmdSeq(): Byte {
        cmdSeqCounter = if (cmdSeqCounter >= 255) 1 else (cmdSeqCounter + 1).toByte()
        return cmdSeqCounter
    }

    // ==================== 内部工具 ====================

    private fun deviceNameOf(gatt: BluetoothGatt): String = try {
        gatt.device.name?.takeIf { it.isNotBlank() } ?: gatt.device.address
    } catch (_: SecurityException) { gatt.device.address }

    private fun requestMtu(gatt: BluetoothGatt) {
        try { gatt.requestMtu(M.BLE_MTU) } catch (_: SecurityException) {}
    }

    private fun clearCharacteristics() {
        rxCharacteristic = null; txCharacteristic = null
        codec.reset()
        currentMtu = M.BLE_DEFAULT_MTU
    }

    private fun closeGatt() {
        clearCharacteristics()
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
    }

    /** 所有 Listener 回调都切到主线程，上层可以放心改状态 */
    private fun post(block: Listener.() -> Unit) {
        mainHandler.post { listeners.forEach { it.block() } }
    }

    /** 页面/ViewModel 销毁时调：停扫描、释放 GATT。不重置 isConnect（下次还能自动重连） */
    fun clear() {
        stopScan()
        closeGatt()
        listeners.clear()
    }

    /** 整个 App 彻底退出时调：连 isConnect 一起重置 */
    fun destroy() {
        clear()
        isConnect = false
        lastConnectedAddress = null
        pendingReconnectAddress = null
        reconnectRunnable = null
        reconnectAttempts = 0
        userInitiatedDisconnect = false
    }

    // ==================== Companion：全局单例 ====================

    companion object {
        /**
         * 单例实例：**@Volatile + 双重检查锁（DCL）**
         * - @Volatile 保证跨线程可见
         * - 第一次 if 不加锁，性能好
         * - synchronized 里再判断一次，防止两个线程同时通过第一次判断
         */
        @Volatile
        private var _instance: BleManager? = null

        private const val RECONNECT_DELAY_MS = 1500L

        /** 一次意外掉线最多自动重连几次；耗尽后回到"未连接" */
        private const val RECONNECT_MAX_ATTEMPTS = 3

        val BLE: BleManager get() = _instance
            ?: error("BLE 未初始化——请在 Activity.onCreate 里调 BleManager.init(context)")

        /** 初始化单例，重复调用安全。权限不在这里处理（MainActivity 负责） */
        fun init(context: Context) {
            if (_instance == null) {
                synchronized(this) {
                    if (_instance == null) {
                        _instance = BleManager(context.applicationContext)
                    }
                }
            }
        }
    }
}
