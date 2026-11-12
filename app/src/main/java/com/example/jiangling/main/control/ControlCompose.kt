package com.example.jiangling.main.control

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.jiangling.data.MainViewModel
import com.example.jiangling.util.M
import com.example.jiangling.util.U
import com.example.jiangling.voice.VoiceState
import kotlin.math.roundToInt

/*
 * ======================================================================
 *  详细操控页（横屏全屏）——首页的二级页面
 * ======================================================================
 *
 *  ┌───────────────────────────────────────────────────────────┐
 *  │                                        [← 返回 40]         │ ← 距顶 38 / 距右 30
 *  │                                                           │
 *  │                                                           │
 *  │  [方向轮盘 108]                        [云台轮盘 108]      │ ← 同尺寸、同距底 38
 *  │       ↕ 10                                                │
 *  │  [速度 38]                                                │
 *  │       ↕ 8                                                 │
 *  │  [语音 38]                                                │
 *  └───────────────────────────────────────────────────────────┘
 *    距左 48                                    距右 48
 *
 *  根布局没有任何内边距，视频铺满整屏；控件是透明白浮层，只避让系统栏 / 挖孔。
 *  两处「距右 48dp」：右侧云台按字面执行；左侧方向轮盘按字面对称会与右侧完全
 *  重叠，因此落在**距左 48dp**（详见 WHEEL_LEFT 注释）。
 * ======================================================================
 */

/** 方向轮盘距左（字面要求是「距右 48dp」，两个轮盘都贴右会重叠，见文件头） */
private val WHEEL_LEFT = 48.dp

/** 云台轮盘距右 */
private val WHEEL_RIGHT = 48.dp

/** 两个轮盘统一的距底 */
private val WHEEL_BOTTOM = 38.dp

/** 两个轮盘统一尺寸（同大小、同高度） */
private val CONTROL_WHEEL_SIZE = 108.dp

/** 速度按钮 38dp（在轮盘正下方 10dp） */
private val SPEED_SIZE = 38.dp

/** 语音按钮 38dp（在速度按钮下方 8dp） */
private val VOICE_SIZE = 38.dp

private val SPEED_GAP = 10.dp
private val BUTTON_GAP = 8.dp

/** 返回键距顶 38dp / 距右 30dp */
private val BACK_TOP = 38.dp
private val BACK_END = 30.dp

private val FILL_10 = Color.White.copy(alpha = 0.10f)
private val STROKE_25 = Color.White.copy(alpha = 0.25f)

/**
 * 详细操控页。
 *
 * @param onBack 页面内返回键（系统返回由 MainCompose 的 BackHandler 处理，同样回首页）
 */
@Composable
fun ControlCompose(viewModel: MainViewModel, onBack: () -> Unit) {

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

    val voicePermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startVoice() else viewModel.reportVoicePermissionDenied()
    }

    // 离开本页 / 切后台时关掉采集（后台麦克风会被系统静音，留着只会亮隐私灯）
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 视频层：铺满整个窗口（状态栏 / 导航栏 / 挖孔区域都画进去），根布局无内边距
            .background(Color(0xFF0E0E10))
    ) {
        // 控件浮层：只让控件避让系统栏 / 挖孔，视频层不受影响、不会被挤小
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
        ) {
            // ---- 左侧：方向轮盘 → 10dp → 速度 38 → 8dp → 语音 38，左边统一对齐 ----
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = WHEEL_LEFT, bottom = WHEEL_BOTTOM)
            ) {
                JoystickDrive(
                    size = CONTROL_WHEEL_SIZE,
                    gear = gear,
                    viewModel = viewModel
                )

                Spacer(Modifier.size(SPEED_GAP))

                GearBallPicker(
                    gear = gear,
                    expanded = gearMenuOpen,
                    onExpandedChange = { gearMenuOpen = it },
                    onSelect = {
                        gear = it
                        gearMenuOpen = false
                    },
                    buttonSize = SPEED_SIZE,
                    // 向右弹出：只占按钮右侧，不会压到左侧轮盘的触点
                    expandRight = true
                )

                Spacer(Modifier.size(BUTTON_GAP))

                VoiceCircleButton(
                    on = voiceOn,
                    size = VOICE_SIZE,
                    onClick = {
                        if (voiceOn) {
                            viewModel.stopVoice()
                        } else if (viewModel.hasVoicePermission()) {
                            viewModel.startVoice()
                        } else {
                            voicePermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                )
            }

            // ---- 右侧：云台轮盘（与方向轮盘同尺寸、同距底，距右 48dp） ----
            GimbalJoystick(
                size = CONTROL_WHEEL_SIZE,
                onVector = { pan, tilt ->
                    // 0xEC 云台矢量，pan 正 = 右 / tilt 正 = 上，直接下发；
                    // 与移动帧完全独立，各自 200ms 计时
                    if (!viewModel.sendGimbalVector(pan, tilt)) viewModel.notifyCommandDropped()
                },
                onStop = {
                    // 定稿协议没有「云台停止」字段：停发即停转，300ms 无新帧固件自停且不回中
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = WHEEL_RIGHT, bottom = WHEEL_BOTTOM)
            )

            // ---- 右上：页面内返回键（距顶 38dp / 距右 30dp） ----
            BackButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = BACK_TOP, end = BACK_END)
            )
        }
    }
}

/** 页面内返回键（40dp，透明白风格） */
@Composable
private fun BackButton(onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(FILL_10)
            .border(1.dp, STROKE_25, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "返回首页",
            tint = Color.White,
            modifier = Modifier.size(20.dp)
        )
    }
}
