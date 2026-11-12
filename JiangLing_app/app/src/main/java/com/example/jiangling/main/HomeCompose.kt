package com.example.jiangling.main

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import com.example.jiangling.data.CarStatus
import com.example.jiangling.data.MainViewModel
import com.example.jiangling.main.control.GearBallPicker
import com.example.jiangling.main.control.GimbalJoystick
import com.example.jiangling.main.control.JoystickDrive
import com.example.jiangling.main.control.ThrottledSender
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.jiangling.main.control.VoiceCircleButton
import com.example.jiangling.main.control.rememberThrottledSender
import com.example.jiangling.util.M
import com.example.jiangling.util.U
import com.example.jiangling.voice.VoiceState
import kotlin.math.roundToInt

/*
 * ======================================================================
 *  首页（HomeScreen）——竖屏，自上而下：状态行 / 摄像头 / 速度·语音 / 双轮盘
 * ======================================================================
 *
 *  ┌───────────────────────────────────────────────┐
 *  │ 状态行（圆角矩形底，左右各 18dp）                │
 *  ├───────────────────────────────────────────────┤ ← 18dp 边距
 *  │  摄像头 200dp 高（左右各 18dp）          [入口] │
 *  │  (速度 44)                          (语音 44)  │ ← 浮层：画面左下 / 右下角
 *  ├───────────────────────────────────────────────┤
 *  │  ┌────────┐            ┌────────┐              │
 *  │  │ 方向轮盘 │            │ 云台轮盘 │              │ ← 120dp，同尺寸
 *  │  └────────┘            └────────┘              │
 *  └───────────────────────────────────────────────┘
 *
 *  - 速度按钮在摄像头左下角，点击**向右**弹挡位（弹层只占右侧，不挡轮盘）；
 *  - 语音按钮在摄像头右下角；
 *  - 两个轮盘下发复用详细操控页同一套链路（sendMoveVector / sendGimbalVector +
 *    200ms 节拍 + 松手补 0xE0 + 切后台停车），没有第二套发送逻辑。
 *
 *  标题栏在 MainCompose 的 SharedTopBar（显示「江灵」）。
 * ======================================================================
 */

/** 页面左右内容边距：摄像头、状态行、按钮行、轮盘行都用它，保证左右对齐 */
private val HOME_MARGIN = 18.dp

/** 摄像头区固定高度 */
private val HOME_CAMERA_HEIGHT = 200.dp

/** 状态行高度 */
private val HOME_STATUS_HEIGHT = 48.dp

/** 垂直间距（状态行 / 摄像头 / 按钮行之间） */
private val HOME_GAP = 10.dp

/** 首页速度 / 语音按钮直径 */
private val HOME_SPEED_SIZE = 44.dp
private val HOME_VOICE_SIZE = 44.dp

/** 首页两个轮盘直径（方向 / 云台同尺寸） */
private val HOME_WHEEL_SIZE = 120.dp

/** 状态行里"信号差"的红线 */
private const val SIGNAL_POOR_THRESHOLD = 30

/**
 * 首页根 Composable。
 *
 * @param onOpenDetail 进详细操控页（摄像头整块 + 右上角圆钮共用这一个回调）
 */
@Composable
fun HomeScreen(viewModel: MainViewModel, onOpenDetail: () -> Unit) {

    val status by viewModel.status.collectAsState()
    val message by viewModel.message.collectAsState()
    val voiceState by viewModel.voiceState.collectAsState()
    val voiceOn = voiceState is VoiceState.On

    val context = LocalContext.current
    LaunchedEffect(message) {
        message?.let {
            U.t(context, it)
            viewModel.consumeMessage()
        }
    }

    // 录音权限：点语音开关时才弹系统框
    val voicePermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startVoice() else viewModel.reportVoicePermissionDenied()
    }

    // 离开首页 / 切后台时关掉采集：避免麦克风在后台一直占用（Android 11+ 还会静音并亮隐私灯）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.stopVoice()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopVoice()
        }
    }

    var gear by rememberSaveable { mutableIntStateOf(M.SPEED_MAX) }
    var gearMenuOpen by remember { mutableStateOf(false) }

    val onToggleVoice: () -> Unit = {
        if (voiceOn) {
            viewModel.stopVoice()
        } else if (viewModel.hasVoicePermission()) {
            viewModel.startVoice()
        } else {
            voicePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ==================== 1. 状态行：标题栏下，圆角矩形底 ====================
        HomeStatusRow(
            status = status,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = HOME_MARGIN, end = HOME_MARGIN, top = HOME_GAP)
                .height(HOME_STATUS_HEIGHT)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .padding(horizontal = 12.dp)
        )

        // ==================== 2. 摄像头 200dp（左右 18dp）+ 左下速度 / 右下语音浮层 ====================
        CameraBlock(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = HOME_MARGIN, end = HOME_MARGIN, top = HOME_GAP)
                .height(HOME_CAMERA_HEIGHT),
            onOpenDetail = onOpenDetail,
            speed = {
                GearBallPicker(
                    gear = gear,
                    expanded = gearMenuOpen,
                    onExpandedChange = { gearMenuOpen = it },
                    onSelect = {
                        gear = it
                        gearMenuOpen = false
                    },
                    buttonSize = HOME_SPEED_SIZE,
                    // 向右弹出：底边与按钮齐平、往上排，弹层不越出摄像头区、不挡下方轮盘
                    expandRight = true
                )
            },
            voice = {
                VoiceCircleButton(
                    on = voiceOn,
                    size = HOME_VOICE_SIZE,
                    onClick = onToggleVoice
                )
            }
        )

        // ==================== 3. 方向轮盘 + 云台轮盘（并排、同尺寸）====================
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = HOME_MARGIN, end = HOME_MARGIN, top = HOME_GAP)
                .weight(1f),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            WheelUnit(caption = "方向") {
                JoystickDrive(
                    size = HOME_WHEEL_SIZE,
                    gear = gear,
                    viewModel = viewModel
                )
            }
            WheelUnit(caption = "云台") {
                GimbalJoystick(
                    size = HOME_WHEEL_SIZE,
                    onVector = { pan, tilt ->
                        // 0xEC：pan 正 = 右 / tilt 正 = 上，与移动帧独立
                        if (!viewModel.sendGimbalVector(pan, tilt)) viewModel.notifyCommandDropped()
                    },
                    onStop = {
                        // 协议没有云台停止字段：停发即停转，300ms 无新帧固件自停且不回中
                    }
                )
            }
        }
    }
}

