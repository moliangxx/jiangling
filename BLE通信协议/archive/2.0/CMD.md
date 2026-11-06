# JiangLing 蓝牙通信协议 V2.0 —— CMD 命令

> **项目唯一标准来源：`d:\Esp32Project`。** 本项目所有文档以 `d:\Esp32Project\index` 下的内容为准；
> `d:\AndroidProject\LingYuan\BLE通信协议\` 与固件目录下的协议文件都是**逐字副本**，冲突一律以 `index` 为准。
> 本文所列行为全部来自 `d:\Esp32Project\JiangLing_ai\src\communication\Protocol.h` 与 `BLE.cpp`，
> 代码没实现的能力一律标“未实现/预留”。
> 数值书写约定：**参数、状态码、请求码、长度、模块号、SEQ 一律十进制；只有 TYPE 与原始字节用十六进制。**
>
> **字段表约定**：表中一行为 **1 B = 8 bit**（「偏移 (B)」为字节偏移）。本文 CMD 的参数全部是 `u8`，占 1 B（8 bit）；
> 不存在半字节字段，多字节字段（本分册没有）一律大端。

## 目录

1. [通用规则](#1-通用规则)
2. [系统/信息分区（0xC0~0xC2）](#2-系统信息分区0xc00xc2)
3. [音频分区（0xD0~0xD3）](#3-音频分区0xd00xd3)
4. [动作/运动分区（0xE0~0xE8）](#4-动作运动分区0xe00xe8)
5. [校验与状态码总表](#5-校验与状态码总表)
6. [未实现与无响应的命令](#6-未实现与无响应的命令)
7. [完整帧示例汇总](#7-完整帧示例汇总)

## 1. 通用规则

### 1.1 方向与帧长

| 项 | 规则 |
| --- | --- |
| 方向 | **只允许 App → 车**；车端永不主动发 CMD |
| 帧格式 | `AA | SEQ | TYPE | PAYLOAD | CRC8`，**无 LEN** |
| PAYLOAD 长度 | 由 TYPE 静态表决定（见总文档 §5），两端硬编码同一张表 |
| 帧总长 | `3 + PAYLOAD 长度 + 1` |
| CRC8 | CRC-8/ATM（多项式 `0x07`、初值 `0x00`），覆盖 `AA` 到最后一个 PAYLOAD 字节 |

- **TYPE 的读法见总文档 §4.1**：`TYPE` 是整字节语义编号，精确判定靠整字节查表，位段（KIND/ZONE/ITEM）只用于粗分类。
- 所有 **1 B 参数**（测试动作、音量、录音秒数、运动等级、云台角度）取值范围 **`0~255`**，十进制值按整字节写入：
  例如十进制 `10` 写成 `0x0A` = `0000 1010`（高 4 位补 0），**不存在只发半字节的情况**。

### 1.2 SEQ 规则

- `SEQ = 0` **仅**允许 `CMD_SESSION_INIT`；其他 CMD 用 `0` 会回 `ACK_RESULT [该 TYPE, 5]` 并记 `W/BLE 事件码 14`。
- 车端保留最近 **64** 条已处理 CMD 的 SEQ（环形覆盖，只对 CMD 判重）。
- 重复 SEQ → **不执行**，直接回 `ACK_RESULT [该 TYPE, 3]`，并记 `D/BLE 事件码 16`。
- 重发策略：App 超时后可直接用同一 SEQ 重发；车端未处理过就执行，处理过就按重复应答。
- `CMD_QUERY_SEQ` 与 `CMD_SESSION_INIT` 不参与判重；`CMD_SESSION_INIT` 另用 `ACK_SESSION_INIT` 应答。

### 1.3 处理顺序（两端必须一致）

车端 `processCmd()` 的判定顺序固定如下，**前一步返回就不会进入后续步骤**：

1. `TYPE == CMD_SESSION_INIT 0xC0` → 走握手分支（见 §2.1），不再往下。
2. `SEQ == 0` → 回 `ACK_RESULT[TYPE, 5]` + `W/BLE 14`。
3. `TYPE == CMD_QUERY_SEQ 0xC1` → 走查重分支（见 §2.2），不再往下。
4. 白名单判定：`0xD3`、`0xE7`、`0xE8` → 回 `ACK_RESULT[TYPE, 4]`；其他未登记 TYPE 在**解析阶段**就被整帧丢弃（**无任何应答**）。
5. 参数校验（音频/音量/录音秒数/运动等级），越界回 `ACK_RESULT[TYPE, 5]`。
6. 会话门控：未完成 `CMD_SESSION_INIT` 时，除 `CMD_STOP` 外一律回 `ACK_RESULT[TYPE, 6]`，并记 `W/BLE 事件码 15`。
7. 判重：命中 64 条窗口 → 回 `ACK_RESULT[TYPE, 3]`，不执行。
8. 记入去重窗口、执行动作、回 `ACK_RESULT[TYPE, 最终状态]`。
9. 音频/录音动作在 **ACK 发出之后**才开始；最后把 `CMD <item> LEVEL=<value>` 或 `ANGLE=<value>` 画到屏幕。

> **注意**：第 5 步在第 6 步之前。会话未建立时，一条参数非法的命令回的是 `5`（错误），不是 `6`（不能执行）。

### 1.4 ACK 一览

| 场景 | ACK TYPE | PAYLOAD | 状态码 |
| --- | --- | --- | --- |
| 普通 CMD 应答 | `0x00` `ACK_RESULT` | `[CMD 的 TYPE, 状态码]` | 3/1/5/6/4（见各条） |
| `CMD_SESSION_INIT` 应答 | `0x01` `ACK_SESSION_INIT` | `[状态码, resetDone]` | 3 或 5 |
| `ACK_ERROR 0x10` | — | — | **已定义，固件不发送** |

ACK 的 `SEQ` 与被应答 CMD 的 `SEQ` 相同；`ACK_SESSION_INIT` 的 `SEQ` 恒为 0。
状态码含义：`0`=已接收、`1`=执行中/已受理、`2`=否、`3`=完成、`4`=无法识别、`5`=错误、`6`=当前不能执行。

## 2. 系统/信息分区（0xC0~0xC2）

`KIND = 3 (CMD)`、`分区 = 0`、`类型 = item`。

### 2.1 `CMD_SESSION_INIT` — TYPE `0xC0`

| 项 | 值 |
| --- | --- |
| TYPE | `0xC0` = CMD(3) << 6 \| 分区 0 << 4 \| 类型 0 |
| 方向 | App → 车 |
| PAYLOAD 长度 | 1 |
| 帧总长 | 5 |
| SEQ | **必须为 0** |

| 偏移 (B) | 字段 | 类型（1 B = 8 bit） | 单位 | 取值 | 无效值含义 |
| --- | --- | --- | --- | --- | --- |
| 0 | `protocol` | u8 | — | 必须等于 `20` | 其他值视为版本不匹配 |

**校验**

- `SEQ != 0` → 回 `ACK_RESULT[0xC0, 5]`（ACK 的 SEQ 用该非法 SEQ），记 `W/BLE 事件码 14`，不建立会话。
- `protocol != 20` → 依赖的 `_isSessionInitialized` 置假，回 `ACK_SESSION_INIT[5, 0]`，记 `W/SYSTEM 事件码 6`；**不清空去重表**。
- `protocol == 20` → 成功。

**ACK 行为**

- 成功：`ACK_SESSION_INIT[3, resetDone]`，`SEQ = 0`。
- `resetDone = 1` 表示车端**清空了 64 条 CMD 去重表**；`resetDone = 0` 表示没有清空。
- `resetDone` 只在“上电/复位后还没成功执行过任何普通 CMD”时为 1（内部 `_isInit` 标志）；同一会话再次握手一律为 0。
- 握手 **不影响** LOG/DAT 的 SEQ 计数器，它们不归零。

**触发时机**

- 连接建立后 App 必须先发这一条，之后才能发其他命令（`CMD_STOP` 除外）。
- 成功后车端**立即**连发一轮 `DAT_INFO_VERSION → DAT_INFO_POWER → DAT_INFO_STORAGE`，不等待 App 回应。
- 断连后车端把会话置为未初始化，重连需重新握手。

**完整示例**（`AA 00 C0 14` + CRC `E2`）

```text
AA 00 C0 14 E2
 |  |  |  |  └ CRC8 = E2（覆盖 AA 00 C0 14）
 |  |  |  └ 协议版本 = 20
 |  |  └ CMD_SESSION_INIT
 |  └ SEQ = 0
 └ HEAD
