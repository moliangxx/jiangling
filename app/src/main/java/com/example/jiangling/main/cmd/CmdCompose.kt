package com.example.jiangling.main.cmd

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.jiangling.data.MainViewModel
import com.example.jiangling.data.MainViewModel.ReplyFilter
import com.example.jiangling.data.command.Command
import com.example.jiangling.main.debug.DateQueryDialog
import com.example.jiangling.util.M
import com.example.jiangling.util.U

/*
 * ======================================================================
 *  命令记录页（CommandCompose）
 * ======================================================================
 *
 *  【这个页面显示什么】
 *  一条命令 = 一次 CMD + 一次 ACK，是 App 本地建的记录（不是小车推的日志）。
 *  每条记录有：命令内容、发送时间、回复内容、回复时间、是否重发过。
 *
 *  【页面结构（和日志页保持一致的操作习惯）】
 *  ┌──────────────────────────────────────────────────────────────┐
 *  │  命令记录                         ← 大标题                    │
 *  │  共 N 条 · 范围：本次启动之后      ← 副标题                    │
 *  │                                                              │
 *  │  [降序 ↓] [全部] [有回复] [没有]   ← 第一行：排序 + 回复筛选    │
 *  │  [时间：本次启动之后 ▾]            [清空当前]    ← 第二行      │
 *  ├──────────────────────────────────────────────────────────────┤
 *  │  命令列表（LazyColumn，每条一张卡片）                          │
 *  └──────────────────────────────────────────────────────────────┘
 *
 *  【四个操作】
 *  ① 降序 ↓ / 升序 ↑
 *     点一下切换排序，列表按"发送时间"重新排。降序 = 新的在最上面。
 *
 *  ② 全部 / 有回复 / 没有
 *     按"是否有 ACK 回复"筛选。三个按钮互斥，选中那个变主色。
 *     注意：这个筛选只影响【显示】，不影响③④的删除范围。
 *
 *  ③ 时间：xxx ▾
 *     按钮上显示当前时间范围。点开弹的日期选择框和【日志页是同一个】
 *     （LogCompose 里的 DateQueryDialog），选日期后可选「之前 / 之后」。
 *     弹窗左下角「恢复默认」= 回到 [firstConnectTime] 起点。
 *
 *  ④ 清空当前
 *     只删【当前时间区间】内的命令（= 当前列表），参数由本页面传。
 *     全 App 只有这一个删除入口，没有"清除全部"。
 *
 *  【数据从哪来（和日志页一样的分层）】
 *  ViewModel.commands       ← 当前显示的列表（由 queryCommands(...) 推送）
 *  ViewModel.commandDates   ← 有命令的日期列表，给弹窗用
 *  筛选条件（排序、回复筛选、时间区间）都在本页面的 remember 里；
 *  条件一变 → LaunchedEffect 重新执行 → 调一次 queryCommands()，
 *  所以 ViewModel 里不需要保存"查询条件"这个状态。
 *
 *  【关键点：默认时间与日志页共用】
 *  默认时间起点是 ViewModel 的 firstConnectTime：
 *  - 初始化时 = App 启动时刻；
 *  - 蓝牙首次主动连接成功时改成连接那一刻（只改一次）；
 *  - 所以命令页和日志页永远显示同一个默认时间范围。
 * ======================================================================
 */

