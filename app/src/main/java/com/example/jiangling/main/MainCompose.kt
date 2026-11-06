package com.example.jiangling.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.jiangling.ble.ConnectionState
import com.example.jiangling.data.MainViewModel
import com.example.jiangling.main.cmd.CommandCompose
import com.example.jiangling.main.debug.DetailCompose
import com.example.jiangling.main.debug.LogCompose

/*
 * ======================================================================
 *  Pure Compose 根页面（唯一 Activity，没有 Fragment，没有 XML）
 * ======================================================================
 *
 *  【页面层级】
 *  ┌─────────────────────────────────────────────────────────────────────┐
 *  │  TopBar（**全 App 唯一共用**，所有页面都挂它）                        │
 *  │                                                                     │
 *  │  一级页面（Home / Debug）:                                          │
 *  │    左 [切换按钮] 26dp [灵渊 + 连接状态小字]   ← 尾部占位             │
 *  │                                                                     │
 *  │  二级页面（Detail / Log / Command）:                                │
 *  │    左 [← 返回箭头] 26dp [页面标题 + 连接状态小字]   ← 尾部占位       │
 *  ├─────────────────────────────────────────────────────────────────────┤
 *  │                                                                     │
 *  │  内容区（Box，根据 Route 决定显示哪个 Screen）                        │
 *  │                                                                     │
 *  │    Route.HOME     → HomeScreen     （控制页：方向键盘 + 摄像头占位）   │
 *  │    Route.DEBUG    → DebugScreen    （调试页：扫描 + 命令 + 入口）     │
 *  │    Route.DETAIL   → DetailCompose   （详细数据）                      │
 *  │    Route.LOGS     → LogCompose      （运行日志）                      │
 *  │    Route.COMMANDS → CommandCompose  （命令记录）                      │
 *  │                                                                     │
 *  └─────────────────────────────────────────────────────────────────────┘
 *
 *  【导航原理】
 *  - 一个 Route 枚举值 = "现在在哪一页"，切换页面 = 改这个值
 *  - 用 rememberSaveable 包着：屏幕旋转 / 进程重建后能恢复当前页面
 *  - BackHandler：拦截系统返回键（物理返回键 / 手势），二级页面回到 DEBUG，
 *    一级页面就让系统处理（退出 App）
 *
 *  【关于 Compose 布局，重要！！！】
 *  Compose 和传统 View/HTML 的区别非常大，你之前可能踩坑的地方：
 *
 *  1) Modifier 是链式调用，顺序 = 生效顺序
 *     Modifier.padding(16.dp).background(红)  → 先留白再画红（留白不红）
 *     Modifier.background(红).padding(16.dp)  → 先画红再留白（整块红）
 *     ✘ 反过来写样式就废了
 *
 *  2) Modifier.weight(n) 只能在 Column/Row 里用，意思是"占剩余空间的 n 份"
 *     Row { A.weight(1f); B.weight(1f) } → A 和 B 各占一半
 *     Row { A.weight(1f); B } → B 先按自身内容宽度排，A 吃掉剩下所有空间
 *     ✘ weight 外面包一层 Box / Surface 就失效了
 *
 *  3) 没有 XML 预览、没有拖拽对齐 → 用 Spacer(Modifier.height(12.dp)) 留缝，
 *     用 Arrangement.spacedBy(8.dp) 统一子元素间距，少写零散 padding
 *
 *  4) fillMaxSize() / fillMaxWidth() / fillMaxHeight() 决定"占多大"
 *     padding(8.dp) 决定"里面留多少边距"
 *     ✘ 写了 fillMaxSize 再写固定 width 会冲突
 *
 *  5) safeDrawingPadding() 自动避开状态栏/导航栏/Fab，
 *     旧 XML 的 fitsSystemWindows 属性在 Compose 里就换成这个
 *
 *  6) 颜色：MaterialTheme.colorScheme.primary 跟随主题，Color(0xFFxxxxxx) 固定色值
 *     字号：MaterialTheme.typography.titleLarge 跟随主题，16.sp 固定
 *
 *  【新增页面怎么做】（比如加一个"设置页"）
 *  1) Route 枚举里加一项 SETTINGS
 *  2) 新建 SettingsCompose(viewModel, onBack)，写好参数：
 *     - viewModel: MainViewModel  ← 用来读写状态
 *     - onBack: () -> Unit         ← 点返回时调，回到你想回的地方
 *  3) 在 JiangLingApp 的 when(route) 里加分支：Route.SETTINGS -> SettingsCompose(...)
 *  4) 如果是二级页面：加到 isSecondary 判断里，这样返回键自动回到 DEBUG
 *  5) 如果是一级页面：自己决定和 Home/Debug 怎么切换
 * ======================================================================
 */