```

### 2.2 `CMD_QUERY_SEQ` — TYPE `0xC1`

| 项 | 值 |
| --- | --- |
| TYPE | `0xC1` = CMD(3) \| 分区 0 \| 类型 1 |
| 方向 | App → 车 |
| PAYLOAD 长度 | 1 |
| 帧总长 | 5 |

| 偏移 (B) | 字段 | 类型（1 B = 8 bit） | 单位 | 取值 | 无效值含义 |
| --- | --- | --- | --- | --- | --- |
| 0 | `targetSeq` | u8 | — | 0~255 | 无 |

**校验 / 门控**

- 未完成会话握手 → 回 `ACK_RESULT[0xC1, 6]`，记 `W/BLE 事件码 15`。
- `SEQ == 0` 在第 2 步已被拦截，回 `ACK_RESULT[0xC1, 5]`。
- 本命令**不做自身判重**：同一 SEQ 重复查询仍会应答，并按结果把该 SEQ 记入窗口。

**ACK 行为**

- `targetSeq` 在车端 64 条 CMD 窗口内 → `ACK_RESULT[0xC1, 1]`（是），记 `D/SYSTEM 事件码 7`。
- 不在窗口内 → `ACK_RESULT[0xC1, 2]`（否），记 `D/SYSTEM 事件码 8`。
- 查询成功后，若本查询自身的 SEQ 不在窗口内，则记入窗口并把 `_isInit` 置假。

**触发时机**

- App 在发送超时/重连后，用来确认“某条 CMD 是否已经执行过”，事件驱动，非常规节拍。
- 只查 CMD 窗口；**查不到** LOG/DAT/ACK 的 SEQ。

**完整示例**（查询 SEQ 5 是否已处理，本命令用 SEQ 1）

```text
AA 01 C1 05 EB
 |  |  |  |  └ CRC8 = EB
 |  |  |  └ targetSeq = 5
 |  |  └ CMD_QUERY_SEQ
 |  └ SEQ = 1
 └ HEAD
