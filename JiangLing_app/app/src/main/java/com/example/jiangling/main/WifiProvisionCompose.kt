package com.example.jiangling.main

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.jiangling.data.MainViewModel
import com.example.jiangling.data.MainViewModel.ProvisionState
import com.example.jiangling.data.MainViewModel.WifiMode
import com.example.jiangling.util.M
import kotlinx.coroutines.delay

/**
 * ======================================================================
 *  WifiProvisionCard —— 给板子配网（唯一 WiFi 入口）
 * ======================================================================
 *
 *  【常态拓扑（手机开热点，板子连入）】
 *  1. 手机开热点 `jianglingAPP` / `31957172yz`，2.4GHz；
 *  2. 板子上电就用编译期默认凭据连这个热点（STA），App 也连在同一热点里；
 *  3. 双方同网段通信：命令 UDP 8081 下行、ACK/LOG/DAT 从 TCP 8080 回；
 *     主板地址由板子向 UDP 8082 广播 V21 `0x87` 自动发现（App 只监听），
 *     10 s 收不到才退到「手动填 IP」；**不是固定 192.168.4.1**。
 *
 *  【换网 → 兜底 AP（不是常态）】
 *  板子连不上默认网络约两分钟（20 s × 3 尝试 + 30 s × 3 退避），或 App 主动要求换网时，才短时开 AP `jianglingAPP`
 *  （预期网关 192.168.4.1）当配网口：
 *     `0xC4 [1]` → ACK 3 预检（仅 RAM）→ `0xC8 [1][0]` → ACK 0 离开 STA、重起 AP
 *     → ACK 3 AP 就绪 = 初始化完成；配完再回 STA。
 *  `0xCA` 是另一条独立入口：清 RAM 配网数据并重启，与「开 AP 换网」不是同一个按钮。
 *
 *  凭据只存在 App（板子只存 RAM），下发是手动动作，不做连上就自动补发。
 */
/** 配网卡片的三档：前两档对应协议 mode，第三档是兜底 AP（动作，不是凭据模式） */
private enum class ProvisionTab { HOTSPOT, ROUTER, FALLBACK_AP }

