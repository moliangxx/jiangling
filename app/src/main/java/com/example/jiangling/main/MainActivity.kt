package com.example.jiangling.main

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.example.jiangling.ble.BleManager
import com.example.jiangling.data.MainViewModel
import com.example.jiangling.ui.JiangLingTheme
import com.example.jiangling.util.U

/**
 * 唯一的 Activity（纯 Compose + 全局权限职责）。
 *
 * ========================= 职责 =========================
 * 因为项目规模小、需要的权限也就蓝牙那几个，所以这里**统一承担全部权限申请**：
 *   1. 申请蓝牙运行时权限（Android 12+ 是 SCAN + CONNECT，12 以下是定位权限）；
 *   2. 申请打开蓝牙；
 *   3. 权限/开关都就绪后，才让 BleManager 干活。
 *
 * BleManager 内部**不再有任何权限代码**，它只在使用时 try/catch SecurityException。
 *
 * ========================= 启动流程 =========================
 * onCreate
 *   → BleManager.init(this)        初始化 BLE 单例
 *   → checkBleReady()              检查权限 → 没权限就弹权限框
 *   → setContent { JiangLingApp }   挂 UI
 *
 * 用户的每次选择都会回到下面两个 Launcher 回调里，再由我们决定下一步：
 *   权限框结果：同意 → 继续检查/请求打开蓝牙；拒绝 → Toast 提示
 *   蓝牙框结果：已开 → 通知 BleManager 继续之前挂起的重连；没开 → Toast 提示
 */
class MainActivity : ComponentActivity() {

    /** 全局唯一的 ViewModel，Activity 重建时不会丢数据 */
    private val viewModel: MainViewModel by viewModels()

    // ---------------- 权限申请：多权限一次性请求 ----------------
    /**
     * Android 12+ 需要 BLUETOOTH_SCAN + BLUETOOTH_CONNECT，
     * 12 以下（老版本）扫描蓝牙必须用 ACCESS_FINE_LOCATION。
     * 见下方 requiredBlePermissions()。
     */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        if (allGranted) {
            // 权限有了，继续确认蓝牙开关
            requestEnableBluetooth()
        } else {
            U.t(this, "蓝牙权限被拒绝，无法搜索/连接小车")
        }
    }

    // ---------------- 蓝牙开关：系统弹窗 ----------------
    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (isBluetoothEnabled()) {
            // 蓝牙开好了 → 通知 BleManager 继续之前被挂起的自动重连
            BleManager.BLE.onBluetoothAvailable()
        } else {
            U.t(this, "蓝牙未开启，无法连接小车")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 初始化 BLE 全局单例（只建对象，不涉及权限）
        BleManager.init(this)

        // 2. 检查权限 → 没权限就弹框；有权限再检查蓝牙开关
        checkBleReady()

        // 3. 挂 UI
        setContent {
            JiangLingTheme {
                JiangLingApp(viewModel = viewModel)
            }
        }
    }

    /**
     * 权限 + 蓝牙开关的统一入口。
     * 先看权限，权限齐了再看蓝牙开关。
     */
    private fun checkBleReady() {
        if (!hasBlePermissions()) {
            permissionLauncher.launch(requiredBlePermissions())
        } else {
            requestEnableBluetooth()
        }
    }

    /** 蓝牙没开就弹系统对话框请求打开；已经开了就直接返回 */
    private fun requestEnableBluetooth() {
        if (isBluetoothEnabled()) return
        try {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (e: SecurityException) {
            // 没权限时系统会抛这个异常（理论上前面已经申请过了，这里只是兜底）
            U.t(this, "无法打开蓝牙：缺少权限")
        }
    }

    /** 当前需要的蓝牙权限是否全部授予 */
    private fun hasBlePermissions(): Boolean =
        requiredBlePermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    /** 蓝牙开关当前是否打开（读系统状态，不弹窗） */
    private fun isBluetoothEnabled(): Boolean = try {
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled == true
    } catch (e: SecurityException) {
        false
    }

    override fun onDestroy() {
        // 真正退出（不是旋转屏幕）时才释放 BLE 资源
        if (isFinishing) BleManager.BLE.destroy()
        super.onDestroy()
    }

    companion object {
        /**
         * 本项目需要的全部蓝牙权限。
         *
         * - Android 12（S）及以上：BLUETOOTH_SCAN + BLUETOOTH_CONNECT
         * - Android 12 以下：ACCESS_FINE_LOCATION（老版本扫描蓝牙必须带定位权限）
         *
         * 权限清单也要在 AndroidManifest.xml 里声明，否则这里申请不到。
         */
        fun requiredBlePermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
    }
}