/** 轮盘 + 下方小标题（两个轮盘看起来一样，需要文字区分方向 / 云台） */
@Composable
private fun WheelUnit(caption: String, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        content()
        Spacer(Modifier.height(6.dp))
        Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ======================================================================
//  状态行（48dp）
// ======================================================================

/**
 * 标题栏与摄像头之间的状态行。
 *
 * 左：WiFi（图标 + 百分比），靠左；
 * 右：速度 `x.x m/s`、电量（图标 + 百分比）。
 *
 * 更新来源（无数据时显示「—」占位，不写死灰字）：
 * - WiFi 百分比：由 DAT_WIFI_STATUS 的 RSSI（dBm）换算 → `status.wifiRssi`
 * - 电量：DAT_BAT 电压换算出的百分比 → `status.battery`
 * - 速度：DAT_WHEEL 双轮转速换算 → `status.speed`
 */
@Composable
private fun HomeStatusRow(status: CarStatus, modifier: Modifier = Modifier) {
    val wifiPercent: Int? = status.wifiRssi?.let { rssiToPercent(it) }
    val batteryPercent: Double? = status.battery.takeIf { it > 0.0 }
    val speed: Double? = status.speed.takeIf { status.connected }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ---- 左：WiFi ----
        SignalItem(
            icon = Icons.Filled.Wifi,
            percent = wifiPercent,
            description = "WiFi 信号"
        )

        Spacer(Modifier.weight(1f))

        // ---- 右：速度 + 电量 ----
        Text(
            text = speed?.let { "%.1f m/s".format(it) } ?: "—",
            style = MaterialTheme.typography.labelLarge,
            color = if (speed == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
        Spacer(Modifier.size(10.dp))
        BatteryItem(percent = batteryPercent)
    }
}

/** WiFi 信号一项：图标 + 百分比。0 或无数值灰，良好白，差红 */
@Composable
private fun SignalItem(icon: ImageVector, percent: Int?, description: String) {
    val color = when {
        percent == null || percent <= 0 -> MaterialTheme.colorScheme.onSurfaceVariant
        percent < SIGNAL_POOR_THRESHOLD -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = color,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = if (percent == null || percent <= 0) "—" else "$percent%",
            style = MaterialTheme.typography.labelLarge,
            color = color
        )
    }
}

/** 电量一项：<15% 红、15~20% 橙、20~50% 蓝、其余白 */
@Composable
private fun BatteryItem(percent: Double?) {
    val color = when {
        percent == null -> MaterialTheme.colorScheme.onSurfaceVariant
        percent < 15.0 -> MaterialTheme.colorScheme.error
        percent < 20.0 -> Color(0xFFFF9800)
        percent < 50.0 -> Color(0xFF2196F3)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.BatteryFull,
            contentDescription = "电量",
            tint = color,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = percent?.let { "${it.roundToInt()}%" } ?: "—",
            style = MaterialTheme.typography.labelLarge,
            color = color
        )
    }
}

/** dBm → 0~100 百分比：-100dBm 及以下算 0，-40dBm 及以上算 100 */
private fun rssiToPercent(rssi: Int): Int =
    ((rssi + 100) * 100 / 60).coerceIn(0, 100)

// ======================================================================
//  摄像头区
// ======================================================================

/**
 * 视频画面占位区（摄像头 200dp）。
 *
 * - 整块可点进详细操控页；
 * - 右上角圆形入口（同一个回调）；
 * - 速度按钮浮在**左下角**、语音按钮浮在**右下角**，与摄像头区左右边缘对齐；
 *   两者外面各套一层无涟漪的空 clickable，按下事件在这里就被吃掉，
 *   不会穿透到整块的 [onOpenDetail]。
 *
 * @param speed 左下角速度按钮（挡位弹层向右弹）
 * @param voice 右下角语音按钮
 */
@Composable
private fun CameraBlock(
    modifier: Modifier,
    onOpenDetail: () -> Unit,
    speed: @Composable () -> Unit,
    voice: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1C1C1E))
            // 不画涟漪，保持视频画面干净
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onOpenDetail
            )
    ) {
        DetailEntryButton(
            onClick = onOpenDetail,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
        )

        // ---- 左下：速度 ----
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { /* 速度按钮区：只吃触摸，不跳详细操控页 */ }
        ) { speed() }

        // ---- 右下：语音 ----
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { /* 语音按钮区：只吃触摸，不跳详细操控页 */ }
        ) { voice() }
    }
}

/**
 * 进入详细操控页的圆形入口（36dp）——可复用控件。
 * 只负责回调，跳转逻辑由调用方集中处理。
 */
@Composable
fun DetailEntryButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.16f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Tune,
            contentDescription = "进入详细操控页",
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
    }
}

// 首页的操控已全部由「速度 + 语音 + 方向/云台双轮盘」承担，
// 发送链路仍是详细操控页那一套（JoystickDrive → ThrottledSender → sendMoveVector）。

