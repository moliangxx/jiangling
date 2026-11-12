package com.example.jiangling.main.control

import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.example.jiangling.data.MainViewModel
import com.example.jiangling.util.M
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/*
 * ======================================================================
 *  首页 / 详细操控页共用的操控控件（只有控件本身，不含任何页面布局）
 * ======================================================================
 *
 *  - JoystickDrive     移动轮盘（八方位 → 转向 + 前后两轴分量，200ms 节拍下发）
 *  - GimbalJoystick    云台轮盘（八方位 → 偏航 + 俯仰两轴增量）
 *  - GearBallPicker    圆形挡位按钮 + 点击向上弹出的四档圆球
 *  - VoiceCircleButton 圆形语音开关
 *
 * 方位判定与分量换算在 JoystickMath.kt（纯函数），节流下发在 ThrottledSender.kt，
 * 本文件只负责「画 + 收手势」。
 *
 * 两页各自的排版（位置、间距、尺寸）写在各自文件里，互不复用布局。
 * ======================================================================
 */

/** 透明白风格：填充 10~12%、描边 25%、摇杆头 45% */
private val FILL_10 = Color.White.copy(alpha = 0.10f)
private val FILL_12 = Color.White.copy(alpha = 0.12f)
private val STROKE_25 = Color.White.copy(alpha = 0.25f)
private val FILL_45 = Color.White.copy(alpha = 0.45f)

/** 限位提示：命中不可用方位时摇杆头收窄行程并转成红色 */
private val LIMIT_STROKE = Color(0xFFE07070).copy(alpha = 0.55f)
private val LIMIT_FILL = Color(0xFFE07070).copy(alpha = 0.28f)

/** 命中不可用方位时，摇杆头最多只能走到正常行程的这个比例（限位手感） */
private const val LIMIT_TRAVEL_SCALE = 0.5f

/** 可选的四个挡位 */
val GEAR_OPTIONS = listOf(25, 50, 75, 100)

// ======================================================================
//  可拖拽轮盘
// ======================================================================

/**
 * 可拖拽轮盘（替代四方向键）。
 *
 * 拖拽时摇杆头跟随手指（限制在圆内），把归一化向量回调给外部；
 * 松手 / 手势取消 / 手指拖出轮盘范围时，摇杆头弹回中心并回调 [onRelease]。
 *
 * @param onMove 归一化偏移，x/y ∈ [-1, 1]，y 为负表示向上（前进）
 * @param blocked 当前方位不可用（正左/正右）：摇杆头限位并变红，作为"此路不通"的提示
 */
@Composable
fun JoystickPad(
    size: Dp,
    onMove: (x: Float, y: Float) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
    blocked: Boolean = false
) {
    val radiusPx = with(LocalDensity.current) { (size / 2).toPx() }
    // 摇杆头最多能离中心这么远：外圆半径 - 摇杆头半径（留一点边）
    val maxTravel = radiusPx * 0.56f

    var knob by remember { mutableStateOf(Offset.Zero) }
    // 命中限位方位时，把行程收窄，视觉上就是"推不过去"
    val shownKnob = if (blocked) knob * LIMIT_TRAVEL_SCALE else knob
    val animatedKnob by animateOffsetAsState(targetValue = shownKnob, label = "joystickKnob")

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(FILL_10)
            .border(1.dp, STROKE_25, CircleShape)
            .pointerInput(radiusPx) {
                var aborted = false
                detectDragGestures(
                    onDragStart = { aborted = false },
                    onDrag = drag@{ change, dragAmount ->
                        change.consume()
                        if (aborted) return@drag
                        val raw = knob + dragAmount
                        // 超出轮盘范围一律按松手处理，本次手势后续事件全部忽略
                        if (hypot(raw.x, raw.y) > radiusPx) {
                            aborted = true
                            knob = Offset.Zero
                            onRelease()
                            return@drag
                        }
                        knob = clampToCircle(raw, maxTravel)
                        onMove(knob.x / maxTravel, knob.y / maxTravel)
                    },
                    // 松手 / 手势被系统取消：都按"回到中心 + 停止"处理，避免小车一直跑
                    onDragEnd = {
                        if (!aborted) {
                            knob = Offset.Zero
                            onRelease()
                        }
                    },
                    onDragCancel = {
                        knob = Offset.Zero
                        onRelease()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size * 0.42f)
                .offset { IntOffset(animatedKnob.x.roundToInt(), animatedKnob.y.roundToInt()) }
                .clip(CircleShape)
                .background(if (blocked) LIMIT_FILL else FILL_45)
                .border(1.dp, if (blocked) LIMIT_STROKE else Color.White.copy(alpha = 0.35f), CircleShape)
        )
    }
}