/**
 * 所有可用的页面路由。
 *
 * 命名规则：枚举名全大写，页面 Composable 名 PascalCase。
 * 修改枚举名后记得同步修改下面的 when 分支和 isSecondary 判断。
 */
private enum class Route {
    // ========== 一级页面（共享同一 TopBar 的切换按钮）==========
    HOME,       // 控制页：方向键盘 + 摄像头 + 状态卡
    DEBUG,      // 调试页：蓝牙扫描 + 命令发送 + 三个入口

    // ========== 二级页面（从 Debug 进入，TopBar 切换按钮变返回箭头）==========
    DETAIL,     // 详细数据：电量、速度、运行状态
    LOGS,       // 运行日志：蓝牙收发日志
    COMMANDS    // 命令记录：发送的命令 + ACK 回复
}

/**
 * 判断当前 Route 是否是"二级页面"。
 * 二级页面的 TopBar 会显示返回箭头而不是"控制"切换按钮。
 * 新加的二级页面（比如 SETTINGS）记得加到这里。
 */
private fun Route.isSecondary(): Boolean =
    this == Route.DETAIL || this == Route.LOGS || this == Route.COMMANDS

/**
 * 页面显示的标题文字。
 * 一级页面（首页 / 调试页）固定显示"灵渊"，二级页面显示各自标题。
 */
private fun Route.displayTitle(): String = when (this) {
    Route.HOME, Route.DEBUG -> "灵渊"
    Route.DETAIL -> "详细数据"
    Route.LOGS -> "运行日志"
    Route.COMMANDS -> "命令记录"
}

// ==================== 主入口 Composable ====================

/**
 * 整个 App 的界面入口。
 *
 * 为什么这里要传 viewModel 进来？
 * MainActivity 里 by viewModels() 创建的 MainViewModel 是全局单例（整个 Activity 共用一个），
 * JiangLingApp 和所有子页面都读同一个实例，所以状态能共享。
 */
@Composable
fun JiangLingApp(viewModel: MainViewModel) {

    // ======== 当前页面状态 ========
    // rememberSaveable：旋转屏幕 / 进程被系统杀死后重新创建时，能从 Bundle 里恢复这个值。
    var route by rememberSaveable { mutableStateOf(Route.HOME) }

    // 连接三态直接读 MainViewModel，不另造一套状态
    val connectionState by viewModel.connectionState.collectAsState()

    // ======== 系统返回键拦截 ========
    // 只拦截二级页面的返回键——二级页面按返回键回到 DEBUG；
    // 一级页面（HOME / DEBUG）按返回键交给系统处理（退出 App）。
    BackHandler(enabled = route.isSecondary()) {
        route = Route.DEBUG
    }

    // ======== 整块布局 ========
    // safeDrawingPadding() 避开顶部状态栏和底部系统导航栏。
    Column(
        modifier = Modifier
            .fillMaxSize()       // 占满整个屏幕
            .safeDrawingPadding()
    ) {

        // ======== 1. 共享标题栏（所有页面共用，含连接状态小字） ========
        SharedTopBar(
            currentRoute = route,
            connectionState = connectionState,
            onToggle = {
                // 一级页面：Home ↔ Debug 快速切换（唯一入口）
                route = if (route == Route.HOME) Route.DEBUG else Route.HOME
            },
            onBack = {
                // 二级页面：一律回到 Debug
                route = Route.DEBUG
            }
        )

        // ======== 2. 页面内容区 ========
        // Box + weight(1f)：占满标题栏下面的所有剩余空间。
        Box(modifier = Modifier.weight(1f)) {

            // 根据 route 决定显示哪个 Screen
            when (route) {

                // ---- 一级页面 ----
                Route.HOME -> HomeScreen(viewModel = viewModel)

                Route.DEBUG -> DebugScreen(
                    viewModel = viewModel,
                    onOpenDetail   = { route = Route.DETAIL },
                    onOpenLogs     = { route = Route.LOGS },
                    onOpenCommands = { route = Route.COMMANDS }
                )

                // ---- 二级页面 ----
                Route.DETAIL -> DetailCompose(viewModel = viewModel)

                Route.LOGS -> LogCompose(
                    viewModel = viewModel,
                    onBack = { route = Route.DEBUG }
                )

                Route.COMMANDS -> CommandCompose(
                    viewModel = viewModel,
                    onBack = { route = Route.DEBUG }
                )
            }
        }
    }
}