```

### 2.3 `CMD_REQ_INFO` — TYPE `0xC2`

| 项 | 值 |
| --- | --- |
| TYPE | `0xC2` = CMD(3) \| 分区 0 \| 类型 2 |
| 方向 | App → 车 |
| PAYLOAD 长度 | 0 |
| 帧总长 | 4 |

**校验 / 门控**：需要已完成握手，否则 `ACK_RESULT[0xC2, 6]`；无参数可越界。

**ACK 行为**：`ACK_RESULT[0xC2, 3]`；随后**在同一轮**连发 `DAT_INFO_VERSION → POWER → STORAGE`，与握手后那一轮内容完全相同。

**触发时机**：事件驱动；车端不会周期性重发 DAT_INFO。App 若错过握手时的三帧，用本命令补拉。

**完整示例**

```text
AA 02 C2 A5
 |  |  |  └ CRC8 = A5
 |  |  └ CMD_REQ_INFO（无 PAYLOAD）
 |  └ SEQ = 2
 └ HEAD
```

## 3. 音频分区（0xD0~0xD3）

`KIND = 3 (CMD)`、`分区 = 1`、`类型 = item`。

### 3.1 `CMD_AUDIO_TEST` — TYPE `0xD0`

| 项 | 值 |
| --- | --- |
| TYPE | `0xD0` = CMD(3) \| 分区 1 \| 类型 0 |
| 方向 | App → 车 |
| PAYLOAD 长度 | 1 |
| 帧总长 | 5 |

| 偏移 (B) | 字段 | 类型（1 B = 8 bit） | 单位 | 取值 | 无效值含义 |
| --- | --- | --- | --- | --- | --- |
| 0 | `action` | u8 | — | 0 = 上电提示音；1 = 1 kHz/200 ms 测试音；2 = 立即停止 | > 2 视为非法 |

**校验**

- `action > 2` → `ACK_RESULT[0xD0, 5]`，记 `W/BLE 事件码 48+item`（即 `0x30 + 0` = 48）。
- 需要已完成握手，否则 `ACK_RESULT[0xD0, 6]`。

**ACK 行为**

| `action` | 条件 | 状态码 |
| --- | --- | --- |
| 2（停止） | 恒可执行，不检查硬件 | 3 |
| 0 / 1 | `AudioCue` 未初始化 → 记 `W/AUDIO 事件码 3` | 6 |
| 0 / 1 | 正在录音或回放（`Record::isBusy()`）→ 记 `W/AUDIO 事件码 4` | 6 |
| 0 / 1 | 另一段提示音还在播（`AudioCue::isBusy()`）→ 记 `W/AUDIO 事件码 5` | 6 |
| 0 / 1 | 以上都不成立 | 3 |

- 被拒时**不会**播放任何声音，状态码不会是 3。
- `3` 只代表“已受理”，播放由 `AudioCue::update()` 分块推进，不阻塞。

**触发时机**：事件驱动，单次；ACK 先发，播放后启动。

**完整示例**（播放上电提示音，SEQ 3）

```text
AA 03 D0 00 64
 |  |  |  |  └ CRC8 = 64
 |  |  |  └ action = 0
 |  |  └ CMD_AUDIO_TEST
 |  └ SEQ = 3
 └ HEAD