/** 把偏移限制在半径 [maxTravel] 的圆内 */
private fun clampToCircle(raw: Offset, maxTravel: Float): Offset {
    val distance = hypot(raw.x, raw.y)
    return if (distance <= maxTravel || distance == 0f) {
        raw
    } else {
        raw * (maxTravel / distance)
    }
}

/**
 * 移动轮盘：八方位 → 「纵向 drive + 转向 turn」两轴，各自带符号，装进同一帧 `0xEB` 下发。
 *
 * 下发规则（定稿协议 `CMD_MOVE_VECTOR`）：
 *  - 正左 / 正右需要横移，差速车做不到，**直接拦截不发任何命令**，只给限位提示；
 *  - 斜向例如右前 = `drive>0 且 turn<0`，两个分量同帧下发，由固件合成；
 *    `drive` 正=前进、负=后退；`turn` 正=左转、负=右转（后退两格符号相反）；
 *  - 节拍统一交给 [ThrottledSender]：按住期间 200ms 一帧重发，松手补一帧 `0xE0` 并停发。
 *
 * 速度 = 挡位（25/50/75/100）× 拖拽幅度，输出 `-100`~`100` 的百分比。
 */
@Composable
fun JoystickDrive(
    size: Dp,
    gear: Int,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val radiusPx = with(LocalDensity.current) { (size / 2).toPx() }
    var blocked by remember { mutableStateOf(false) }

    val sender = rememberThrottledSender<JoystickVector>(
        onFrame = { vector ->
            // 0xEB 运动矢量：drive + turn 装进同一帧，两轴各自带符号一起下发。
            // turn 的正方向已在 resolveJoystick 按协议定为「正 = 左转」，这里不再取反。
            val drive = percentOf(vector.forward, gear)
            val turn = percentOf(vector.turn, gear)
            if (drive != 0 || turn != 0) {
                // 发不出去要看得见，不能静默吞掉
                if (!viewModel.sendMoveVector(drive, turn)) viewModel.notifyCommandDropped()
            }
        },
        onStop = {
            // 松手立刻补一帧停车（0xE0），然后停止节拍；车端 300ms 无新帧也会自停，双保险
            if (viewModel.canSendCommand()) viewModel.sendCommand(M.CMD_STOP, 0)
        }
    )

    JoystickPad(
        size = size,
        modifier = modifier,
        blocked = blocked,
        onMove = { nx, ny ->
            val vector = resolveJoystick(nx * radiusPx, ny * radiusPx, radiusPx)
            blocked = vector.way.isLateral
            if (vector.way == DriveWay.NONE || vector.way.isLateral) {
                /*
                 * 回中或命中正左/正右：都不下发方向命令。
                 * 这里必须顺带把上一帧停掉——节拍循环会重发「最新状态」，
                 * 若不清理，进入不可用方位后仍会每 200ms 重发上一帧（例如前进）。
                 */
                sender.release()
            } else {
                sender.submit(vector)
            }
        },
        onRelease = {
            blocked = false
            sender.release()
        }
    )
}

/** 分量（-1..1）× 量程（挡位 25/50/75/100）→ -100..100 的整数百分比，保留符号 */
private fun percentOf(component: Float, range: Int): Int =
    (component * range).roundToInt().coerceIn(-100, 100)

// ======================================================================
//  挡位：圆形按钮 + 向上弹出的四档圆球
// ======================================================================

/** 弹层里每个圆球的尺寸与间距 */
private val BALL_SIZE = 34.dp
private val BALL_GAP = 6.dp
private val MENU_PADDING = 4.dp

/**
 * 圆形挡位按钮。点击后弹出一列圆球（25/50/75/100），选中即收起；
 * 点弹层外部或按系统返回键也会收起（Popup 的 focusable 行为）。
 *
 * 弹出方向由 [expandRight] 决定：
 * - `false`（默认）：向**正上方**弹，用于按钮下方还有别的东西的场合；
 * - `true`：向**右侧**弹，垂直居中对齐按钮。首页与详细操控页的速度按钮都用这个，
 *   弹层只占按钮右侧，**不会压到旁边的轮盘**。
 *
 * @param buttonSize 按钮直径（首页 44dp / 详细操控页 38dp）
 * @param expandRight true = 向右弹出
 */