// ======================================================================
//  共享标题栏
// ======================================================================

/**
 * 所有页面共用的标题栏（**全 App 只有这一个 TopBar，各页不再各写一套**）。
 *
 * 【一级页面（首页 / 调试页）】从左到右：
 *  ```
 *  [切换按钮] 26dp  灵渊
 *                  ● 未连接        ← 尾部 weight(1f) 吃掉剩余宽度
 *  ```
 *
 * 【二级页面（详细数据 / 运行日志 / 命令记录）】：
 *  ```
 *  [← 返回]   26dp  详细数据
 *                  ● 已连接        ← 与一级页使用相同的 26dp 间距
 *  ```
 *
 * 标题下方那行小字是蓝牙连接三态，直接读 [ConnectionState]，不另起状态。
 * 未连接灰、已连接绿、重连中黄；重连中的圆点有轻微呼吸，不闪烁。
 *
 * @param currentRoute 当前页面（决定一级/二级形态与标题）
 * @param connectionState MainViewModel 暴露的真实连接三态
 * @param onToggle 一级页点切换按钮（首页 ↔ 调试页）
 * @param onBack 二级页点返回箭头
 */
@Composable
private fun SharedTopBar(
    currentRoute: Route,
    connectionState: ConnectionState,
    onToggle: () -> Unit,
    onBack: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 2.dp,
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)                  // 需要容纳标题 + 状态两行
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            // ======== 左侧 ========
            if (currentRoute.isSecondary()) {
                // 二级页面：最左边只放返回键
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            } else {
                // 一级页面：最左边只有一个切换按钮（文字 + 图标）
                // 文字显示"要去的页面"，点击在首页 ↔ 调试页之间切换
                TopBarEntry(
                    icon = Icons.Default.SwapHoriz,
                    text = if (currentRoute == Route.HOME) "调试" else "首页",
                    onClick = onToggle
                )
            }

            // ======== 标题 + 状态：与左侧控件保持统一间距 ========
            // 固定 26dp，一级页（切换按钮）与二级页（返回键）一致；
            // 尾部 weight(1f) 仍负责吃掉剩余宽度，标题不会被推到屏幕最右
            Spacer(Modifier.width(26.dp))

            Column(horizontalAlignment = Alignment.Start) {
                Text(
                    text = currentRoute.displayTitle(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                ConnectionStatusText(connectionState)
            }

            // 尾部占位：吃掉剩余宽度，让标题区保持靠左
            Spacer(Modifier.weight(1f))
        }
    }
}

/** 一级页切换按钮：图标 + 文字，整块可点 */
@Composable
private fun TopBarEntry(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1
        )
    }
}

/**
 * 标题下方的连接状态小字（一行，无点击）。
 *
 * 三态配色：未连接灰、已连接绿、重连中黄；重连中圆点轻微呼吸（alpha 0.45~1.0）。
 *
 * @param state 只读 [MainViewModel.connectionState] 的当前值
 */
@Composable
private fun ConnectionStatusText(state: ConnectionState) {
    val color = when (state) {
        ConnectionState.DISCONNECTED -> Color(0xFF9E9E9E)   // 灰
        ConnectionState.CONNECTED -> Color(0xFF43A047)      // 绿
        ConnectionState.RECONNECTING -> Color(0xFFF9A825)   // 黄
    }

    val dotAlpha = if (state == ConnectionState.RECONNECTING) {
        val transition = rememberInfiniteTransition(label = "reconnect")
        val alpha by transition.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200),
                repeatMode = RepeatMode.Reverse
            ),
            label = "reconnectAlpha"
        )
        alpha
    } else {
        1f
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "●",
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.alpha(dotAlpha)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = state.label,
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}