@Composable
fun WifiProvisionCard(viewModel: MainViewModel) {
    val status by viewModel.status.collectAsState()
    val saved by viewModel.savedCredential.collectAsState()
    val provision by viewModel.provisionState.collectAsState()
    val boardIp by viewModel.boardIp.collectAsState()

    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    // 首次进入用记忆值回填；改过的输入在旋转后保留
    var tab by rememberSaveable {
        mutableStateOf(
            if (saved?.mode == WifiMode.ROUTER) ProvisionTab.ROUTER else ProvisionTab.HOTSPOT
        )
    }
    var ssidInput by rememberSaveable {
        mutableStateOf(saved?.takeIf { it.mode == WifiMode.ROUTER }?.ssid.orEmpty())
    }
    var passwordInput by rememberSaveable {
        mutableStateOf(saved?.takeIf { it.mode == WifiMode.ROUTER }?.password.orEmpty())
    }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmFactoryReset by remember { mutableStateOf(false) }

    // 兜底 AP 档是「动作」，不下发凭据；凭据模式由前两档决定
    val credentialMode = if (tab == ProvisionTab.ROUTER) WifiMode.ROUTER else WifiMode.HOTSPOT

    val nameBytes = ssidInput.trim().toByteArray(Charsets.UTF_8).size
    val pwdBytes = passwordInput.toByteArray(Charsets.UTF_8).size
    // 热点模式用固定凭据，不需要校验输入
    val credentialError = when {
        tab != ProvisionTab.ROUTER -> null
        nameBytes == 0 -> null
        nameBytes !in 1..32 -> "SSID 需 1~32 字节"
        pwdBytes != 0 && pwdBytes !in 8..63 -> "密码需留空或 8~63 字节"
        1 + nameBytes + pwdBytes > 96 -> "名称 + 密码总长不能超过 96 字节"
        else -> null
    }
    val busy = provision is ProvisionState.Sending ||
        provision is ProvisionState.WaitingNet ||
        provision is ProvisionState.SwitchingToAp
    val canApply = tab != ProvisionTab.FALLBACK_AP && status.connected && !busy &&
        credentialError == null &&
        (tab == ProvisionTab.HOTSPOT || nameBytes > 0)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "给板子配网", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "常态是手机开热点「${MainViewModel.HOTSPOT_SSID}」" +
                    "（密码 ${MainViewModel.HOTSPOT_PASSWORD}，必须 2.4GHz）：板子上电会自动连上来，" +
                    "App 也连在这个热点里，双方同一网段走 UDP 8081（命令）/ TCP 8080（回程）。" +
                    "板子不是热点——它的 AP 只在换网或连不上默认网络时临时开一下。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            // ---- 模式档位：前两档下发凭据，第三档是兜底 AP（动作，不是凭据模式）----
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = tab == ProvisionTab.HOTSPOT,
                    onClick = { tab = ProvisionTab.HOTSPOT },
                    enabled = !busy,
                    label = { Text("手机热点") }
                )
                FilterChip(
                    selected = tab == ProvisionTab.ROUTER,
                    onClick = { tab = ProvisionTab.ROUTER },
                    enabled = !busy,
                    label = { Text("路由器") }
                )
                FilterChip(
                    selected = tab == ProvisionTab.FALLBACK_AP,
                    onClick = { tab = ProvisionTab.FALLBACK_AP },
                    enabled = !busy,
                    label = { Text("兜底 AP") }
                )
            }
            Spacer(Modifier.height(8.dp))

            if (tab == ProvisionTab.HOTSPOT) {
                HotspotGuide(
                    onCopy = { text -> clipboard.setText(AnnotatedString(text)) },
                    onOpenHotspotSettings = {
                        // 动作是「开手机热点 / 确认手机连在热点里」：先直达热点设置，
                        // 没有独立入口的 ROM 退回「无线和网络」页
                        val tether = Intent("android.settings.TETHER_SETTINGS")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        val wireless = Intent(Settings.ACTION_WIRELESS_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        if (runCatching { context.startActivity(tether) }.isFailure) {
                            runCatching { context.startActivity(wireless) }
                        }
                    }
                )
            } else if (tab == ProvisionTab.ROUTER) {
                Text(
                    text = "路由器档：板子以 STA 连这个路由器，凭据只存在 App（板子只存 RAM）。" +
                        "下发后板子会离开手机热点去连路由器，手机也要连到同一个路由器，" +
                        "命令通道才连得回去。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = ssidInput,
                    onValueChange = { ssidInput = it.take(32) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("路由器 SSID") },
                    singleLine = true,
                    enabled = !busy
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it.take(63) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("密码（开放网络留空）") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation =
                        if (passwordVisible) VisualTransformation.None
                        else PasswordVisualTransformation()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "显示密码",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = passwordVisible,
                        onCheckedChange = { passwordVisible = it }
                    )
                }
            } else {
                // ---- 兜底 AP：只做「让板子临时开 AP」，不是常态凭据模式 ----
                FallbackApGuide(
                    enabled = status.connected && !busy,
                    onOpenAp = { viewModel.returnToApForProvision() },
                    onFactoryReset = { confirmFactoryReset = true }
                )
            }

            if (credentialError != null) {
                Text(
                    text = credentialError,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(8.dp))

            // ---- 入口一：下发凭据（0xC5 + 0xC8 [0][0]，板子转 STA）----
            // 兜底 AP 档不下发凭据，按钮在 FallbackApGuide 里
            if (tab != ProvisionTab.FALLBACK_AP) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.applyCredential(credentialMode, ssidInput, passwordInput) },
                        enabled = canApply,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = if (busy) "配网中…" else "保存并下发", maxLines = 1)
                    }
                    TextButton(
                        onClick = { viewModel.resendCredential() },
                        enabled = saved != null && status.connected && !busy
                    ) { Text("重新下发", maxLines = 1) }
                }

                // ---- 入口二：临时开 AP 换网（0xC4[1] → ACK3 → 0xC8[1][0]，不重启、不清凭据）----
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { viewModel.returnToApForProvision() },
                        enabled = status.connected && !busy
                    ) { Text("让板子开 AP 换网", maxLines = 1) }
                    Spacer(Modifier.weight(1f))
                    // ---- 入口三：0xCA 清凭据并重启（和「开 AP 换网」明确分开）----
                    TextButton(
                        onClick = { confirmFactoryReset = true },
                        enabled = status.connected && !busy
                    ) { Text("清凭据并重启", maxLines = 1) }
                }
                Text(
                    text = "「让板子开 AP 换网」不重启、不清凭据，只是临时把板子切成热点当配网口，" +
                        "配完会回 STA；「清凭据并重启」会丢掉板子 RAM 里的全部配网数据并重启（0xCA）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ---- 下达结果：进行中 / 切 AP / 成功 / 失败原因 ----
            val (resultText, resultColor) = when (val s = provision) {
                ProvisionState.Idle ->
                    if (saved == null) "尚未保存配网信息" to MaterialTheme.colorScheme.onSurfaceVariant
                    else "" to Color.Unspecified

                is ProvisionState.Sending ->
                    "下发中，等待板子回执…" to MaterialTheme.colorScheme.onSurfaceVariant

                ProvisionState.SwitchingToAp ->
                    "板子正在临时切到 AP：本次连接会断开，属正常流程。请把手机连到它的临时热点" +
                        "「${MainViewModel.HOTSPOT_SSID}」，就绪后会自动确认…" to
                        MaterialTheme.colorScheme.onSurfaceVariant

                is ProvisionState.WaitingNet ->
                    "主板已受理，正在连「${s.ssid}」，最多 25 秒…" to
                        MaterialTheme.colorScheme.onSurfaceVariant

                is ProvisionState.Success ->
                    if (s.toAp) {
                        "板子已进入临时 AP：「${s.ssid}」已就绪，可重新配网（配完会回 STA）" to Color(0xFF2E7D32)
                    } else {
                        "配网成功：板子已连上「${s.ssid}」" to Color(0xFF2E7D32)
                    }

                is ProvisionState.Failed ->
                    "配网失败：${s.reason}" to MaterialTheme.colorScheme.error
            }
            if (resultText.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = resultText,
                    style = MaterialTheme.typography.labelSmall,
                    color = resultColor
                )
            }
            if (provision is ProvisionState.Failed) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { viewModel.resendCredential() },
                        enabled = saved != null && status.connected
                    ) { Text("重试") }
                    TextButton(onClick = { viewModel.resetProvisionState() }) { Text("关闭提示") }
                }
            }

            // 板子当前 WiFi 状态
            val stateText = when (status.wifiState) {
                null -> "尚未查询"
                0 -> "未连网络"
                1 -> "已连（无外网）"
                2 -> "已有外网"
                else -> "未知(${status.wifiState})"
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "板子当前：$stateText，IP ${status.wifiIp ?: "—"}，" +
                    "RSSI ${status.wifiRssi?.let { "$it dBm" } ?: "—"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!status.connected) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "WiFi 未连接：确认手机已开着热点「${MainViewModel.HOTSPOT_SSID}」" +
                        "（密码 ${MainViewModel.HOTSPOT_PASSWORD}），并且板子和 App 都在这个热点里；" +
                        "连上后自动建立命令通道（UDP 8081 + TCP 8080）。" +
                        "板子连不上时会等约两分钟再临时开 AP，可在「兜底 AP」档手动触发。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ---- 主板地址：广播自动发现，10 s 没收到就允许手填 ----
            BoardAddressRow(
                viewModel = viewModel,
                boardIp = boardIp,
                fallbackSuggestion = if (tab == ProvisionTab.FALLBACK_AP) M.BOARD_AP_IP else ""
            )
        }
    }

    // 0xCA 是破坏性操作：先确认，避免和「回 AP 换网」误触
    if (confirmFactoryReset) {
        AlertDialog(
            onDismissRequest = { confirmFactoryReset = false },
            title = { Text("清空板子配网数据并重启？") },
            text = {
                Text(
                    "会清掉板子 RAM 里的全部配网数据并重启（0xCA）。重启后板子先用默认凭据去连" +
                        "热点「${MainViewModel.HOTSPOT_SSID}」；连不上约两分钟后才会临时开 AP 配网。" +
                        "App 里记住的凭据也会一并清空。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmFactoryReset = false
                    viewModel.clearCredential()
                    viewModel.factoryReset()
                }) { Text("确认清除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmFactoryReset = false }) { Text("取消") }
            }
        )
    }
}

