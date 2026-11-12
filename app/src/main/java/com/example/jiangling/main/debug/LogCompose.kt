package com.example.jiangling.main.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.jiangling.data.MainViewModel
import com.example.jiangling.data.log.Log
import com.example.jiangling.util.M
import kotlin.math.abs
import com.example.jiangling.util.U



/**
 * 日志页入口。
 *
 * @param viewModel 全局 ViewModel（列表和日期列表从这里读）
 * @param onBack    保留参数：返回动作已由顶部共享标题栏的返回箭头处理，
 *                  这里保留是为了和其他二级页面签名统一，以后要在页面内加返回按钮可直接用。
 */
@Composable
fun LogCompose(viewModel: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current

    // ======== 页面自己持有的"筛选条件" ========
    // 这些条件只影响本页面显示，没必要放进 ViewModel。
    // ViewModel 里只有"当前显示的列表"，条件变了就再调一次查询函数。
    var sortAsc by remember { mutableStateOf(false) }                                // 排序方向
    var time by remember { mutableStateOf(viewModel.firstConnectTime) }             // 基准时间点
    var isAfter by remember { mutableStateOf(true) }                                // true=之后，false=之前
    var label by remember { mutableStateOf("当前") }                                // 查询文字
    var showDateDialog by remember { mutableStateOf(false) }                        // 日期弹窗开关
    var levelFilter by remember { mutableStateOf(M.LOG_LEVEL_DEBUG) }                // 等级筛选，默认 D
    var showLevelMenu by remember { mutableStateOf(false) }                         // 等级下拉开关

    // ======== 从 ViewModel 读数据 ========
    val logs by viewModel.logs.collectAsState()
    val dates by viewModel.logDates.collectAsState()
    val message by viewModel.message.collectAsState()

    // 当前等级下的日志；等级只做本地筛选，时间/排序仍由 ViewModel 查询
    val displayedLogs = remember(logs, levelFilter) {
        logs.filter { it.level.equals(levelFilter, ignoreCase = true) }
    }

    // 时间、方向、排序变化时重新查询（日志页首次进入也走这里）
    LaunchedEffect(time, isAfter, sortAsc) {
        viewModel.queryLogs(time, isAfter, sortAsc)
    }


    // ======== Toast ========
    // ⚠ LocalContext.current 是 Composable 调用，只能在外层取，不能写进 LaunchedEffect 里面。
    LaunchedEffect(message) {
        message?.let {
            U.t(context, it)
            viewModel.consumeMessage()
        }
    }


    Column( //column = LinearLayout+vertical
        modifier = Modifier
            .fillMaxSize() //match_parent,height+width
            .padding(horizontal = 16.dp, vertical = 12.dp)//写在column里面 = padding,外面 = layout_margin
    ) {
        // 菜单栏：降序 / 等级筛选 / 查询 / 清空，全部排在同一行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {

            // ---- ① 排序按钮：降序 ⇄ 升序 ----
            TextButton(
                onClick = { sortAsc = !sortAsc },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (sortAsc) "升序 ↑" else "降序 ↓",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // ---- ② 等级筛选：默认 D，点开选 D / E / W / I（与线路取值 0/1/2/3 一致）----
            Box {
                TextButton(
                    onClick = { showLevelMenu = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "等级：$levelFilter ▾",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                DropdownMenu(
                    expanded = showLevelMenu,
                    onDismissRequest = { showLevelMenu = false }
                ) {
                    listOf(
                        M.LOG_LEVEL_DEBUG,
                        M.LOG_LEVEL_ERROR,
                        M.LOG_LEVEL_WARNING,
                        M.LOG_LEVEL_INFO
                    ).forEach { level ->
                        DropdownMenuItem(
                            text = { Text("$level  ${M.logLevelText(level)}") },
                            onClick = {
                                levelFilter = level
                                showLevelMenu = false
                            }
                        )
                    }
                }
            }

            // ---- ③ 查询：单行显示当前范围 ----
            TextButton(
                onClick = { showDateDialog = true },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "查询：$label",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // ---- ④ 清空当前：只删当前列表（参数由本页面传） ----
            TextButton(
                onClick = { viewModel.deleteLogs(time, isAfter) },
                enabled = displayedLogs.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(text = "清空当前", style = MaterialTheme.typography.labelLarge)
            }
        }

        Spacer(Modifier.height(10.dp))//view:layout_width = 10dp,layout_height = wrap_content,高度占位，也等效下面控件layout_marginTop = 10dp

        // ==================== 列表 / 空状态 ====================
        if (displayedLogs.isEmpty()) {
            // Box.fillMaxSize + contentAlignment.Center = 在剩余空间里居中显示提示
            Box(//FragmeLayout
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center//fragmeLayout+gravity = center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally//LinearLayout+vertival+center
                ) {
                    Text(
                        text = "暂无日志",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "默认只显示最近一段时间的记录，更早的点「查询」按日期查看",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(//recyclerView
                modifier = Modifier.fillMaxSize(),//match_parent
                verticalArrangement = Arrangement.spacedBy(8.dp),//每条之间间距
                contentPadding = PaddingValues(bottom = 12.dp)//paddingBottom = 12dp
            ) {
                // key = it.id：数据库主键，保证每条日志身份唯一，增删时不闪烁
                items(displayedLogs, key = { it.id }) { log ->
                    LogItem(log)
                }
            }
        }
    }

    //查询窗口
    if (showDateDialog) {
        DateQueryDialog(
            dates = dates,
            onQuery = { date, after ->
                // 日期 → 时间点由 U.parseDateStart 算好，页面只负责换条件
                U.parseDateStart(date)?.let { dayStart ->
                    time = dayStart
                    isAfter = after
                    label = "$date ${if (after) "后" else "前"}"
                }
                showDateDialog = false
            },
            onReset = {
                // 恢复默认：时间回到当前（firstConnectTime），方向=之后，排序不动
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
//  单条日志
// ======================================================================

/**
 * 单条日志卡片。
 * 左边是等级色块（D / E / W / I），右边是时间、等级文字和内容。
 */
@Composable
private fun LogItem(log: Log) {
    val level = log.level.uppercase()
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // 等级色块：size + clip + background 三件套（顺序不能乱）
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(logLevelColor(level)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = level,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = U.formatTime(log.time),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = M.logLevelText(level),
                        style = MaterialTheme.typography.labelSmall,
                        color = logLevelColor(level)
                    )
                }
                Text(
                    text = log.content,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/**
 * 日志等级 → 颜色。
 * 想调整"警告橙 / 错误红 / 普通灰蓝"，直接改这里的 Color(0x...)。
 */
private fun logLevelColor(level: String): Color = when (level) {
    M.LOG_LEVEL_INFO -> Color(0xFF2E7D32)
    M.LOG_LEVEL_WARNING -> Color(0xFFEF6C00)
    M.LOG_LEVEL_ERROR -> Color(0xFFC62828)
    else -> Color(0xFF546E7A)
}

// ======================================================================
//  按时间查询弹窗
// ======================================================================

/**
 * 日期选择弹窗。
 *
 * 注意：这个弹窗是【日志页和命令页共用】的（所以用 internal 而不是 private），
 * 命令页直接 import 过来复用，保证两个页面的时间查询体验完全一致。
 * 想改日期选择交互，只改这一处，两个页面同时生效。
 *
 * 【交互流程】
 *  1) 左边是日期滚轮：只列出"数据库里真的有日志"的那几天，滑动时中间那项变黑变大
 *  2) 右边是"前 / 后"滚轮：同样滑动选择，中间那项是选中状态
 *  3) 底部两个按钮（AlertDialog 自带左右位置）：
 *       「恢复默认」→ 时间回到当前（firstConnectTime），方向=后，排序不动
 *       「确认」    → 按 日期 + 前/后 去查询
 *  4) 点弹窗外/返回键 = 关闭，不改变任何条件
 *
 * 【为什么选择项要用 remember 而不是 ViewModel】
 * 「弹窗里临时选了哪一天」属于纯 UI 的中间状态，
 * 弹窗一关就没意义了，没必要提升到 ViewModel。
 *
 * @param dates     有日志的日期列表（"yyyy-MM-dd"，倒序）
 * @param onQuery   (日期, 是否查之后) → 交给页面换查询条件
 * @param onReset   恢复默认查询
 * @param onDismiss 关闭弹窗
 */
@Composable
internal fun DateQueryDialog(
    dates: List<String>,
    onQuery: (date: String, after: Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    // 弹窗内的临时选择（关掉就丢掉，不需要放 ViewModel）
    var dateIndex by remember { mutableStateOf(0) }   // 滚轮停在哪个日期
    var after by remember { mutableStateOf(true) }    // true=后，false=前

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("按时间查询") },
        text = {
            if (dates.isEmpty()) {
                Text(
                    text = "还没有任何日志日期",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // 左边日期滚轮 + 右边"前/后"滚轮，两个都用同一套 WheelPicker
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WheelPicker(
                        items = dates,
                        startIndex = dateIndex,
                        onSelected = { dateIndex = it },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(12.dp))
                    WheelPicker(
                        items = listOf("前", "后"),
                        startIndex = if (after) 1 else 0,
                        onSelected = { after = it == 1 },
                        modifier = Modifier.width(64.dp)
                    )
                }
            }
        },
        // 左"恢复默认"，右"确认"——AlertDialog 自带这个位置关系
        confirmButton = {
            TextButton(
                enabled = dates.isNotEmpty() && dates.getOrNull(dateIndex) != null,
                onClick = { dates.getOrNull(dateIndex)?.let { onQuery(it, after) } }
            ) {
                Text("确认")
            }
        },
        dismissButton = {
            TextButton(onClick = onReset) { Text("恢复默认") }
        }
    )
}

// ======================================================================
//  滑动选择器（日期滚轮 / 前-后滚轮共用）
// ======================================================================

/** 滚轮里每一行的高度、以及可视区高度（3 行，上下各留 1 行做"变灰变小"的过渡） */
private val WheelRowHeight = 52.dp
private val WheelPickerHeight = WheelRowHeight * 3

/**
 * 滑动选择器（仿 iOS 滚轮）。
 *
 * 【视觉规则】
 *   停在中间的那一项：字号最大（20sp）、加粗、颜色最深（onSurface = 黑色）
 *   相邻的那一项：   字号 15sp、灰色（alpha 0.55）
 *   再远的：         字号 13sp、更灰（alpha 0.35）
 * 因为是按"距离中心的行数"算样式，所以滑动过程中会连续地变大变小，不会跳。
 *
 * 【怎么判断哪一项被选中】
 *   LazyColumn 的 layoutInfo 里有每一项的位置和尺寸，
 *   视口中心 = (viewportStart + viewportEnd) / 2，
 *   谁的中心离这个点最近，谁就是"中间那一项"。
 *   首项能被选中是因为加了 contentPadding（上下各一行），等于给列表留了"空位"。
 *
 * 【startIndex】
 *   打开弹窗时的初始选中项。用 scrollToItem(index, 一行的高度) 把目标行摆到中间。
 *
 * @param items       要选择的文字列表
 * @param startIndex  初始停在哪个索引
 * @param onSelected  中间项变化时回调（滑动过程中会连续回调，直接赋值即可）
 */
@Composable
private fun WheelPicker(
    items: List<String>,
    startIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val snap = rememberSnapFlingBehavior(lazyListState = listState)
    val density = LocalDensity.current
    val rowPx = with(density) { WheelRowHeight.toPx() }

    // 首次进入：把 startIndex 摆到中间那行（offset 传一行高 = 往下挪一行）
    LaunchedEffect(items) {
        if (items.isNotEmpty()) {
            listState.scrollToItem(startIndex.coerceIn(0, items.lastIndex), rowPx.toInt())
        }
    }

    // "中间那一项"的索引：滑动时实时计算
    val centerIndex by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            if (info.visibleItemsInfo.isEmpty()) return@derivedStateOf 0
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo
                .minByOrNull { abs(it.offset + it.size / 2 - center) }
                ?.index ?: 0
        }
    }

    // 中间项一变就通知外面（确认按钮用这个索引）
    LaunchedEffect(centerIndex) { onSelected(centerIndex) }

    LazyColumn(
        state = listState,
        flingBehavior = snap,
        modifier = modifier.height(WheelPickerHeight),
        horizontalAlignment = Alignment.CenterHorizontally,
        // 上下各留一行空白，保证第一项/最后一项也能停到中间
        contentPadding = PaddingValues(vertical = WheelRowHeight)
    ) {
        items(items.size) { index ->
            val distance = abs(index - centerIndex)
            val isCenter = distance == 0
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(WheelRowHeight),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = items[index],
                    fontSize = when {
                        isCenter -> 20.sp
                        distance == 1 -> 15.sp
                        else -> 13.sp
                    },
                    fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        isCenter -> MaterialTheme.colorScheme.onSurface
                        distance == 1 -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    },
                    maxLines = 1,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
