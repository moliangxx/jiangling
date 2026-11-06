package com.example.jiangling.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.jiangling.data.MainViewModel
import com.example.jiangling.ble.BleDevice
import com.example.jiangling.ble.ConnectionState
import com.example.jiangling.util.M
import com.example.jiangling.util.U

/*
 * ======================================================================
 *  调试页（DebugScreen）——功能聚合页
 * ======================================================================
 *
 *  布局结构（从上到下，整页可滚动）：
 *  ┌─────────────────────────────────────────────┐
 *  │  蓝牙连接卡片：当前状态 + 单个「扫描连接/断开连接」按钮 │
 *  ├─────────────────────────────────────────────┤
 *  │  发送命令卡片：TYPE(HEX) + 参数(DEC) + 发送按钮   │
 *  ├─────────────────────────────────────────────┤
 *  │  入口卡片组：                                │
 *  │    ▶ 详细数据     ▶ 命令记录     ▶ 运行日志  │
 *  └─────────────────────────────────────────────┘
 *
 *  弹窗：点"扫描连接" → AlertDialog + LazyColumn 设备列表
 *        Dialog 打开时自动开始扫描，关闭时自动停止扫描
 *
 *  【Compose 这里用到的进阶技巧】
 *
 *  1) verticalScroll(rememberScrollState())
 *     让整页内容可滚动，适合内容可能超过屏幕高度的页面。
 *     用 Column + verticalScroll 包裹所有子元素即可。
 *
 *  2) var showDeviceDialog by remember { mutableStateOf(false) }
 *     弹窗开关：一个布尔值控制 AlertDialog 是否显示。
 *     ✘ 不要用 mutableStateOf 不带 remember——重组时会重置状态！
 *     ✔ remember 把状态保存到 Composition 里，重组不丢。
 *
 *  3) if (showDeviceDialog) { DeviceDialog(...) }
 *     Compose 里条件渲染就是 if/ when 直接套 Composable。
 *     和 HTML 里的 v-if / ng-if 一个意思，组件会被真正销毁/创建。
 *
 *  4) LazyColumn = RecyclerView 的 Compose 版
 *     只创建屏幕内可见的条目，列表上千条也不卡。
 *     items(list, key = { it.id }) { item -> ItemRow(item) }
 *     key 是条目唯一标识，帮助 Compose 判断谁变了、谁可以复用。
 *
 *  5) OutlinedTextField 输入过滤
 *     onValueChange = { typeInput = it.take(6) }
 *     输入框只截断长度，合法性交给 validateTypeInput / validateParamInput，
 *     不合法时 isError=true 标红并且不发出去。
 *     这样用户输到 0x1FF 能明确看到"TYPE 超出 0xFF"，而不是被悄悄截断。
 *
 *  6) LaunchedEffect(Unit) { onScan() }
 *     Effect key = Unit：每次 Dialog 进入组合时执行一次（相当于 onStart）
 *     Dialog 关闭 → Composable 销毁 → Effect 取消 → 协程自动停
 * ======================================================================
 */

/**
 * 调试页根 Composable。
 *
 * @param viewModel 全局 ViewModel
 * @param onOpenDetail 点"详细数据"入口 → JiangLingApp 会切到 DETAIL 路由
 * @param onOpenLogs 点"运行日志"入口 → 切到 LOGS
 * @param onOpenCommands 点"命令记录"入口 → 切到 COMMANDS
 */
