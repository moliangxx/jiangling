package com.example.jiangling.main

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.jiangling.data.MainViewModel
import com.example.jiangling.util.M
import com.example.jiangling.util.U

/*
 * ======================================================================
 *  控制页（HomeScreen）——整个 App 的首页
 * ======================================================================
 *
 *  布局结构（从上到下）：
 *  ┌─────────────────────────────────────────────┐
 *  │  摄像头占位（16:9 黑块）                     │
 *  ├─────────────────────────────────────────────┤
 *  │  状态卡片：连接 | 设备 | 电量 | 速度         │
 *  ├─────────────────────────────────────────────┤
 *  │  方向键盘（十字键）                           │
 *  │          ▲                                  │
 *  │     ◀    ■    ▶                             │
 *  │          ▼                                  │
 *  ├─────────────────────────────────────────────┤
 *  │  底部说明小字                                 │
 *  └─────────────────────────────────────────────┘
 *  （连接/断开统一由标题栏右侧的状态 + 调试页的单个按钮负责）
 *
 *  【Compose 关键概念，这里用到的】
 *
 *  1) collectAsState() 把 Flow 变成可观察的 state
 *     val status by viewModel.status.collectAsState()
 *     status 变了 → Compose 自动重组这个函数 → 界面刷新
 *
 *  2) Column / Row / Box 三种布局容器
 *     Column = 从上到下排    Row = 从左到右排    Box = 叠放
 *
 *  3) weight(1f) = 占满剩余空间
 *     Row { 左侧内容; Box(Modifier.weight(1f)) { 中间空白 }; 右侧内容 }
 *     → 中间的 Box 会吃掉左右两侧之外的所有宽度
 *
 *  4) Spacer = 占位空隙
 *     Spacer(Modifier.height(12.dp))  → 留 12dp 的竖直空隙
 *     Spacer(Modifier.width(8.dp))   → 留 8dp 的水平空隙
 *     比 padding 更安全——不会意外把别的控件也挤开
 *
 *  5) Modifier.clip() + .background() + .pointerInput()
 *     组合效果 = 圆角可点击按钮：
 *     Modifier
 *       .clip(RoundedCornerShape(12.dp))   // 先裁剪圆角
 *       .background(Color.Red)             // 再画背景（被圆角裁掉）
 *       .pointerInput { detectTapGestures { onClick() } }  // 最后加点击
 *     ✘ 顺序反了就不生效！clip 必须在 background 之前
 *
 *  6) detectTapGestures { onPress = { ...; tryAwaitRelease(); ... } }
 *     长按/按住手势：按下执行第一段，松手执行第二段。
 *     这里用来做方向键——按住前进、松手自动 STOP。
 *     tryAwaitRelease() 是挂起函数，会一直等手指抬起
 *
 *  7) Card / Surface / Box 的区别
 *     Box = 最基础的容器，不带背景/阴影
 *     Surface = 带背景色 + 阴影 + Material 点击波纹
 *     Card = Surface + 默认圆角 + 内边距（Material3 风格卡片）
 *     ✘ 不要嵌套太多层 Card/Surface，会出现叠加阴影很难看
 *
 *  8) LaunchedEffect(message) { ... }
 *     某个 state 变化时执行一段协程代码。
 *     这里用来弹 Toast——每当 viewModel.message 有新值就弹一次，
 *     然后调 consumeMessage() 把它清空，避免下次重组又弹一遍。
 * ======================================================================
 */

/**
 * 控制页根 Composable。
 *
 * @param viewModel 全局唯一的 ViewModel，所有数据都从这里读
 */
@Composable
fun HomeScreen(viewModel: MainViewModel) {

    // ======== 读取状态 ========
    // by 关键字：自动把 StateFlow<CarStatus> 解包成 CarStatus
    val status by viewModel.status.collectAsState()
    val message by viewModel.message.collectAsState()

    // ======== Toast 弹出 ========
    // ⚠ LocalContext.current 是 Composable 调用，必须在外层捕获，不能放在 LaunchedEffect 里！
    val context = LocalContext.current
    LaunchedEffect(message) {
        message?.let {
            U.t(context, it)                     // 弹 Toast
            viewModel.consumeMessage()            // 清掉，避免重复弹
        }
    }

    // ======== 主布局：Column 从上到下排 ========
    Column(
        modifier = Modifier
            .fillMaxSize()                       // 占满父容器（标题栏下面的全部空间）
            .padding(16.dp),                     // 四周各留 16dp 边距
        verticalArrangement = Arrangement.spacedBy(12.dp)  // 子元素之间统一留 12dp
    ) {

        // ==================== 1. 摄像头占位 ====================
        CameraPanel()

        // ==================== 2. 状态卡片 ====================
        // weight(0f)：固定高度，不参与剩余空间分配
        StatusCard(
            connected = status.connected,
            deviceName = status.deviceName,
            battery = status.battery,
            speed = status.speed
        )

        // ==================== 3. 方向键盘 ====================
        // weight(1f)：吃掉标题栏、摄像头、状态卡、底部按钮之外的所有空间
        // 这样键盘会自动居中，不会被挤出屏幕
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center
        ) {
            DirectionPad(viewModel)
        }

        // ==================== 4. 底部说明 ====================
        Text(
            text = "当前为蓝牙调试模式，方向指令只发送给小车",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ======================================================================
//  子 Composable
// ======================================================================

/**
 * 摄像头占位面板（16:9 比例的黑色矩形）。
 * 以后接了真摄像头，把 Box 里的 Text 换成 AndroidView + CameraX 即可。
 */
@Composable
private fun CameraPanel() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)          // 固定 16:9 宽高比
            .clip(RoundedCornerShape(16.dp)) // 圆角 16dp
            .background(Color(0xFF1C1C1E)), // 深灰背景（模拟摄像头待机）
        contentAlignment = Alignment.Center
    ) {
        // 里面叠两层 Column：文字居中小标题
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "摄像头",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "视频功能待接入",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall
            )
        }

        // 左上角小标签（未连接时显示）
        Text(
            text = "未连接",
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.TopStart)      // Box 内的定位：左上角
                .padding(12.dp)
        )
    }
}

