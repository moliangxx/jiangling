# JiangLing 蓝牙通信协议 V2.0 —— ACK 应答

> **项目唯一标准来源：`d:\Esp32Project`。** 本项目所有文档以 `d:\Esp32Project\index` 下的内容为准；
> `d:\AndroidProject\LingYuan\BLE通信协议\` 与固件目录下的协议文件都是**逐字副本**，冲突一律以 `index` 为准。
> 本文所列行为全部来自 `d:\Esp32Project\JiangLing_ai\src\communication\Protocol.h` 与 `BLE.cpp`。
> 数值书写约定：**状态码、序号、长度、分区/类型编号一律十进制；只有 TYPE 用十六进制。**
>
> **字段表约定**：表中一行为 **1 B = 8 bit**（「偏移 (B)」为字节偏移）。本分册 PAYLOAD 只有两个 `u8`，
> 每个 1 B（8 bit），无多字节字段、无半字节字段。

## 目录

1. [方向与总览](#1-方向与总览)
2. [ACK_RESULT 0x00](#2-ack_result-0x00)
3. [ACK_SESSION_INIT 0x01](#3-ack_session_init-0x01)
4. [ACK_ERROR 0x10（已定义，不发送）](#4-ack_error-0x10已定义不发送)
5. [状态码表](#5-状态码表)
6. [握手时序](#6-握手时序)
7. [完整帧示例](#7-完整帧示例)

## 1. 方向与总览

| 项 | 规则 |
| --- | --- |
| 方向 | **只允许 车 → App**；车端收到 App 的 ACK 静默丢弃整帧 |
| 帧格式 | `AA | SEQ | TYPE | PAYLOAD | CRC8`，无 LEN |
| PAYLOAD 长度 | 三种 ACK 全部固定 2 字节，帧总长 6 字节 |
| 发送方式 | 20 字节分片的 notify，6 字节帧一个分片发完 |
| 应答时机 | 在处理完一条 CMD 的同步路径内发出；音频/录音动作在 ACK 之后才启动 |
| 应答范围 | 只对 CMD 应答；LOG/DAT 不需应答，App 也不得对它们回 ACK |

| TYPE | 名称 | KIND / 分区 / 类型（十进制） | PAYLOAD |
| --- | --- | --- | --- |
| `0x00` | `ACK_RESULT` | ACK / 0 / 0 | `[CMD 的 TYPE, 状态码]` |
| `0x01` | `ACK_SESSION_INIT` | ACK / 0 / 1 | `[状态码, resetDone]` |
| `0x10` | `ACK_ERROR` | ACK / 1 / 0 | `[CMD 的 TYPE, 错误码]`（**固件不发送**） |

ACK 的 `SEQ` 与被应答 CMD 的 `SEQ` 相同（本固件原样回填）；`ACK_SESSION_INIT` 例外，`SEQ` 恒为 0。

## 2. ACK_RESULT 0x00

| 项 | 值 |
| --- | --- |
| TYPE | `0x00` = ACK(0) \| 分区 0 \| 类型 0 |
| 方向 | 车 → App |
| PAYLOAD 长度 | 2 |
| 帧总长 | 6 |
| SEQ | 与对应 CMD 相同 |

| 偏移 (B) | 字段 | 类型（1 B = 8 bit） | 单位 | 取值 | 无效值含义 |
| --- | --- | --- | --- | --- | --- |
| 0 | `cmdType` | u8 | — | 被应答 CMD 的完整 TYPE（如 `0xE1`） | 无 |
| 1 | `status` | u8 | — | 0~6，见 §5 | 7 及以上当前固件不产生，App 按未知处理 |

**用途**：除 `CMD_SESSION_INIT` 以外的所有 CMD 应答。App 用 `PAYLOAD[0]` 反查是哪条命令、用 `SEQ` 匹配自己发出的那条。

**不会出现的组合**

- `PAYLOAD[0]` 不会是 `0xC0`（`CMD_SESSION_INIT` 只走 `ACK_SESSION_INIT`）。
- `PAYLOAD[0]` 不会是 `0xD3/0xE7/0xE8` 以外的任何“未登记 TYPE”，因为那些帧在解析阶段就被丢弃，不会走到应答环节。

**典型状态映射**

| 情形 | `status` |
| --- | --- |
| 会话未初始化就发普通命令（`CMD_STOP` 除外） | 6 |
| 参数越界（音频 `> 2`、音量 `> 100`、录音不在 1~10、运动等级 `> 100`） | 5 |
| `SEQ = 0` 用在非握手命令 | 5 |
| 重复 SEQ（64 条窗口命中） | 3（不重复执行） |
| 硬件/状态不满足（喇叭未就绪、录音忙、音频未初始化等） | 6 |
| 运动命令 `level = 0`、停车、云台、查询无参数命令 | 3 |
| 运动命令 `level = 1~100` | 1 |
| `CMD_QUERY_SEQ` 命中窗口 / 未命中 | 1 / 2 |
| `CMD_REQ_RECORD`、`CMD_LEFT_SHIFT`、`CMD_RIGHT_SHIFT` | 4 |

**完整示例**（对 `CMD_FORWARD 0xE1`、`SEQ 8` 的受理应答）

```text
AA 08 00 E1 01 CF
 |  |  |  |  |  └ CRC8 = CF
 |  |  |  |  └ status = 1（已受理，持续运动）
 |  |  |  └ cmdType = 0xE1
 |  |  └ ACK_RESULT
 |  └ SEQ = 8（与命令一致）
 └ HEAD