@Composable
fun DebugScreen(
    viewModel: MainViewModel,
    onOpenDetail: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenCommands: () -> Unit
) {
    val context = LocalContext.current

    // ======== 从 ViewModel 读状态 ========
    val status by viewModel.status.collectAsState()
    // 连接三态（未连接 / 已连接 / 重连中）直接读 ViewModel，用于按钮文字切换
    val connectionState by viewModel.connectionState.collectAsState()
    val devices by viewModel.devices.collectAsState()
    val scanning by viewModel.scanning.collectAsState()
    val message by viewModel.message.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val commands by viewModel.commands.collectAsState()

    // ======== 本地 UI 状态 ========
    // 这几个状态只在 DebugScreen 内部用，不放 ViewModel
    var showDeviceDialog by remember { mutableStateOf(false) }
    // V1.10：0xD0 = CMD_AUDIO_TEST（分区 1 音频 / 类型 0），参数 1 = 1 kHz 测试音
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

        // ==================== 1. 蓝牙连接卡片 ====================
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "蓝牙连接",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (status.connected) {
                        "已连接：${status.deviceName.orEmpty()}（MTU=${status.mtu}）"
                    } else {
                        "未连接，请扫描并选择小车"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))

                // 单个连接按钮：
                // - 未连接：点"扫描连接" → 打开弹窗扫描并主动连接
                // - 已连接 / 重连中：点"断开连接" → 断开并抑制后台自动重连
                // 重连本身由系统后台发起，界面不提供单独触发入口。
                val hasConnection = connectionState == ConnectionState.CONNECTED ||
                    connectionState == ConnectionState.RECONNECTING
                Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            if (hasConnection) {
                                viewModel.disconnect()
                            } else {
                                showDeviceDialog = true
                            }
                        }
                    ) {
                        Text(if (hasConnection) "断开连接" else "扫描连接")
                    }
                }
            }
        }

        // ==================== 2. 发送命令卡片 ====================
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {

                // 两个输入框并排：左边 TYPE（十六进制），右边参数（十进制）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                // 固件未实现的命令就地提示，避免误以为能控制
                val unimplementedHint = when (parsedKindType) {
                    M.CMD_LEFT_SHIFT, M.CMD_RIGHT_SHIFT -> "固件未实现横移，仅回「无法识别」"
                    M.CMD_REQ_RECORD -> "固件未实现该请求，仅回「无法识别」"
                    else -> null
                }
                if (unimplementedHint != null) {
                    Text(
                        text = unimplementedHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))

                // 发送按钮（右侧对齐）
                Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            val kindType = parsedKindType
                            // 长度由 TYPE 长度表决定（M.cmdLen / M.cmdPayload），用户不填 LEN
                            if (kindType != null && typeError == null && paramError == null) {
                                viewModel.sendCommand(kindType, paramValue)
                            }
                        },
                        enabled = status.connected && canSend
                    ) {
                        Text("发送")
                    }
                }
            }
        }

        // ==================== 3. 三个入口卡片 ====================
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
            title = "运行日志",
            subtitle = "共 ${logs.size} 条记录",
            onClick = onOpenLogs
        )
    }

    // ==================== 蓝牙设备扫描弹窗 ====================
    // showDeviceDialog = true 时渲染 AlertDialog
    if (showDeviceDialog) {
        DeviceDialog(
            devices = devices,
            scanning = scanning,
            onScan = { viewModel.startScan() },
            onSelect = {
                viewModel.connect(it.address)
                showDeviceDialog = false
            },
            onDismiss = {
                viewModel.stopScan()
                showDeviceDialog = false
            }
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

/**
 * 蓝牙扫描设备弹窗。
 *
 * 【生命周期】
 * - 弹窗打开 → LaunchedEffect(Unit) 触发一次扫描
 * - 弹窗关闭 → onDismiss 调 stopScan，扫描自动停
 * - 用户点"重新扫描" → onScan 被重新调用
 *
 * 【为什么用 LaunchedEffect(Unit)】
 * key = Unit 意味着"只要这个 Composable 在组合里就跑一次"。
 * Dialog 打开时 Unit 没变，但 effect 是第一次执行。
 * Dialog 关闭后 Composable 销毁、Effect 取消，扫描也自动停了。
 *
 * @param devices 扫描到的设备列表（从 ViewModel 读）
 * @param scanning 当前是否正在扫描
 * @param onScan 开始扫描
 * @param onSelect 选中一个设备 → 连接
 * @param onDismiss 关闭弹窗 → 停止扫描
 */
@Composable
private fun DeviceDialog(
    devices: List<BleDevice>,
    scanning: Boolean,
    onScan: () -> Unit,
    onSelect: (BleDevice) -> Unit,
    onDismiss: () -> Unit
) {
    // 进入弹窗立刻触发一次扫描
    LaunchedEffect(Unit) { onScan() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择小车蓝牙设备") },
        text = {
            Column {
                Text(
                    text = if (scanning) "正在扫描附近蓝牙设备…" else "扫描已结束",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))

                if (devices.isEmpty()) {
                    Text(
                        text = "未发现设备",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // LazyColumn 放在 Dialog 里，用 heightIn 限制最大高度
                    // 否则设备太多会把 Dialog 撑出屏幕
                    LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                        items(devices, key = { it.address }) { device ->
                            TextButton(
                                onClick = { onSelect(device) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(text = device.name)
                                    Row {
                                        Text(
                                            text = device.address,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "RSSI ${device.rssi}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = { TextButton(onClick = onScan) { Text("重新扫描") } }
    )
}

// ======================================================================
//  手动命令输入校验（校验通过才允许发送）
// ======================================================================

/**
 * 解析 TYPE 输入，返回 (KIND_TYPE, 错误提示)。
 *
 * - 接受 `D0` / `0xD0` 两种写法，忽略大小写与 `0x` 前缀；
 * - V1.10 的 TYPE 三段布局：KIND(bit7-6) + 分区(bit5-4) + 类型(bit3-0)，
 *   调试页只允许发送 **CMD**（`0xC0~0xFF`），取低 6 bit 作为 KIND_TYPE；
 * - `0x00~0xBF` 属于 ACK / LOG / DAT，不是 CMD，拒绝发送；
 * - 未登记在 V1.10 长度表内的 CMD TYPE 也拒绝发送（没有 LEN 可猜，整帧长度未知）；
 * - 超出 `0xFF` 报错。
 */
private fun validateTypeInput(raw: String): Pair<Int?, String?> {
    val text = raw.trim()
    if (text.isEmpty()) return null to "请输入 TYPE"
    val value = text.removePrefix("0x").removePrefix("0X").trim().toIntOrNull(16)
        ?: return null to "请输入十六进制，如 D0"
    if (value > 0xFF) return null to "TYPE 超出 0xFF"
    val kindType = M.parseCmdKindType(value)
    return if (kindType == null) {
        null to "该 TYPE 不是已登记的 CMD（0xC0~0xFF）"
    } else {
        kindType to null
    }
}

/**
 * 解析参数输入：只允许十进制 0~255 的整数。
 * 长度为 0 的命令（CMD_STOP / CMD_REQ_RECORD / CMD_REQ_INFO）参数不参与组装，留空也合法；
 * 其余命令按 [M.cmdLen] 查表，参数固定 1 字节。
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