@Composable
fun GearBallPicker(
    gear: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 40.dp,
    expandRight: Boolean = false
) {
    // 弹层总高度：4 个球 + 3 个间距 + 上下内边距
    val menuHeight = BALL_SIZE * GEAR_OPTIONS.size + BALL_GAP * (GEAR_OPTIONS.size - 1) +
        MENU_PADDING * 2
    val offsetY = with(LocalDensity.current) { -menuHeight.roundToPx() }
    val buttonPx = with(LocalDensity.current) { buttonSize.roundToPx() }

    Box(modifier = modifier.size(buttonSize)) {
        Box(
            modifier = Modifier
                .size(buttonSize)
                .clip(CircleShape)
                .background(FILL_12)
                .border(1.dp, STROKE_25, CircleShape)
                .clickable { onExpandedChange(!expanded) },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "$gear",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White
            )
        }

        if (expanded) {
            Popup(
                // 向右：弹层左缘贴按钮右缘，**底边与按钮齐平向上排**——
                // 这样只占按钮右上方的空白，不会向下压到轮盘触点；
                // 向上（默认）：弹层整体落在按钮正上方
                alignment = if (expandRight) Alignment.BottomStart else Alignment.TopCenter,
                offset = if (expandRight) IntOffset(buttonPx, 0) else IntOffset(0, offsetY),
                onDismissRequest = { onExpandedChange(false) },
                properties = PopupProperties(focusable = true)
            ) {
                GearBallColumn(gear = gear, onSelect = onSelect)
            }
        }
    }
}

/** 挡位弹层主体：一列圆球，从下往上 100 → 25 */
@Composable
private fun GearBallColumn(gear: Int, onSelect: (Int) -> Unit) {
    Column(
        modifier = Modifier.padding(MENU_PADDING),
        verticalArrangement = Arrangement.spacedBy(BALL_GAP),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        GEAR_OPTIONS.sortedDescending().forEach { option ->
            val selected = option == gear
            Box(
                modifier = Modifier
                    .size(BALL_SIZE)
                    .clip(CircleShape)
                    .background(if (selected) FILL_45 else Color(0xFF2A2A2E))
                    .border(1.dp, STROKE_25, CircleShape)
                    .clickable { onSelect(option) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$option",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) Color.Black else Color.White
                )
            }
        }
    }
}

// ======================================================================
//  语音开关
// ======================================================================

/** 圆形语音开关：关闭 = Call / 白，开启 = CallEnd / 白 45% 底 + 黑图标 */
@Composable
fun VoiceCircleButton(
    on: Boolean,
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (on) FILL_45 else FILL_12)
            .border(1.dp, STROKE_25, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (on) Icons.Filled.CallEnd else Icons.Filled.Call,
            contentDescription = if (on) "关闭语音" else "开启语音",
            tint = if (on) Color.Black else Color.White,
            modifier = Modifier.size(size * 0.5f)
        )
    }
}

// ======================================================================
//  云台轮盘
// ======================================================================

/**
 * 云台轮盘：与移动轮盘同一套八方位判定，斜向同样拆成两个轴分量，
 * 装进同一帧 `0xEC` 下发，**与移动轮盘完全独立**、各自计时、互不影响。
 *
 * 映射：`tilt` = forward（正=上）、`pan` = -turn（正=右，因 turn 的正方向是左）。
 * 停发即停转：定稿协议没有云台停止字段，固件 300ms 无新帧自停且不回中。
 *
 * 云台不拦截正左/正右——偏航本来就要左右转。
 */
@Composable
fun GimbalJoystick(
    size: Dp,
    onVector: (pan: Int, tilt: Int) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val radiusPx = with(LocalDensity.current) { (size / 2).toPx() }

    val sender = rememberThrottledSender<JoystickVector>(
        onFrame = { vector ->
            // 0xEC 云台矢量：tilt 正 = 上（直接用 forward）；
            // pan 正 = 右，而 resolveJoystick 的 turn 是「正 = 左」，所以这里取反。
            val tilt = percentOf(vector.forward, 100)
            val pan = percentOf(-vector.turn, 100)
            if (pan != 0 || tilt != 0) onVector(pan, tilt)
        },
        onStop = onStop
    )

    JoystickPad(
        size = size,
        modifier = modifier,
        onMove = { nx, ny ->
            val vector = resolveJoystick(nx * radiusPx, ny * radiusPx, radiusPx)
            if (vector.way == DriveWay.NONE) sender.release() else sender.submit(vector)
        },
        onRelease = { sender.release() }
    )
}