/**
 * 主板地址行：显示当前建链目标，并在「10 秒没收到广播」时放出「手动填 IP」入口。
 *
 * 地址优先来自板子的 UDP 8082 广播（V21 `0x87`），其次 TCP 上收到的 `0x87`，
 * 最后才是手填。手填值持久化，有广播后自动让位。
 */
@Composable
private fun BoardAddressRow(
    viewModel: MainViewModel,
    boardIp: String?,
    fallbackSuggestion: String
) {
    val manual by viewModel.manualBoardIp.collectAsState()
    var waited by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }

    // 有地址就不等；没地址等 MANUAL_IP_REVEAL_MS 才把入口露出来，避免一进来就催手填
    LaunchedEffect(boardIp) {
        if (boardIp == null) {
            waited = false
            delay(MANUAL_IP_REVEAL_MS)
            waited = true
        } else {
            waited = false
        }
    }

    Spacer(Modifier.height(6.dp))
    Text(
        text = "主板地址：${boardIp ?: "未发现（等待板子广播…）"}" +
            if (manual != null && boardIp == manual) "（手动）" else "",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (boardIp == null && !waited) {
        Text(
            text = "App 已在 UDP ${M.DISCOVERY_PORT} 监听板子的地址广播。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        TextButton(onClick = {
            input = manual ?: fallbackSuggestion
            editing = true
        }) { Text("手动填 IP") }
    }

    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("手动填写主板 IP") },
            text = {
                Column {
                    Text(
                        text = "一般不需要手填：板子连上网络后会向 UDP ${M.DISCOVERY_PORT} 广播自己的地址。" +
                            "只有热点不转发广播、或固件还没实现广播时才用这里。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it.trim().take(15) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("例如 192.168.43.12 或 ${M.BOARD_AP_IP}") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (viewModel.setManualBoardIp(input)) editing = false
                }) { Text("保存") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        viewModel.setManualBoardIp("")
                        editing = false
                    }) { Text("清除并等广播") }
                    TextButton(onClick = { editing = false }) { Text("取消") }
                }
            }
        )
    }
}