```

### 3.2 `CMD_VOLUME` — TYPE `0xD1`

| 项 | 值 |
| --- | --- |
| TYPE | `0xD1` = CMD(3) \| 分区 1 \| 类型 1 |
| 方向 | App → 车 |
| PAYLOAD 长度 | 1 |
| 帧总长 | 5 |

| 偏移 (B) | 字段 | 类型（1 B = 8 bit） | 单位 | 取值 | 无效值含义 |
| --- | --- | --- | --- | --- | --- |
| 0 | `volume` | u8 | % | 0 = 静音，100 = 满量程 | > 100 视为非法 |

**校验**

- `> 100` → `ACK_RESULT[0xD1, 5]`，记 `W/BLE 事件码 49`（`0x30 + 1`）。
- **不做裁剪**：不会把 200 悄悄改成 100。
- 需要已完成握手，否则 `ACK_RESULT[0xD1, 6]`。

**ACK 行为**

- `Speaker` 未就绪 → `ACK_RESULT[0xD1, 6]`，记 `W/SPEAKER 事件码 1`，音量不变。
- 就绪 → `Speaker::setVolume(volume)`，`ACK_RESULT[0xD1, 3]`。

**触发时机**：事件驱动；设置是持久的，断电/复位后回到驱动默认值（固件不做 NVS 保存）。

**完整示例**（音量 100，SEQ 4）

```text
AA 04 D1 64 5C
 |  |  |  |  └ CRC8 = 5C
 |  |  |  └ volume = 100
 |  |  └ CMD_VOLUME
 |  └ SEQ = 4
 └ HEAD
```

### 3.3 `CMD_RECORD` — TYPE `0xD2`

| 项 | 值 |
| --- | --- |
| TYPE | `0xD2` = CMD(3) \| 分区 1 \| 类型 2 |
| 方向 | App → 车 |
| PAYLOAD 长度 | 1 |
| 帧总长 | 5 |

| 偏移 (B) | 字段 | 类型（1 B = 8 bit） | 单位 | 取值 | 无效值含义 |
| --- | --- | --- | --- | --- | --- |
| 0 | `seconds` | u8 | 秒 | 1~10 | 0 或 > 10 视为非法 |

**校验（顺序固定）**

1. `seconds < 1` 或 `> 10` → `ACK_RESULT[0xD2, 5]`，记 `W/BLE 事件码 50`（`0x30 + 2`）。
2. 需要已完成握手，否则 `ACK_RESULT[0xD2, 6]`。

**ACK 行为**

| 条件 | 状态码 | 日志 |
| --- | --- | --- |
| 录音缓冲未就绪（`Record::isReady()` 假） | 6 | `W/RECORD 1` |
| 麦克风未就绪（`Mic::isReady()` 假） | 6 | `W/RECORD 2` |
| 正在录音或回放 | 6 | `W/RECORD 4` |
| 提示音/测试音还在播 | 6 | `W/RECORD 5` |
| `seconds > Record::maxSeconds()`（PSRAM 缺失时缓冲只够 1~2 s） | 6 | `W/RECORD 6` |
| 以上都不成立 | 3 | — |

- 回 `3` 后车端才调用 `Record::start(seconds)`。若 `start` 当场失败（`START_BUSY` / `START_NO_MIC` / `START_TOO_LONG` / `START_NO_BUFFER`），车端**只写 W 日志（事件码 7/8/9/10）**，不会补发第二个 ACK。
- 因此 `3` 的语义是“参数通过并已尝试启动”，不是“已录完”。
- 录满 `seconds` 秒后**自动回放**，不需要 App 再发命令。

**触发时机**：事件驱动，单次；录音/回放全程非阻塞，由 `Record::update()` 推进。

**完整示例**（录 5 秒，SEQ 5）

```text
AA 05 D2 05 28
 |  |  |  |  └ CRC8 = 28
 |  |  |  └ seconds = 5
 |  |  └ CMD_RECORD
 |  └ SEQ = 5
 └ HEAD
