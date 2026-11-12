package com.example.jiangling.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.jiangling.data.MainViewModel
import androidx.compose.ui.graphics.Color
import com.example.jiangling.util.M
import com.example.jiangling.util.U

/*
 * ======================================================================
 *  调试页（DebugScreen）——功能聚合页
 * ======================================================================
 *
 *  布局结构（从上到下，整页可滚动）：
 *  ┌─────────────────────────────────────────────┐
 *  │  命令通道卡片：WiFi 已连接 / 未连接 + 主板 IP   │
 *  ├─────────────────────────────────────────────┤
 *  │  给板子配网卡片（全程 WiFi）                   │
 *  ├─────────────────────────────────────────────┤
 *  │  发送命令卡片：TYPE(HEX) + 参数(DEC) + 发送按钮   │
 *  ├─────────────────────────────────────────────┤
 *  │  入口卡片组：                                │
 *  │    ▶ 详细数据     ▶ 命令记录     ▶ 运行日志  │
 *  └─────────────────────────────────────────────┘
 *
 *  【Compose 这里用到的进阶技巧】
 *
 *  1) verticalScroll(rememberScrollState())
 *     让整页内容可滚动，适合内容可能超过屏幕高度的页面。
 *
 *  2) OutlinedTextField 输入过滤
 *     onValueChange = { typeInput = it.take(6) }
 *     输入框只截断长度，合法性交给 validateTypeInput / validateParamInput，
 *     不合法时 isError=true 标红并且不发出去。
 * ======================================================================
 */

/**
 * 调试页根 Composable。
 *
 * @param viewModel 全局 ViewModel
 * @param onOpenDetail 点"详细数据"入口 → JiangLingApp 会切到 DETAIL 路由
 * @param onOpenLogs 点"运行日志"入口 → 切到 LOGS
 * @param onOpenCommands 点"命令记录"入口 → 切到 COMMANDS
 * @param onOpenCmdView 点"命令查看"入口 → 切到 CMD_VIEW
 */
@Composable
fun DebugScreen(
    viewModel: MainViewModel,
    onOpenDetail: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenCommands: () -> Unit,
    onOpenCmdView: () -> Unit
) {
    val context = LocalContext.current

    // ======== 从 ViewModel 读状态 ========
    val status by viewModel.status.collectAsState()
    // 命令通道两态（WiFi 已连接 / 未连接）直接读 ViewModel
    val channel by viewModel.commandChannel.collectAsState()
    val boardIp by viewModel.boardIp.collectAsState()
    val message by viewModel.message.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val commands by viewModel.commands.collectAsState()
    val lastAck by viewModel.lastAck.collectAsState()

    // ======== 本地 UI 状态 ========
    // 这几个状态只在 DebugScreen 内部用，不放 ViewModel
    // 0xD0 = CMD_AUDIO_TEST（分区 1 音频 / 类型 0），参数 1 = 1 kHz 测试音
    var typeInput by remember { mutableStateOf("D0") }
    var paramInput by remember { mutableStateOf("1") }

    // TYPE 十六进制、参数十进制；校验通过才允许发送
    val (parsedKindType, typeError) = validateTypeInput(typeInput)
    val (paramValue, paramError) = validateParamInput(
        paramInput,
        parsedKindType?.let { M.cmdLen(it) }
    )
    val canSend = parsedKindType != null && typeError == null && paramError == null

    // ======== Toast ========
    LaunchedEffect(message) {
        message?.let {
            U.t(context, it)
            viewModel.consumeMessage()
        }
    }

    // ======== 主布局：可滚动 Column ========
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())  // 整页可滚动
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        // ==================== 1. 命令通道卡片 ====================
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "命令通道：${channel.label}",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "WiFi 通道（UDP ${M.CMD_PORT} 下行 + TCP ${M.CTRL_PORT} 回程）" +
                        (boardIp?.let { "；主板 $it" } ?: "；板子地址待 0x87 上报"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!status.connected) {
                    Text(
                        text = "手机开热点 ${MainViewModel.HOTSPOT_SSID}（板子也连它）：连上后自动建立命令通道；" +
                            "板子地址以 0x87 上报为准",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ==================== 2. 给板子配网（唯一 WiFi 入口） ====================
        WifiProvisionCard(viewModel = viewModel)

        // ==================== 2.1 麦克风占用（语音 ↔ AI 对话互斥） ====================
        MicOccupancyCard(viewModel = viewModel)

        // ==================== 3. 发送命令卡片 ====================
        // 边框内部左上角是「命令」标签，下方仅一行：两个输入框 + 圆形发送按钮
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {

                Text(
                    text = "命令",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = typeInput,
                        onValueChange = { typeInput = it.take(6) },
                        modifier = Modifier.weight(1f),
                        label = { Text("TYPE（HEX）") },
                        singleLine = true,
                        isError = typeError != null
                    )
                    OutlinedTextField(
                        value = paramInput,
                        onValueChange = { paramInput = it.take(3) },
                        modifier = Modifier.weight(1f),
                        label = { Text("参数（DEC）") },
                        singleLine = true,
                        isError = paramError != null
                    )
                    FilledIconButton(
                        onClick = {
                            val kindType = parsedKindType
                            // LEN 由 TYPE 允许范围 + 实际 PAYLOAD 长度决定，用户不填 LEN
                            if (kindType != null && typeError == null && paramError == null) {
                                viewModel.sendCommand(kindType, paramValue)
                            }
                        },
                        enabled = canSend && channel == MainViewModel.CommandChannel.WIFI,
                        modifier = Modifier.size(48.dp),
                        shape = CircleShape
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "发送"
                        )
                    }
                }
                // 输入不合法就地标红提示（两种错误最多各一行）
                if (typeError != null) {
                    Text(
                        text = typeError,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (paramError != null) {
                    Text(
                        text = paramError,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                // 就地回执：发出后不用再翻「命令记录」才知道板子怎么答的
                lastAck?.let { ack ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "最近回执：SEQ ${ack.seq} · ${M.cmdName(ack.kindType)} · ${ack.statusText}",
                        style = MaterialTheme.typography.labelSmall,
                        color = when (M.ackLevel(ack.status)) {
                            0 -> Color(0xFF2E7D32)
                            1 -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> Color(0xFFC62828)
                        }
                    )
                }
            }
        }

        // 4. WiFi 配置卡片（0xC4~0xC7）已下线：模式/凭据/查询统一收敛到上面的「给板子配网」

        // ==================== 5. 入口卡片 ====================
        EntryCard(
            title = "详细数据",
            subtitle = "电量、速度、运行状态",
            onClick = onOpenDetail
        )
        EntryCard(
            title = "命令记录",
            subtitle = "共 ${commands.size} 条命令",
            onClick = onOpenCommands
        )
        EntryCard(
            title = "命令查看",
            subtitle = "协议命令速查（TYPE / DEC）",
            onClick = onOpenCmdView
        )
        EntryCard(
            title = "运行日志",
            subtitle = "共 ${logs.size} 条记录",
            onClick = onOpenLogs
        )
    }
}