@Composable
fun CommandCompose(viewModel: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current

    // ======== 页面自己持有的"筛选条件"（不进 ViewModel） ========
    var sortAsc by remember { mutableStateOf(false) }                               // 排序方向
    var replyFilter by remember { mutableStateOf(ReplyFilter.ALL) }                 // 是否有回复
    var time by remember { mutableStateOf(viewModel.firstConnectTime) }             // 基准时间点
    var isAfter by remember { mutableStateOf(true) }                                // true=之后，false=之前
    var label by remember { mutableStateOf("当前") }                                // 时间按钮上的文字
    var showDateDialog by remember { mutableStateOf(false) }                        // 弹窗开关

    // ======== 从 ViewModel 读数据 ========
    val commands by viewModel.commands.collectAsState()
    val dates by viewModel.commandDates.collectAsState()
    val message by viewModel.message.collectAsState()

    // ======== 任一条件变化 → 重新查询一次 ========
    LaunchedEffect(time, isAfter, sortAsc, replyFilter) {
        viewModel.queryCommands(time, isAfter, sortAsc, replyFilter)
    }

    LaunchedEffect(message) {
        message?.let {
            U.t(context, it)
            viewModel.consumeMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {

        // ==================== 标题区 ====================
        // 页面标题只由顶部共享 TopBar 显示，这里只保留条数/范围副标题
        Text(
            text = "共 ${commands.size} 条 · 范围：$label",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(6.dp))

        // ==================== 第一行操作：排序 + 回复筛选 ====================
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ① 排序按钮
            ToolbarTextButton(
                text = if (sortAsc) "升序 ↑" else "降序 ↓",
                highlighted = true,
                onClick = { sortAsc = !sortAsc }
            )

            // ② 三个回复筛选按钮，遍历枚举按声明顺序排：全部 / 有回复 / 没有
            ReplyFilter.entries.forEach { item ->
                ToolbarTextButton(
                    text = item.label,
                    highlighted = replyFilter == item,
                    onClick = { replyFilter = item }
                )
            }
        }

        // ==================== 第二行操作：时间 + 清空 ====================
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ③ 时间按钮：按钮上的文字就是当前范围，点开共用日志页的日期弹窗
            ToolbarTextButton(
                text = "时间：$label ▾",
                highlighted = true,
                onClick = { showDateDialog = true }
            )

            // 弹簧：把两个清空按钮推到最右
            Spacer(Modifier.weight(1f))

            // ④ 清空当前：只删当前列表（区间由本页面的 range 传参）
            ToolbarTextButton(
                text = "清空当前",
                enabled = commands.isNotEmpty(),
                onClick = { viewModel.deleteCommands(time, isAfter) }
            )
        }

        Spacer(Modifier.height(10.dp))

        // ==================== 列表 / 空状态 ====================
        if (commands.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = when (replyFilter) {
                            ReplyFilter.UNANSWERED -> "没有未回复的命令"
                            ReplyFilter.ANSWERED -> "没有已回复的命令"
                            ReplyFilter.ALL -> "暂无命令记录"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "默认只显示最近一段时间的记录，更早的点「时间」按日期查看",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                // key = 主键，保证列表增删时不闪烁
                items(commands, key = { it.id }) { command ->
                    CommandItem(command)
                }
            }
        }
    }

    // ==================== 日期查询弹窗（与日志页共用同一个实现） ====================
    if (showDateDialog) {
        DateQueryDialog(
            dates = dates,
            onQuery = { date, after ->
                U.parseDateStart(date)?.let { dayStart ->
                    time = dayStart
                    isAfter = after
                    label = "$date ${if (after) "后" else "前"}"
                }
                showDateDialog = false
            },
            onReset = {
                // 恢复默认：时间回到当前（firstConnectTime），方向=后，排序不动
                time = viewModel.firstConnectTime
                isAfter = true
                label = "当前"
                showDateDialog = false
            },
            onDismiss = { showDateDialog = false }
        )
    }
}

// ======================================================================
//  小工具：操作栏上的文字按钮
// ======================================================================

/**
 * 操作栏统一用的小文字按钮，两种视觉状态：
 * - highlighted = true → 主色文字（表示"当前生效的筛选/可点击"）
 * - 默认               → 普通文字色，禁用时降透明度
 *
 * 单独抽出来是为了让上面的操作栏代码更像"配置"而不是一堆重复的 TextButton。
 */
@Composable
private fun ToolbarTextButton(
    text: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
    enabled: Boolean = true
) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        highlighted -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    TextButton(
        onClick = onClick,
        enabled = enabled,
        // 收紧左右内边距，一行才放得下多个按钮
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

// ======================================================================
//  单条命令
// ======================================================================

/**
 * 单条命令卡片。
 * - 有回复：显示绿色 "回复｜状态" 和回复时间；
 * - 无回复：显示红色 "未收到回复"；
 * - 不自动重发，所以没有"已重发"这类标记。
 *
 * 【Compose 技巧】
 * Row 里给第一个子元素 Modifier.weight(1f)，它就会占满剩余宽度，
 * 自然把后面的内容推到最右边，不需要手动偏移。
 */
@Composable
private fun CommandItem(command: Command) {
    val answered = command.reply.isNotBlank()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 第一行：发起对象 + 中文含义
                Text(
                    text = command.role,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (command.role == M.ROLE_SEND)
                        MaterialTheme.colorScheme.primary
                    else
                        Color(0xFF00838F),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = command.content,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(4.dp))
            // 第二行：序列号 + 发送时间
            Text(
                text = "序列号 ${command.seq} · 发送 ${U.formatTime(command.sendTime)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            // 第三行：二进制码（TYPE + PAYLOAD 的十六进制，V1.10 没有 LEN）
            Text(
                text = "码 ${command.code.ifBlank { "-" }}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            if (answered) {
                Row {
                    Text(
                        text = "回复｜${command.reply}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF2E7D32)
                    )
                    // replyTime 可能为 null，用 ?.let 安全处理
                    command.replyTime?.let { replyTime ->
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = U.formatTime(replyTime),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Text(
                    text = "未收到回复",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFC62828)
                )
            }
        }
    }
}