```

### 3.4 `CMD_REQ_RECORD` — TYPE `0xD3`（未实现）

| 项 | 值 |
| --- | --- |
| TYPE | `0xD3` = CMD(3) \| 分区 1 \| 类型 3 |
| 方向 | App → 车 |
| PAYLOAD 长度 | 0 |
| 帧总长 | 4 |

- 车端**不支持**该命令：固定回 `ACK_RESULT[0xD3, 4]`，记 `W/BLE 事件码 17`，不做任何录音动作。
- 录音请使用 `CMD_RECORD 0xD2`。

**完整示例**

```text
AA 06 D3 86
 |  |  |  └ CRC8 = 86
 |  |  └ CMD_REQ_RECORD（仅回无法识别）
 |  └ SEQ = 6
 └ HEAD
```

## 4. 动作/运动分区（0xE0~0xE8）

`KIND = 3 (CMD)`、`分区 = 2`、`类型 = item`。

### 4.1 运动四命令 `CMD_FORWARD 0xE1` / `CMD_BACK 0xE2` / `CMD_LEFT 0xE3` / `CMD_RIGHT 0xE4`

| 项 | 值 |
| --- | --- |
| TYPE | `0xE1` = CMD(3) \| 分区 2 \| 类型 1（后退 2、左转 3、右转 4） |
| 方向 | App → 车 |
| PAYLOAD 长度 | 1 |
| 帧总长 | 5 |

| 偏移 (B) | 字段 | 类型（1 B = 8 bit） | 单位 | 取值 | 无效值含义 |
| --- | --- | --- | --- | --- | --- |
| 0 | `level` | u8 | % | 0~100，相对最大速度/最大回转角速度 | > 100 视为非法 |

**校验**

- `level > 100` → `ACK_RESULT[TYPE, 5]`，记 `W/BLE 事件码 0x30 + item`。
- 需要已完成握手（`CMD_STOP` 除外），否则 `ACK_RESULT[TYPE, 6]`。

**ACK 行为**

| `level` | 状态码 | 是否更新执行状态 |
| --- | --- | --- |
| 0 | 3 | 是，等价于 `CMD_STOP` |
| 1~100 | 1（已受理） | 是 |

- 状态码 `1` 是“持续运动已受理”，不是失败；`3` 只用于 `level = 0` 的停车语义。

**执行状态（当前无电机硬件，只算目标值）**

- 目标轮转速（`int16`，单位 `0.01 r/s`）= `level × 500 / 100`（最大 5.00 r/s）。
- 目标 PWM = `level × 255 / 100`。
- 差速解算：前进两轮同正；后退两轮同负；左转左负右正；右转左正右负。
- 每收到一条运动命令刷新“失联计时”。

**触发时机 / 节拍**

- **必须重复发送**：车端在最后一次运动命令后 **1000 ms** 内没有新的运动命令，就自动停车并记 `W/MOTOR 事件码 1`。
- 因此 App 需以明显小于 1 s 的周期持续重发（例如 200~500 ms 一条），每条用**新的 SEQ**；重复 SEQ 会被判重，不会刷新计时。

**完整示例**（前进 90%，SEQ 8；ACK 见 §7）

```text
AA 08 E1 5A E5
 |  |  |  |  └ CRC8 = E5
 |  |  |  └ level = 90
 |  |  └ CMD_FORWARD
 |  └ SEQ = 8
 └ HEAD
