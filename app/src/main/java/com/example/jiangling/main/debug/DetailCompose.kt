package com.example.jiangling.main.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.jiangling.data.MainViewModel

/**
 * 详细数据页：从调试页进入，展示链路信息 + DAT 帧遥测（电量/电压/轮转速/线速度）。
 *
 * 【Compose 笔记：页面结构】
 * - 一进入页面就是「Column → 顶部返回栏 + Card」的纵向结构；
 * - 每一个 @Composable 都只负责一小块 UI，像积木一样组合起来；
 * - 想加一行数据：复制一个 MetricRow(...) + HorizontalDivider(...) 即可。
 */
@Composable
fun DetailCompose(viewModel: MainViewModel) {
    // 订阅合并后的小车状态（链路字段 + DAT 遥测），值变化时本函数自动重组
    val status by viewModel.status.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 顶部返回/标题已统一由共享 TopBar 承担，这里不再重复
        // ---------------- 数据卡片 ----------------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                MetricRow(
                    label = "连接状态",
                    value = if (status.connected) "已连接" else "未连接"
                )
                // HorizontalDivider 是横向分隔线，padding 控制上下留白
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                MetricRow(label = "设备名称", value = status.deviceName ?: "—")
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                MetricRow(
                    label = "电量",
                    value = if (status.battery > 0) "%.1f%%".format(status.battery) else "—"
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                MetricRow(
                    label = "电压",
                    value = if (status.batteryVoltage > 0) "%.2f V".format(status.batteryVoltage) else "—"
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                MetricRow(
                    label = "左 / 右轮转速",
                    value = "%.2f / %.2f r/s".format(status.wheelLeftRps, status.wheelRightRps)
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                MetricRow(label = "线速度", value = "%.2f m/s".format(status.speed))
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                MetricRow(label = "运行状态", value = status.runStatus)
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = "电量由小车上报的电压经 App 曲线换算（小车只交原始电压）；线速度由左右轮转速平均后按 v = r/s × π × 0.068m 换算。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 一行“标签 + 数值”。
 * - label 用 weight(1f) 占满左侧剩余空间，数值自然被推到最右边；
 * - 想改对齐方式：把 weight 放到 value 上，会让标签靠左、数值紧跟其后。
 */
@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}