// ======================================================================
//  调试页内部子 Composable
// ======================================================================

/**
 * 通用入口卡片：左边标题+描述，右边一个"›"箭头。
 * 点击整块触发 onClick。
 */
@Composable
private fun EntryCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)   // 整块可点
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "›",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ======================================================================
//  手动命令输入校验（校验通过才允许发送）
// ======================================================================

/**
 * 解析 TYPE 输入，返回 (KIND_TYPE, 错误提示)。
 *
 * - 接受 `D0` / `0xD0` 两种写法，忽略大小写与 `0x` 前缀；
 * - TYPE 三段布局：KIND(bit7-6) + 分区(bit5-4) + 类型(bit3-0)，
 *   调试页只允许发送 **CMD**（`0xC0~0xFF`），取低 6 bit 作为 KIND_TYPE；
 * - `0x00~0xBF` 属于 ACK / LOG / DAT，不是 CMD，拒绝发送；
 * - 未登记在协议 21 长度范围表（[M.payloadLenRange]）内的 CMD TYPE 拒绝发送；
 * - `0xC5/0xC6` 是变长凭据命令，本卡片无法表达，提示改用下方 WiFi 配置卡片；
 * - 超出 `0xFF` 报错。
 */
private fun validateTypeInput(raw: String): Pair<Int?, String?> {
    val text = raw.trim()
    if (text.isEmpty()) return null to "请输入 TYPE"
    val value = text.removePrefix("0x").removePrefix("0X").trim().toIntOrNull(16)
        ?: return null to "请输入十六进制，如 D0"
    if (value > 0xFF) return null to "TYPE 超出 0xFF"
    if (value in 0xC0..0xFF) {
        val kindType = value and 0x3F
        if (M.cmdLenRange(kindType) == null) return null to "该 TYPE 不是已登记的 CMD（0xC0~0xFF）"
        if (M.cmdLen(kindType) < 0) return null to "变长凭据命令请用下方 WiFi 配置卡片发送"
        return kindType to null
    }
    return null to "该 TYPE 不是已登记的 CMD（0xC0~0xFF）"
}

/**
 * 解析参数输入：只允许十进制 0~255 的整数。
 * 长度为 0 的命令（CMD_STOP / CMD_REQ_RECORD / CMD_REQ_INFO / CMD_WIFI_STATUS）参数不参与组装，留空也合法；
 * 其余命令按 [M.cmdLen] 取唯一的 PAYLOAD 长度，参数固定 1 字节。
 */
private fun validateParamInput(raw: String, expectedLen: Int?): Pair<Int, String?> {
    val text = raw.trim()
    if (text.isEmpty()) {
        return if (expectedLen == 0) 0 to null else 0 to "请输入参数（0~255）"
    }
    val value = text.toIntOrNull() ?: return 0 to "参数必须是十进制整数"
    if (value !in 0..255) return 0 to "参数超出 0~255"
    return value to null
}