```

其他三条（同一 `level` 写法）：

```text
AA 09 E2 32 AE   ← CMD_BACK，level = 50
AA 0A E3 28 40   ← CMD_LEFT，level = 40
AA 0B E4 3C 2C   ← CMD_RIGHT，level = 60
```

### 4.2 `CMD_STOP` — TYPE `0xE0`

| 项 | 值 |
| --- | --- |
| TYPE | `0xE0` = CMD(3) \| 分区 2 \| 类型 0 |
| 方向 | App → 车 |
| PAYLOAD 长度 | 0 |
| 帧总长 | 4 |

**校验 / 门控**

- **唯一**允许在握手完成前执行的命令（急停不能被会话状态挡住）。
- 无参数可越界；重复 SEQ 仍按通用判重处理（回 `3`，不重复执行，但也不算错误）。

**ACK 行为**：恒 `ACK_RESULT[0xE0, 3]`，随后清空目标轮速/PWM/运动等级，并刷新失联计时（使超时不会再把停车当异常记录）。

> 注意：重复 SEQ 的 `CMD_STOP` 走通用判重，**不会再次执行**停车（只回 `3`）。若担心重发撞上已用 SEQ，请使用新的 SEQ。

**触发时机**：App 松开方向键、切换模式、退出遥控时发送；也用于握手前的安全兜底。

**完整示例**

```text
AA 07 E0 0A
 |  |  |  └ CRC8 = 0A
 |  |  └ CMD_STOP（无 PAYLOAD）
 |  └ SEQ = 7
 └ HEAD