```

## 3. ACK_SESSION_INIT 0x01

| 项 | 值 |
| --- | --- |
| TYPE | `0x01` = ACK(0) \| 分区 0 \| 类型 1 |
| 方向 | 车 → App |
| PAYLOAD 长度 | 2 |
| 帧总长 | 6 |
| SEQ | **恒为 0** |

| 偏移 (B) | 字段 | 类型（1 B = 8 bit） | 单位 | 取值 | 无效值含义 |
| --- | --- | --- | --- | --- | --- |
| 0 | `status` | u8 | — | `3` = 握手成功；`5` = 协议版本不符 | 其他值当前不产生 |
| 1 | `resetDone` | u8 | — | `1` = 车端清空了 64 条 CMD 去重表；`0` = 未清空 | 无 |

**触发条件**

| 条件 | `status` | `resetDone` |
| --- | --- | --- |
| `CMD_SESSION_INIT` 的 `protocol == 20` 且上电后还没执行过普通 CMD | 3 | 1 |
| 同上，但上电后已执行过普通 CMD（如断连重连） | 3 | 0 |
| `protocol != 20` | 5 | 0（并把会话标记为未初始化） |

- `resetDone = 1` 时，App 可以放心复用 1~255 的旧 SEQ，不会撞上旧记录。
- `resetDone = 0` 时，App 必须继续使用未用过的 SEQ，或先发 `CMD_QUERY_SEQ` 确认。
- 握手**不影响** LOG/DAT 的 SEQ 计数器；它们上电归零后一直递增，断连不重置。
- `CMD_SESSION_INIT` 用了非 0 的 `SEQ` 时，**不会**走到这里，而是回 `ACK_RESULT[0xC0, 5]`。

**完整示例**

```text
AA 00 01 03 01 7D   ← 握手成功，已清空去重表（首帧应答后的 DAT_INFO 紧跟其后）
AA 00 01 05 00 04   ← 协议版本不符（App 应改用 protocol = 20 重新握手）
```

## 4. ACK_ERROR 0x10（已定义，不发送）

| 项 | 值 |
| --- | --- |
| TYPE | `0x10` = ACK(0) \| 分区 1 \| 类型 0 |
| 方向 | 车 → App |
| PAYLOAD 长度 | 2（长度表已登记） |
| 帧总长 | 6 |
| 固件发送情况 | **从不发送** |

| 偏移 (B) | 字段 | 类型（1 B = 8 bit） | 说明 |
| --- | --- | --- | --- |
| 0 | `cmdType` | u8 | 被应答 CMD 的 TYPE |
| 1 | `errorCode` | u8 | 错误码（当前无发送路径，无实际值定义） |

- 当前所有错误都用 `ACK_RESULT` 的状态码 `5`/`6` 表达。
- App 侧解析器应容忍收到 `0x10`（长度表一致即可），但**不能依赖它**判断错误。
- 不要把它文档化成“可用能力”：本固件没有发送它的代码路径。

**示例（仅用于说明格式，固件不产生）**

```text
AA 08 10 E1 05 71   ← 仅示例格式（CRC 已按同规则算出），实际不会出现
```

## 5. 状态码表

| 值 | 名称 | 含义 | 谁会产生 |
| --- | --- | --- | --- |
| 0 | `ACK_ST_RECEIVED` | 已接收（预留，当前无使用点） | — |
| 1 | `ACK_ST_YES` | 是 / 已受理 | `CMD_QUERY_SEQ` 命中；运动命令 `level 1~100` |
| 2 | `ACK_ST_NO` | 否 | `CMD_QUERY_SEQ` 未命中 |
| 3 | `ACK_ST_DONE` | 完成 / 已执行 | 大多数命令、重复 SEQ、`level = 0` |
| 4 | `ACK_ST_UNKNOWN` | 无法识别 | `0xD3`、`0xE7`、`0xE8` |
| 5 | `ACK_ST_ERROR` | 参数/版本错误 | 越界参数、`SEQ = 0`、版本不符 |
| 6 | `ACK_ST_CANNOT` | 当前不能执行 | 未握手、硬件未就绪、忙 |

- 状态码是**十进制**书写。
- `1` 与 `3` 都不是失败：“已受理”表示运动已开始，App 需继续发新命令维持。
- App 请不要把 `1` 当成功终态去停止重发——运动会在 1000 ms 后自动停车。

## 6. 握手时序

```text
App                                                  车
 |  AA 00 C0 14 E2   CMD_SESSION_INIT(protocol=20)  →
 |                                                   | 清空去重表（仅首帧）
 |  ←  AA 00 01 03 01 7D   ACK_SESSION_INIT[3,1]
 |  ←  AA 01 83 ...        DAT_INFO_VERSION
 |  ←  AA 02 84 ...        DAT_INFO_POWER
 |  ←  AA 03 85 ...        DAT_INFO_STORAGE
 |  AA 01 D1 64 5C   CMD_VOLUME(100)                →
 |  ←  AA 01 00 D1 03 XX   ACK_RESULT[0xD1,3]