/** 「手动填 IP」入口从「未发现」到「露出」的等待时间 */
private const val MANUAL_IP_REVEAL_MS = 10_000L

/**
 * 「手机开热点、板子连入」引导区（默认档）：固定名称密码 + 复制 + 跳手机热点设置。
 *
 * 默认拓扑里板子是 STA：手机开热点 `jianglingAPP`，板子上电自动连入，
 * App 也连在同一热点，双方同网段走 UDP 8081 / TCP 8080。
 * 板子不是热点；它的 AP 只在换网 / 连不上默认网络时临时出现。
 */
@Composable
private fun HotspotGuide(onCopy: (String) -> Unit, onOpenHotspotSettings: () -> Unit) {
    Text(
        text = "默认档：手机开热点，板子上电自动连入。名称/密码用下面这组（必须 2.4GHz）；" +
            "开好热点后手机自己也连在这个热点里，App 才能和板子在同一网段互通。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "名称  ${MainViewModel.HOTSPOT_SSID}",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = { onCopy(MainViewModel.HOTSPOT_SSID) }) { Text("复制") }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "密码  ${MainViewModel.HOTSPOT_PASSWORD}",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = { onCopy(MainViewModel.HOTSPOT_PASSWORD) }) { Text("复制") }
    }
    Text(
        text = "频段必须选 2.4GHz（ESP32 不支持 5GHz，选 5G 板子会找不到这个热点）。",
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFFB26A00)
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = "板子不是热点：它的 AP 只在换网、或连续三次连不上默认网络（约两分钟）时临时开一下，" +
            "用来当配网口，配完就回 STA。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    TextButton(onClick = onOpenHotspotSettings) {
        Text("打开手机热点设置")
    }
}

/**
 * 兜底 AP 档：板子临时开 `jianglingAPP` 让 App 连，用于换网 / 连不上默认网络时救场。
 *
 * 入口是 `0xC4(1)` + `0xC8[1][0]`（不重启、不清凭据），`0xCA` 只负责清数据重启。
 * 这一档不是常态：常态是手机开热点、板子以 STA 连入。
 */
@Composable
private fun FallbackApGuide(
    enabled: Boolean,
    onOpenAp: () -> Unit,
    onFactoryReset: () -> Unit
) {
    Text(
        text = "兜底档：板子开 AP「${MainViewModel.HOTSPOT_SSID}」让 App 连，" +
            "仅用于换网或板子连不上默认网络时，不是常态。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = "这一档的预期网关是 ${M.BOARD_AP_IP}（只在临时 AP 期间成立）；" +
            "常态的主板地址以 0x87 上报为准，不写死。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = "时序：0xC4[1] 预检 → 0xC8[1][0] 离开 STA、重起 AP → ACK 3 AP 就绪。" +
            "期间连接会断开，属正常；手机要连到板子这个临时热点。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(onClick = onOpenAp, enabled = enabled) {
            Text("让板子开 AP", maxLines = 1)
        }
        TextButton(onClick = onFactoryReset, enabled = enabled) {
            Text("清凭据并重启", maxLines = 1)
        }
    }
}

/**
 * 麦克风占用状态（语音 ↔ AI 对话互斥）。
 *
 * 互斥由主板判定：语音首包一到达，主板自己把 AI 让出。
 * App 不提供 AI 开关，这里只如实显示「麦克风被谁占着」，避免出现两边同时可用的假象。
 */
@Composable
fun MicOccupancyCard(viewModel: MainViewModel) {
    val occupied by viewModel.micOccupied.collectAsState()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "麦克风占用", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (occupied) "语音通话占用中（AI 对话由主板自动让出）" else "空闲",
                style = MaterialTheme.typography.labelMedium,
                color = if (occupied) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "语音通话开始即独占麦克风，结束后释放；App 只上报占用状态，不做 AI 开关。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