```

### 4.3 云台 `CMD_PITCH 0xE5` / `CMD_YAW 0xE6`（无硬件）

| 项 | 值 |
| --- | --- |
| TYPE | `0xE5` / `0xE6` = CMD(3) \| 分区 2 \| 类型 5 / 6 |
| 方向 | App → 车 |
| PAYLOAD 长度 | 1 |
| 帧总长 | 5 |

| 偏移 (B) | 字段 | 类型（1 B = 8 bit） | 单位 | 取值 | 无效值含义 |
| --- | --- | --- | --- | --- | --- |
| 0 | `angle` | u8 | 度 | 0~255 原始角值，**不做范围校验** | 无 |

**校验 / ACK**

- 只校验握手机和判重；`angle` 全 0~255 都接受。
- 回 `ACK_RESULT[TYPE, 3]`；角度写入 `_gimbalPitchAngle` / `_gimbalYawAngle`。
- **不参与**运动失联计时（不会因为只发云台命令触发自动停车，也不会被自动停车清零）。
- 本固件**没有云台硬件**，角度只保存不驱动。
- 角度如何显示属于 App 侧约定：旧版 App 曾用 `角度 = raw × 180 / 255`（俯仰）、`raw × 360 / 255`（偏航）；
  固件**不做**这个换算，只存原始字节，App 可自行沿用或另定。

**触发时机**：事件驱动；如 App 采用拖拽滑动条，可低频发送。

**完整示例**

```text
AA 0C E5 5A 1A   ← CMD_PITCH，angle = 90
AA 0D E6 2D 0C   ← CMD_YAW，angle = 45
```

### 4.4 麦克纳姆横移 `CMD_LEFT_SHIFT 0xE7` / `CMD_RIGHT_SHIFT 0xE8`（未实现）

| 项 | 值 |
| --- | --- |
| TYPE | `0xE7` / `0xE8` = CMD(3) \| 分区 2 \| 类型 7 / 8 |
| 方向 | App → 车 |
| PAYLOAD 长度 | 1（保留字段，**固件不读取**） |
| 帧总长 | 5 |

- 本固件按差速轮实现，**不支持横移**：固定回 `ACK_RESULT[TYPE, 4]`，记 `W/BLE 事件码 18`，不改任何运动状态。
- 该命令在解析阶段能通过长度表（长度 1），所以会有应答；`level` 字段被忽略。

**完整示例**

```text
AA 0E E7 1E 3D   ← CMD_LEFT_SHIFT，参数被忽略
AA 0F E8 1E 95   ← CMD_RIGHT_SHIFT，参数被忽略
```

## 5. 校验与状态码总表

| CMD | TYPE | 参数范围 | 越界结果 | 会话未建立 | 硬件/状态不满足 | 正常 |
| --- | --- | --- | --- | --- | --- | --- |
| `CMD_SESSION_INIT` | `0xC0` | `protocol = 20` | `ACK_SESSION_INIT[5,0]` | 不需要（本身建立会话） | — | `ACK_SESSION_INIT[3, resetDone]` |
| `CMD_QUERY_SEQ` | `0xC1` | `targetSeq 0~255` | — | `6` | — | `1`（在窗口）/`2`（不在） |
| `CMD_REQ_INFO` | `0xC2` | — | — | `6` | — | `3` |
| `CMD_AUDIO_TEST` | `0xD0` | `0/1/2` | `5` | `6` | `6` | `3` |
| `CMD_VOLUME` | `0xD1` | `0~100` | `5` | `6` | `6` | `3` |
| `CMD_RECORD` | `0xD2` | `1~10` | `5` | `6` | `6` | `3` |
| `CMD_REQ_RECORD` | `0xD3` | —（未实现） | — | — | — | 固定 `4` |
| `CMD_STOP` | `0xE0` | — | — | **允许** | — | `3` |
| `CMD_FORWARD`/`BACK`/`LEFT`/`RIGHT` | `0xE1`~`0xE4` | `0~100` | `5` | `6` | — | `3`（0）/`1`（1~100） |
| `CMD_PITCH`/`CMD_YAW` | `0xE5`/`0xE6` | `0~255` | — | `6` | — | `3` |
| `CMD_LEFT_SHIFT`/`RIGHT_SHIFT` | `0xE7`/`0xE8` | 忽略 | — | — | — | 固定 `4` |

- 越界一律回错误码，**固件不做静默裁剪**（唯一例外是板 → App 的 `DAT_REQ` 参数，见 DAT.md）。
- `SEQ = 0` 用在非 `CMD_SESSION_INIT` 的命令上，先于上表所有列返回 `5`。

## 6. 未实现与无响应的命令

| 类型 | 行为 |
| --- | --- |
| `CMD_REQ_RECORD 0xD3`、`CMD_LEFT_SHIFT 0xE7`、`CMD_RIGHT_SHIFT 0xE8` | 长度表内登记，**回 `ACK_RESULT[TYPE, 4]`**，不执行 |
| CMD 分区 0 的 `0xC3`~`0xCF`、分区 1 的 `0xD4`~`0xDF`、分区 2 的 `0xE9`~`0xEF`、分区 3 全部 | **未登记长度**，帧在解析阶段被丢弃，**无任何 ACK** |
| `ACK_ERROR 0x10` | 已定义、有长度表项，但固件**不发送** |

> App 端请勿把“未登记 TYPE 无响应”当成超时重试的依据——重发也同样没有响应。只能发表内命令。

## 7. 完整帧示例汇总

以下字节序列均可直接用于联调（SEQ 为示例值，App 可自选 1~255）：

```text
握手：      AA 00 C0 14 E2
查重：      AA 01 C1 05 EB
补拉信息：  AA 02 C2 A5
提示音：    AA 03 D0 00 64
音量 100：  AA 04 D1 64 5C
录音 5 s：  AA 05 D2 05 28
请求录音：  AA 06 D3 86
停车：      AA 07 E0 0A
前进 90%：  AA 08 E1 5A E5
云台 90°：  AA 0C E5 5A 1A
```

对应车端应答（状态码用十进制解释）：

```text
前进 90% 的 ACK：   AA 08 00 E1 01 CF   （ACK_RESULT,[0xE1, 1] 已受理）
停车的 ACK：        AA 07 00 E0 03 06   （ACK_RESULT,[0xE0, 3] 完成）
握手成功的 ACK：    AA 00 01 03 01 7D   （ACK_SESSION_INIT,[3, 1] 已清空去重表）
版本不符的 ACK：    AA 00 01 05 00 04   （ACK_SESSION_INIT,[5, 0] 版本错误）
```

CRC8 校验方法：把 `AA` 到最后一个 PAYLOAD 字节按 CRC-8/ATM（`0x07` 多项式、初值 `0x00`）逐字节计算；
帧的最后一个字节必须等于该结果。例如 `AA 08 E1 5A` 的 CRC 为 `E5`，故整帧为 `AA 08 E1 5A E5`。