```

- 三条 `DAT_INFO` 与握手 ACK 在同一轮发出，App 必须按顺序收，不能假设它们跨轮。
- 握手前只允许 `CMD_STOP`；其他任何命令回 `6`。
- 断连后车端立即把会话置为未初始化，并重新广播；App 重连后必须重新握手。
- 握手失败（`status = 5`）时会话保持未初始化，App 修正 `protocol` 后重发即可。

## 7. 完整帧示例

```text
ACK_RESULT 已受理：      AA 08 00 E1 01 CF
ACK_RESULT 完成：        AA 07 00 E0 03 06
ACK_RESULT 参数错误：    AA 05 00 D2 05 EB   （CMD_RECORD 秒数越界，status=5）
ACK_RESULT 无法识别：    AA 06 00 D3 04 C3   （CMD_REQ_RECORD，status=4）
ACK_RESULT 未握手：      AA 01 00 C1 06 D2   （CMD_QUERY_SEQ 被拒，status=6）
ACK_SESSION_INIT 成功：  AA 00 01 03 01 7D
ACK_SESSION_INIT 版本错：AA 00 01 05 00 04
```

CRC8 为 CRC-8/ATM（`0x07`、初值 `0x00`），覆盖 `AA` 到最后一个 PAYLOAD 字节。
