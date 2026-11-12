package com.example.jiangling.main.cmdview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * 命令速查表的一条记录。
 *
 * 数据来源：`index/江灵通信协议/命令.md`，并只保留 App 侧已登记/已处理的命令。
 * TYPE 用十六进制，DEC 列用十进制。
 */
private data class CommandInfo(
    val function: String,
    val zone: String,
    val typeHex: String,
    val decValue: String,
    val decNote: String,
    val special: String
)

/**
 * App 已实现的命令清单，共 20 条。
 *
 * 未收录：`0xEB CMD_MOVE_VECTOR`（摇杆向量运动）——协议文档已定义，
 * 但 App 没有对应常量、组装与发送逻辑，固件也尚未实现，因此不列入。
 */
private val commands = listOf(
    CommandInfo(
        function = "会话初始化",
        zone = "系统",
        typeHex = "0xC0",
        decValue = "21",
        decNote = "协议版本号（PROTOCOL_VERSION）",
        special = "SEQ=0、免去重；每次新会话第一帧；应答 ACK_SESSION_INIT，版本不相等回 5"
    ),
    CommandInfo(
        function = "查询序列号",
        zone = "系统",
        typeHex = "0xC1",
        decValue = "0~255",
        decNote = "要查询的 targetSeq",
        special = "调试用；应答状态 1=有 / 2=无"
    ),
    CommandInfo(
        function = "请求设备信息",
        zone = "系统",
        typeHex = "0xC2",
        decValue = "—",
        decNote = "无参数（LEN=0）",
        special = "应答 0/3，随后补发一轮 DAT_INFO（0x83→0x84→0x85→0x90）"
    ),
    CommandInfo(
        function = "选择 WiFi 模式",
        zone = "系统",
        typeHex = "0xC4",
        decValue = "0 / 1 / 2",
        decNote = "0=STA 连路由器，1=AP 自建热点，2=APSTA",
        special = "写入 NVS；>2 或缺 STA 凭据回 5；切换会关闭已建立的 TCP 连接"
    ),
    CommandInfo(
        function = "下发路由器凭据",
        zone = "系统",
        typeHex = "0xC5",
        decValue = "2~96 字节",
        decNote = "[ssidLen][SSID][密码]；SSID 1~32，密码 0 或 8~63",
        special = "按实际长度填写，不补齐 0x00；密码不打印日志"
    ),
    CommandInfo(
        function = "下发热点凭据",
        zone = "系统",
        typeHex = "0xC6",
        decValue = "2~96 字节",
        decNote = "布局同 0xC5",
        special = "写入 NVS；AP 正在运行时立即重建热点"
    ),
    CommandInfo(
        function = "查询 WiFi 状态",
        zone = "系统",
        typeHex = "0xC7",
        decValue = "—",
        decNote = "无参数（LEN=0）",
        special = "ACK 3 后补一帧 DAT_WIFI_STATUS(0x86)"
    ),
    CommandInfo(
        function = "音频自检",
        zone = "音频",
        typeHex = "0xD0",
        decValue = "0 / 1 / 2",
        decNote = "0=启动提示音，1=1 kHz/200 ms 测试音，2=停止",
        special = "ACK 0/3/4/5/6"
    ),
    CommandInfo(
        function = "音量调节",
        zone = "音频",
        typeHex = "0xD1",
        decValue = "0~100",
        decNote = "音量百分比：0 静音，100 满量程",
        special = "响度上限由功放 GAIN 决定；ACK 1/3"
    ),
    CommandInfo(
        function = "录音并自动回放",
        zone = "音频",
        typeHex = "0xD2",
        decValue = "1~10",
        decNote = "录音时长（秒）",
        special = "录完由设备自动回放；0 或 >10 回 5；ACK 0/3/4"
    ),
    CommandInfo(
        function = "请求录音",
        zone = "音频",
        typeHex = "0xD3",
        decValue = "—",
        decNote = "无参数（LEN=0）",
        special = "固件未实现，ACK 仅 4；App 由 DAT_REQ 请求码 2 触发"
    ),
    CommandInfo(
        function = "急停",
        zone = "运动",
        typeHex = "0xE0",
        decValue = "—",
        decNote = "无参数（LEN=0）",
        special = "最高优先级；ACK 3"
    ),
    CommandInfo(
        function = "前进",
        zone = "运动",
        typeHex = "0xE1",
        decValue = "0~100",
        decNote = "最大线速度百分比",
        special = "1 s 内无新命令自动停止；ACK 0/3/4/5/6"
    ),
    CommandInfo(
        function = "后退",
        zone = "运动",
        typeHex = "0xE2",
        decValue = "0~100",
        decNote = "最大线速度百分比",
        special = "1 s 内无新命令自动停止；ACK 0/3/4/5/6"
    ),
    CommandInfo(
        function = "原地左转",
        zone = "运动",
        typeHex = "0xE3",
        decValue = "0~100",
        decNote = "最大角速度百分比",
        special = "1 s 内无新命令自动停止；ACK 0/3/4/5/6"
    ),
    CommandInfo(
        function = "原地右转",
        zone = "运动",
        typeHex = "0xE4",
        decValue = "0~100",
        decNote = "最大角速度百分比",
        special = "1 s 内无新命令自动停止；ACK 0/3/4/5/6"
    ),
    CommandInfo(
        function = "云台俯仰",
        zone = "运动",
        typeHex = "0xE5",
        decValue = "0~255",
        decNote = "线性映射 0°~180°",
        special = "无云台硬件回 6"
    ),
    CommandInfo(
        function = "云台水平",
        zone = "运动",
        typeHex = "0xE6",
        decValue = "0~255",
        decNote = "线性映射 0°~360°",
        special = "无云台硬件回 6"
    ),
    CommandInfo(
        function = "横向左移",
        zone = "运动",
        typeHex = "0xE7",
        decValue = "0~100",
        decNote = "横移最大速度百分比",
        special = "固件未实现，ACK 仅 4"
    ),
    CommandInfo(
        function = "横向右移",
        zone = "运动",
        typeHex = "0xE8",
        decValue = "0~100",
        decNote = "横移最大速度百分比",
        special = "固件未实现，ACK 仅 4"
    )
)

/**
 * 命令查看页：静态速查列表，不依赖链路连接。
 */
@Composable
fun CommandReferenceCompose() {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)
    ) {
        item {
            Text(
                text = "共 ${commands.size} 条 · 仅收录 App 已实现的命令",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        items(commands, key = { it.typeHex }) { item ->
            CommandCard(item)
        }
    }
}

@Composable
private fun CommandCard(item: CommandInfo) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.function,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = item.zone,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))

            Text(
                text = "TYPE：${item.typeHex}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "DEC 取值：${item.decValue}",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "DEC 说明：${item.decNote}",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "特殊说明：${item.special}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