/**
 * 状态卡片：四列并排显示连接状态、设备名、电量、速度。
 */
@Composable
private fun StatusCard(
    connected: Boolean,
    deviceName: String?,
    battery: Double,
    speed: Double
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            InfoCell(
                label = "连接",
                value = if (connected) "已连接" else "未连接",
                modifier = Modifier.weight(1f)
            )
            InfoCell(
                label = "设备",
                value = deviceName ?: "—",
                modifier = Modifier.weight(1f)
            )
            InfoCell(
                label = "电量",
                value = if (battery > 0) "%.1f%%".format(battery) else "—",
                modifier = Modifier.weight(1f)
            )
            InfoCell(
                label = "速度",
                value = if (speed > 0) "%.2f m/s".format(speed) else "—",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * 状态卡片里的一格：上面小标签，下面大数值。
 * weight(1f) 让四格平均分宽度。
 */
@Composable
private fun InfoCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,                        // 最多 1 行
            overflow = TextOverflow.Ellipsis     // 超出显示 ...
        )
    }
}

// ----------------------------------------------------------------------
//  方向键盘
// ----------------------------------------------------------------------

/**
 * 十字方向键盘整体。
 *
 * 布局：
 *       ▲
 *  ◀    ■    ▶
 *       ▼
 *
 * 中间那个是 STOP 键（红色圆形），四角/四边是方向键。
 */
@Composable
private fun DirectionPad(viewModel: MainViewModel) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        DirectionKey("▲", M.CMD_FORWARD, viewModel)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DirectionKey("◀", M.CMD_LEFT, viewModel)
            DirectionKey("■", M.CMD_STOP, viewModel, isStop = true)
            DirectionKey("▶", M.CMD_RIGHT, viewModel)
        }

        DirectionKey("▼", M.CMD_BACK, viewModel)
    }
}

/**
 * 单个方向键。
 *
 * 【交互逻辑】
 *  - 按下 → 发送方向命令（带 speed=100，即最大速度的 100%）
 *  - 松手 → 自动发送 STOP 命令（isStop=true 的键除外）
 *  - STOP 键按下只发一次 STOP，松手不再额外发
 *
 * 【参数说明】
 * @param label 键盘上显示的文字，比如 "▲"、"◀"
 * @param cmdKindType 对应的 CMD KIND_TYPE，比如 M.CMD_FORWARD
 * @param viewModel 发命令通过 viewModel
 * @param isStop true = STOP 键，红色背景，松手不自动发 STOP
 * @param speed 挡位百分比 0~100（V1.7 起含义是"最大速度 / 最大回转速度的百分比"，0 = 停止）
 */
@Composable
private fun DirectionKey(
    label: String,
    cmdKindType: Int,
    viewModel: MainViewModel,
    isStop: Boolean = false,
    speed: Int = M.SPEED_MAX
) {
    Box(
        modifier = Modifier
            .size(68.dp)                     // 固定 68x68dp
            .clip(CircleShape)               // 先裁成圆形
            .background(
                if (isStop) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.primaryContainer
            )
            .pointerInput(cmdKindType) {       // detectTapGestures 识别按压手势
                detectTapGestures(
                    onPress = {
                        // 手指按下那一刻：发送方向命令
                        viewModel.sendCommand(cmdKindType, speed)
                        // tryAwaitRelease() = 挂起等待手指抬起
                        // 返回后手指已经离开屏幕
                        tryAwaitRelease()
                        // 松手后（如果不是 STOP 键）自动发 STOP
                        if (!isStop) {
                            viewModel.sendCommand(M.CMD_STOP, 0)
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            // 反色文字：primaryContainer 背景用 primary 色字；errorContainer 用 error 色字
            color = if (isStop) MaterialTheme.colorScheme.onErrorContainer
                   else MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}


