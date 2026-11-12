/*
 * Protocol.h —— JiangLing V1.10 应用层帧协议常量、TYPE 布局、长度校验与 CRC8
 *
 * 职责：
 *   只集中定义协议术语（HEAD、SEQ、TYPE、LEN、PAYLOAD、CRC8、KIND、
 *   CMD_xxx、ACK_ST_xxx、LOG 模块/等级/事件码、DAT_xxx、DAT_REQ_xxx）、
 *   PAYLOAD 长度合法性校验、组帧助手、PAYLOAD 大端读写与 CRC-8/ATM。
 *
 * 协作：
 *   base/util/cmd、base/util/data、base/util/log 三个类在各自的 get() 出口里
 *   用本文件的 beginFrame/finishFrame 与 crc8() 直接组装完整帧；main/wife/Wifi.cpp
 *   只负责把字节搬运到 socket，不再重复组帧或算 CRC。
 *
 * V1.10 规则（与 Android 端一致，不可自创）：
 *   TYPE 是**整字节语义编号**：低 6 位是平铺的 64 个类型序号，高 2 位 KIND 只表示方向。
 *   「系统/音频/运动」这类分组只是给人看的注释与排列顺序，**不是位域**；
 *   App 与固件两端都按整字节查表，任何一方都不要做中间两位的位切分。
 *   唯一例外是 LOG：中间两位是日志等级 D/I/W/E，有实际语义，由 makeLogType 拼出。
 *   帧格式 = HEAD | SEQ | TYPE | LEN | PAYLOAD(LEN) | CRC8，**帧头含 LEN 字节**；
 *   LEN = PAYLOAD 字节数（不含 LEN 自身、不含 CRC），帧总长 = 5 + LEN；
 *   接收端按 LEN 定界；LEN 非法（超出 255 或与 TYPE 允许范围不符）整帧丢弃；
 *   LOG 帧是特例：每条记录 = TYPE | LEN(固定 1) | param，一帧最多 8 条；
 *   CRC8 覆盖 HEAD 起到最后一个 PAYLOAD 字节（含 LEN），不含 CRC 自身；
 *   方向强制：CMD 只 App→车，LOG/DAT/ACK 只 车→App。
 *
 * 改动同步点：新增/修改 TYPE 或 LEN 时，必须同时改本文件、
 *   payloadLengthValid()、`index/江灵通信协议/` 下的五份分册（总览/命令/回复/数据/日志），否则 App 侧会拒帧。
 */
#ifndef JIANGLING_PROTOCOL_H
#define JIANGLING_PROTOCOL_H

#include <stddef.h>
#include <stdint.h>

namespace JiangLingProtocol {

// ==================== 帧字段 ====================
constexpr uint8_t FRAME_HEAD = 0xAA;       // 帧同步头
constexpr uint8_t FRAME_HEADER_BYTES = 4;  // HEAD + SEQ + TYPE + LEN
constexpr uint8_t FRAME_LEN_BYTES = 1;     // LEN = PAYLOAD 字节数
constexpr uint8_t FRAME_CRC_BYTES = 1;
// 帧总长 = 5 + LEN（HEAD/SEQ/TYPE/LEN/CRC 共 5 B）。
constexpr uint8_t FRAME_OVERHEAD_BYTES = FRAME_HEADER_BYTES + FRAME_CRC_BYTES;
constexpr uint8_t MAX_PAYLOAD_BYTES = 255;  // LEN 为 1 B
constexpr uint16_t MAX_FRAME_BYTES =
    FRAME_OVERHEAD_BYTES + MAX_PAYLOAD_BYTES;  // 260 B
constexpr uint8_t PROTOCOL_VERSION = 21;   // LEN 帧格式是不兼容变更
constexpr uint8_t SEQ_INIT = 0;            // SEQ=0 专用于 CMD_SESSION_INIT

// ==================== LOG 帧特例 ====================
// 每条记录 = TYPE | LEN(1) | param(1)，LEN 表示记录内 param 的字节数（当前恒为 1）。
constexpr uint8_t LOG_MAX_RECORDS = 8;       // 单帧最多 8 条（实际条数可少）
constexpr uint8_t LOG_RECORD_LEN_BYTES = 1;
constexpr uint8_t LOG_RECORD_PARAM_BYTES = 1;
constexpr uint8_t LOG_RECORD_BYTES =
    1 + LOG_RECORD_LEN_BYTES + LOG_RECORD_PARAM_BYTES;  // 3 B
constexpr uint8_t LOG_MAX_PAYLOAD_BYTES = LOG_MAX_RECORDS * LOG_RECORD_BYTES; // 24
constexpr uint16_t LOG_FRAME_MAX_BYTES =
    FRAME_OVERHEAD_BYTES + LOG_MAX_PAYLOAD_BYTES;  // 29 B

// ==================== TYPE 布局与 KIND ====================
// KIND 只取 TYPE 的高 2 位，用于方向校验（type >> 6）。低 6 位是平铺序号，
// 不做任何位域解析；因此 CMD / DAT / ACK 常量一律直接写十六进制字面量。
enum Kind : uint8_t {
  KIND_ACK = 0,
  KIND_LOG = 1,
  KIND_DAT = 2,
  KIND_CMD = 3
};

inline constexpr Kind kindOf(uint8_t type) {
  return static_cast<Kind>(type >> 6);
}

// 仅 LOG 用：中间两位是日志等级（D/E/W/R），不是阅读分组。
inline constexpr uint8_t zoneOf(uint8_t type) {
  return static_cast<uint8_t>((type >> 4) & 0x03);
}

// 取低 4 位，只用于把「未登记/非法 CMD」编码进日志 param（0x20+n / 0x30+n）。
inline constexpr uint8_t itemOf(uint8_t type) {
  return static_cast<uint8_t>(type & 0x0F);
}

// LOG 等级：中间两位有实际语义，只有这一类 TYPE 允许按位拼装。
// 线路字节值：D=0（0x40 基址）、E=1（0x50）、W=2（0x60）、I=3（0x70）。
// Info 已从 D 拆出，占用原 RSV 位；既有 D/E/W 的字节值保持不变（见 日志.md §1）。
enum LogLevelZone : uint8_t {
  LOG_ZONE_DEBUG = 0,  // D（仅 Debug）
  LOG_ZONE_ERROR = 1,  // E（固件的 Fatal 并入 E）
  LOG_ZONE_WARN = 2,   // W
  LOG_ZONE_INFO = 3    // I（Info 独立等级，原 LOG_ZONE_RSV）
};

// ==================== ACK 类型与定长 PAYLOAD ====================
constexpr uint8_t ACK_PAYLOAD_LEN = 2;

constexpr uint8_t ACK_RESULT = 0x00;        // [被应答 CMD 的 TYPE, 状态码]
constexpr uint8_t ACK_SESSION_INIT = 0x01;  // [状态码, resetDone]
constexpr uint8_t ACK_ERROR = 0x10;         // [被应答 CMD 的 TYPE, 错误码]

// ACK 的回复状态（PAYLOAD 内状态码）。
enum AckStatus : uint8_t {
  ACK_ST_RECEIVED = 0,
  ACK_ST_YES = 1,
  ACK_ST_NO = 2,
  ACK_ST_DONE = 3,
  ACK_ST_UNKNOWN = 4,
  ACK_ST_ERROR = 5,
  ACK_ST_CANNOT = 6
};

// ==================== DAT 类型与定长 PAYLOAD ====================
// ---- 状态上报（0x80~0x86）----
constexpr uint8_t DAT_BAT = 0x80;
constexpr uint8_t DAT_US = 0x81;
constexpr uint8_t DAT_WHEEL = 0x82;
constexpr uint8_t DAT_INFO_VERSION = 0x83;
constexpr uint8_t DAT_INFO_POWER = 0x84;
constexpr uint8_t DAT_INFO_STORAGE = 0x85;
constexpr uint8_t DAT_WIFI_STATUS = 0x86;
// 0x87 DAT_INIT_STATE：TCP 事件上报 + UDP 8082 广播发现共用同一帧（见《数据.md》§5）。
constexpr uint8_t DAT_INIT_STATE = 0x87;

// ---- 内存/统计（0x90）----
constexpr uint8_t DAT_MEM = 0x90;

// ---- 请求与应答（0xA0）----
constexpr uint8_t DAT_REQ = 0xA0;

constexpr uint8_t DAT_BAT_LEN = 2;    // [电压 u16] 0.01 V，0xFFFF 无效
constexpr uint8_t DAT_US_LEN = 2;     // [距离 u16] 厘米，0xFFFF 无效
constexpr uint8_t DAT_WHEEL_LEN = 4;  // [左轮 int16][右轮 int16] 0.01 r/s
constexpr uint8_t DAT_MEM_LEN = 16;   // 见 index/江灵通信协议/数据.md
constexpr uint8_t DAT_REQ_LEN = 2;    // [请求码 u8][参数 u8]
// [模式 u8][连接状态 u8][IPv4 4 B][RSSI int16]；RSSI 0xFFFF 表示不可用。
constexpr uint8_t DAT_WIFI_STATUS_LEN = 8;
// [stage][wifiMode][wifiState][peerStage][flags][IPv4 4 B]
constexpr uint8_t DAT_INIT_STATE_LEN = 9;
constexpr uint16_t DAT_INVALID_U16 = 0xFFFF;
constexpr int16_t DAT_INVALID_I16 = -1;  // 以 0xFFFF 表示该轮无效

// DAT_INFO 拆成三个独立 TYPE，各自定长。
constexpr uint8_t DAT_INFO_VERSION_LEN = 20;  // [主][次][补丁][协议][型号 16 B]
constexpr uint8_t DAT_INFO_POWER_LEN = 5;     // [电芯][化学][轮径 u16][最大挡位]
constexpr uint8_t DAT_INFO_STORAGE_LEN = 6;   // [Flash MB u16][PSRAM KB u16][能力 u16]
constexpr uint8_t DAT_INFO_MODEL_BYTES = 16;

// DAT_REQ 请求码（App 按代码表映射成正常 CMD 后再发回小车）。
enum DataRequestCode : uint8_t {
  DAT_REQ_STOP = 1,
  DAT_REQ_RECORD = 2,
  DAT_REQ_SHIFT_LEFT = 3,
  DAT_REQ_SHIFT_RIGHT = 4,
  DAT_REQ_PITCH = 5,
  DAT_REQ_YAW = 6,
  DAT_REQ_FORWARD = 7,
  DAT_REQ_BACK = 8,
  DAT_REQ_LEFT = 9,
  DAT_REQ_RIGHT = 10
};

// DAT_INFO_STORAGE 能力位图。
enum CapabilityBit : uint16_t {
  CAP_VOICE = 1 << 0,
  CAP_GIMBAL = 1 << 1,
  CAP_DISPLAY = 1 << 2,
  CAP_US = 1 << 3,
  CAP_CAMERA = 1 << 4,
  CAP_IMU = 1 << 5,
  CAP_MECANUM = 1 << 6
};

// ==================== CMD 类型与定长 PAYLOAD ====================
// ---- 系统/信息（0xC0~0xC7）----
// 0xC3 为旧设计 CMD_SELECT_MODE 保留，本固件不实现，收到按未登记 TYPE 丢弃。
constexpr uint8_t CMD_SESSION_INIT = 0xC0;  // LEN=1，payload[0]=协议版本
constexpr uint8_t CMD_QUERY_SEQ = 0xC1;     // LEN=1，payload[0]=目标 SEQ
constexpr uint8_t CMD_REQ_INFO = 0xC2;      // LEN=0，回 ACK 后补一轮 DAT_INFO
constexpr uint8_t CMD_WIFI_MODE = 0xC4;     // LEN=1，payload[0]=WIFI_CFG_MODE_*
// LEN = 1 B ssidLen + SSID + 密码实际长度，不填充。
constexpr uint8_t CMD_WIFI_STA_CONFIG = 0xC5;
constexpr uint8_t CMD_WIFI_AP_CONFIG = 0xC6;
constexpr uint8_t CMD_WIFI_STATUS = 0xC7;   // LEN=0，只回 ACK（已就绪 3 / 未就绪 5）

// ---- 初始化命令（0xC8~0xCA）：与运行期命令同走 WiFi UDP 8081，ACK 走 TCP 8080 ----
constexpr uint8_t CMD_INIT_COMMIT = 0xC8;         // LEN=2，[mode][保留必须为 0]
constexpr uint8_t CMD_INIT_STATUS = 0xC9;         // LEN=0，查询初始化状态
constexpr uint8_t CMD_INIT_FACTORY_RESET = 0xCA;  // LEN=1，[magic]=0x5A
// 0xCB 原语义是「断链 + 停广播 / 恢复广播」。2026-11-11 蓝牙整体移除后已废弃：
// 编号保留、不复用；收到一律回 ACK 4（UNKNOWN），不执行、不产生日志。
constexpr uint8_t CMD_BLE_CONTROL = 0xCB;

constexpr uint8_t INIT_COMMIT_RESERVED = 0x00;  // 0xC8[1] 保留字节，必须为 0
constexpr uint8_t INIT_FACTORY_MAGIC = 0x5A;    // 0xCA[0] 防误触 magic

// WiFi 模式取值（与 Wifi.h 的 WifiMode 一一对应）。
enum WifiConfigMode : uint8_t {
  WIFI_CFG_MODE_STA = 0,
  WIFI_CFG_MODE_AP = 1,
  WIFI_CFG_MODE_APSTA = 2
};
constexpr uint8_t WIFI_CFG_MODE_MAX = WIFI_CFG_MODE_APSTA;

// DAT_WIFI_STATUS 的连接状态取值（与 Wifi.h 的 WifiState 一一对应）。
enum WifiStateValue : uint8_t {
  WIFI_STATE_DISCONNECTED = 0,
  WIFI_STATE_LAN_ONLY = 1,
  WIFI_STATE_INTERNET = 2
};

// 凭据 PAYLOAD = [ssidLen u8][SSID(ssidLen)][密码(剩余字节)]，不再定长填充。
// SSID 1~32 B，密码 0（开放网络）或 8~63 B；LEN 最小 2、最大 96。
constexpr uint8_t WIFI_SSID_MAX_BYTES = 32;
constexpr uint8_t WIFI_PASSWORD_MAX_BYTES = 63;
constexpr uint8_t WIFI_PASSWORD_MIN_BYTES = 8;
constexpr uint8_t WIFI_CREDENTIAL_MAX_LEN =
    1 + WIFI_SSID_MAX_BYTES + WIFI_PASSWORD_MAX_BYTES;  // 96
constexpr uint8_t WIFI_CREDENTIAL_MIN_LEN = 2;          // 1 B ssidLen + 1 B SSID

// ---- 音频（0xD0~0xD3）----
constexpr uint8_t CMD_AUDIO_TEST = 0xD0;  // LEN=1，payload[0]=AUDIO_TEST_*
constexpr uint8_t CMD_VOLUME = 0xD1;      // LEN=1，0~100
constexpr uint8_t CMD_RECORD = 0xD2;      // LEN=1，录音秒数 1~10
constexpr uint8_t CMD_REQ_RECORD = 0xD3;  // LEN=0，仅保留编号，不执行

// ---- 动作/运动（0xE0~0xE8）----
constexpr uint8_t CMD_STOP = 0xE0;        // LEN=0
constexpr uint8_t CMD_FORWARD = 0xE1;     // LEN=1，速度百分比
constexpr uint8_t CMD_BACK = 0xE2;        // LEN=1，速度百分比
constexpr uint8_t CMD_LEFT = 0xE3;        // LEN=1，角速度百分比
constexpr uint8_t CMD_RIGHT = 0xE4;       // LEN=1，角速度百分比
constexpr uint8_t CMD_PITCH = 0xE5;       // LEN=1，云台俯仰绝对角度 0~255 → 0~180°
constexpr uint8_t CMD_YAW = 0xE6;         // LEN=1，云台水平绝对角度 0~255 → 0~360°
constexpr uint8_t CMD_LEFT_SHIFT = 0xE7;  // LEN=1，麦轮横移，本固件不支持
constexpr uint8_t CMD_RIGHT_SHIFT = 0xE8; // LEN=1，麦轮横移，本固件不支持
// 0xE9/0xEA 为万向轮原地自转预留。
// 0xEB：差速轮盘两分量 [drive int8][turn int8]，drive 正=前进、turn 正=左转。
constexpr uint8_t CMD_MOVE_VECTOR = 0xEB;
// 0xEC：云台轮盘两分量 [pan int8][tilt int8]，pan 正=向右、tilt 正=向上。
constexpr uint8_t CMD_GIMBAL_VECTOR = 0xEC;
// 0xED~0xEF 仍预留。

// 流式两分量命令的 PAYLOAD 长度：两个 int8。
constexpr uint8_t MOTION_VECTOR_LEN = 2;
constexpr uint8_t GIMBAL_VECTOR_LEN = 2;

// CMD_AUDIO_TEST 的 LEN=1 参数值。
constexpr uint8_t AUDIO_TEST_STARTUP_CHIME = 0;  // 播放上电提示音
constexpr uint8_t AUDIO_TEST_1KHZ = 1;           // 播放 1 kHz / 200 ms 测试音
constexpr uint8_t AUDIO_TEST_STOP = 2;           // 立即停止播放
constexpr uint8_t AUDIO_TEST_PARAM_MAX = AUDIO_TEST_STOP;

// CMD_VOLUME 的 LEN=1 参数范围：0 = 静音，100 = 满量程。
constexpr uint8_t VOLUME_PARAM_MIN = 0;
constexpr uint8_t VOLUME_PARAM_MAX = 100;

// CMD_RECORD 的 LEN=1 参数范围：录音秒数，1~10。
constexpr uint8_t RECORD_SECONDS_MIN = 1;
constexpr uint8_t RECORD_SECONDS_MAX = 10;

// 运动命令（前进/后退/原地转弯）参数为最大速度或最大回转角速度的百分比。
constexpr uint8_t MOTION_LEVEL_MAX = 100;

// ==================== LOG 模块编号（TYPE 低 4 bit，0~15）====================
enum LogModule : uint8_t {
  LOG_MOD_SYSTEM = 0,
  // 模块号 1：原 BLE 模块。2026-11-11 蓝牙移除后编号与事件码保留、不复用；
  // 仍在使用的是 CMD 协议诊断事件（去重/会话/收到命令），见日志.md。
  LOG_MOD_BLE = 1,
  LOG_MOD_LOG = 2,
  LOG_MOD_DAT = 3,
  LOG_MOD_TFT = 4,
  LOG_MOD_AUDIO = 5,
  LOG_MOD_MIC = 6,
  LOG_MOD_SPEAKER = 7,
  LOG_MOD_RECORD = 8,
  LOG_MOD_MOTOR = 9
  // 10~15 预留
};

// param 字节语义：0x01~0x1F = 模块内事件码；0x20~0x7F = 数值/码段；0x80~0xFF 预留。
constexpr uint8_t LOG_PARAM_EVENT_MAX = 0x1F;
constexpr uint8_t LOG_PARAM_NUM_BASE = 0x20;

// 模块号 1 的两段数值编码：0x20~0x2F = 未知 CMD 的 item；0x30~0x3F = 参数非法 CMD 的 item。
constexpr uint8_t LOG_BLE_CMD_UNKNOWN_BASE = 0x20;
constexpr uint8_t LOG_BLE_CMD_INVALID_BASE = 0x30;

inline constexpr uint8_t logBleCmdUnknownParam(uint8_t item) {
  return static_cast<uint8_t>(LOG_BLE_CMD_UNKNOWN_BASE + (item & 0x0F));
}

inline constexpr uint8_t logBleCmdInvalidParam(uint8_t item) {
  return static_cast<uint8_t>(LOG_BLE_CMD_INVALID_BASE + (item & 0x0F));
}

// WiFi 模块（LOG_MOD_SYSTEM）跨文件共用的两条事件码，与《日志.md》0x33 / 0x35 对应。
constexpr uint8_t WIFI_LOG_CONFIG_INVALID = LOG_PARAM_NUM_BASE + 19;
constexpr uint8_t WIFI_LOG_LEN_INVALID = LOG_PARAM_NUM_BASE + 21;

// 0x00 表示“只有事件码、无附加数值”。
enum SystemLogEvent : uint8_t {
  SYS_LOG_BANNER = 0x01,
  SYS_LOG_READY = 0x02,
  SYS_LOG_BOOT_STOP_TFT = 0x03,
  SYS_LOG_BOOT_STOP_BLE = 0x04,
  SYS_LOG_SESSION_INIT_OK = 0x05,
  SYS_LOG_SESSION_VERSION_MISMATCH = 0x06,
  SYS_LOG_QUERY_SEQ_HIT = 0x07,
  SYS_LOG_QUERY_SEQ_MISS = 0x08
};

// 模块号 1 的事件码。2026-11-11 蓝牙整体移除后，0x01~0x0D 与 0x14 不再产生、
// 编号保留不复用；0x0E~0x13 仍在 CMD 协议诊断中使用（见 日志.md）。
enum BleLogEvent : uint8_t {
  BLE_LOG_INIT_QUEUE_FAIL = 0x01,
  BLE_LOG_INIT_SERVER_FAIL = 0x02,
  BLE_LOG_INIT_SERVER_CALLBACK_FAIL = 0x03,
  BLE_LOG_INIT_SERVICE_FAIL = 0x04,
  BLE_LOG_INIT_CHARACTERISTIC_FAIL = 0x05,
  BLE_LOG_INIT_RX_CALLBACK_FAIL = 0x06,
  BLE_LOG_INIT_ADVERTISING_UNAVAILABLE = 0x07,
  BLE_LOG_INIT_ADVERTISING_FAIL = 0x08,
  BLE_LOG_RX_BUFFER_OVERFLOW = 0x09,
  BLE_LOG_RX_WRITE_TOO_LARGE = 0x0A,
  BLE_LOG_EVENT_QUEUE_FULL = 0x0B,
  BLE_LOG_CLIENT_CONNECTED = 0x0C,
  BLE_LOG_CLIENT_DISCONNECTED = 0x0D,
  BLE_LOG_CMD_SEQ_ZERO = 0x0E,
  BLE_LOG_CMD_SESSION_PENDING = 0x0F,
  BLE_LOG_CMD_DUPLICATE = 0x10,
  BLE_LOG_CMD_REQ_RECORD_UNSUPPORTED = 0x11,
  BLE_LOG_CMD_MECANUM_UNSUPPORTED = 0x12,
  BLE_LOG_CMD_RX = 0x13,
  BLE_LOG_RX_LEN_INVALID = 0x14,  // TYPE 未知或 LEN 与 TYPE 允许范围不符，整帧丢弃
  BLE_LOG_PENDING_RSV = 0x15
};

enum TftLogEvent : uint8_t {
  TFT_LOG_SPIFFS_FAIL = 0x01,
  TFT_LOG_FONT_MISSING = 0x02,
  TFT_LOG_FONT_LOAD_FAIL = 0x03
};

enum DatLogEvent : uint8_t {
  DAT_LOG_REQ_SENT = 0x01,
  DAT_LOG_REQ_REJECTED = 0x02,
  DAT_LOG_REQ_BAD_CODE = 0x03
};

enum AudioLogEvent : uint8_t {
  AUDIO_LOG_I2S_FAIL = 0x01,
  AUDIO_LOG_MIC_FAIL = 0x02,
  AUDIO_LOG_TEST_AUDIO_NOT_READY = 0x03,
  AUDIO_LOG_TEST_RECORD_BUSY = 0x04,
  AUDIO_LOG_TEST_CUE_BUSY = 0x05
};

enum SpeakerLogEvent : uint8_t {
  SPK_LOG_NOT_READY = 0x01
};

enum RecordLogEvent : uint8_t {
  REC_LOG_BUFFER_ALLOC_FAIL = 0x01,
  REC_LOG_NOT_READY = 0x02,
  REC_LOG_MIC_NOT_READY = 0x03,
  REC_LOG_BUSY = 0x04,
  REC_LOG_CUE_BUSY = 0x05,
  REC_LOG_TOO_LONG = 0x06,
  REC_LOG_START_BUSY = 0x07,
  REC_LOG_START_NO_MIC = 0x08,
  REC_LOG_START_TOO_LONG = 0x09,
  REC_LOG_START_NO_BUFFER = 0x0A
};

enum MotorLogEvent : uint8_t {
  MOTOR_LOG_TIMEOUT_AUTO_STOP = 0x01
};

// 由等级与模块拼出日志记录 TYPE：高 2 位 KIND_LOG、中间 2 位等级、低 4 位模块。
// 全文件唯一按位拼 TYPE 的地方；levelZone 取 LOG_ZONE_*，module 取 LOG_MOD_*（0~15）。
inline constexpr uint8_t makeLogType(uint8_t levelZone, uint8_t module) {
  return static_cast<uint8_t>((static_cast<uint8_t>(KIND_LOG) << 6) |
                              (static_cast<uint8_t>((levelZone & 0x03) << 4)) |
                              static_cast<uint8_t>(module & 0x0F));
}

// LOG 帧容器 TYPE：所有 LOG 帧第 3 字节固定 0x42（KIND=LOG、D 分区、模块 LOG_MOD_LOG），
// 记录的真实 TYPE 在 PAYLOAD 内，每条记录自带 TYPE。
constexpr uint8_t LOG_FRAME_TYPE = makeLogType(LOG_ZONE_DEBUG, LOG_MOD_LOG);
static_assert(LOG_FRAME_TYPE == 0x42, "LOG frame container TYPE must be 0x42");

// ==================== LEN 合法性校验表 ====================
// 帧长完全由 LEN 决定；本表只校验「TYPE 允许的 PAYLOAD 长度」，不参与定界。
// 未登记的 TYPE 一律非法，解析器遇到即整帧丢弃并重扫 0xAA。
// 参数：type 为整字节 TYPE；len 为 PAYLOAD 字节数（不含 LEN/CRC）。
// 返回：true = 该 TYPE 允许这个长度；false = 未知 TYPE 或长度不符。
// 编译期可用于 static_assert；运行期无锁、无副作用，可在 ISR 里调用。
// 这是唯一的长度真源，三个帧类与 WiFi 解析器都只查这一张表。
inline constexpr bool payloadLengthValid(uint8_t type, uint8_t len) {
  return kindOf(type) == KIND_LOG
      ? (len >= LOG_RECORD_BYTES && len <= LOG_MAX_PAYLOAD_BYTES &&
         (len % LOG_RECORD_BYTES) == 0)
      : type == ACK_RESULT ? len == ACK_PAYLOAD_LEN
      : type == ACK_SESSION_INIT ? len == ACK_PAYLOAD_LEN
      : type == ACK_ERROR ? len == ACK_PAYLOAD_LEN
      : type == DAT_BAT ? len == DAT_BAT_LEN
      : type == DAT_US ? len == DAT_US_LEN
      : type == DAT_WHEEL ? len == DAT_WHEEL_LEN
      : type == DAT_INFO_VERSION ? len == DAT_INFO_VERSION_LEN
      : type == DAT_INFO_POWER ? len == DAT_INFO_POWER_LEN
      : type == DAT_INFO_STORAGE ? len == DAT_INFO_STORAGE_LEN
      : type == DAT_MEM ? len == DAT_MEM_LEN
      : type == DAT_REQ ? len == DAT_REQ_LEN
      : type == DAT_WIFI_STATUS ? len == DAT_WIFI_STATUS_LEN
      : type == DAT_INIT_STATE ? len == DAT_INIT_STATE_LEN
      : type == CMD_SESSION_INIT ? len == 1
      : type == CMD_QUERY_SEQ ? len == 1
      : type == CMD_REQ_INFO ? len == 0
      : type == CMD_WIFI_MODE ? len == 1
      : type == CMD_WIFI_STA_CONFIG
          ? (len >= WIFI_CREDENTIAL_MIN_LEN && len <= WIFI_CREDENTIAL_MAX_LEN)
      : type == CMD_WIFI_AP_CONFIG
          ? (len >= WIFI_CREDENTIAL_MIN_LEN && len <= WIFI_CREDENTIAL_MAX_LEN)
      : type == CMD_WIFI_STATUS ? len == 0
      : type == CMD_INIT_COMMIT ? len == 2
      : type == CMD_INIT_STATUS ? len == 0
      : type == CMD_INIT_FACTORY_RESET ? len == 1
      // 0xCB 已废弃：任意 LEN 都接住并回 ACK 4，交给 Cmd 统一处理。
      : type == CMD_BLE_CONTROL ? true
      : type == CMD_AUDIO_TEST ? len == 1
      : type == CMD_VOLUME ? len == 1
      : type == CMD_RECORD ? len == 1
      : type == CMD_REQ_RECORD ? len == 0
      : type == CMD_STOP ? len == 0
      : type == CMD_FORWARD ? len == 1
      : type == CMD_BACK ? len == 1
      : type == CMD_LEFT ? len == 1
      : type == CMD_RIGHT ? len == 1
      : type == CMD_PITCH ? len == 1
      : type == CMD_YAW ? len == 1
      : type == CMD_LEFT_SHIFT ? len == 1
      : type == CMD_RIGHT_SHIFT ? len == 1
      : type == CMD_MOVE_VECTOR ? len == MOTION_VECTOR_LEN
      : type == CMD_GIMBAL_VECTOR ? len == GIMBAL_VECTOR_LEN
      : false;
}

// 编译期校验：关键 TYPE 编码与允许长度必须与协议文档一致。
static_assert(CMD_SESSION_INIT == 0xC0, "CMD_SESSION_INIT must map to TYPE 0xC0");
static_assert(CMD_QUERY_SEQ == 0xC1, "CMD_QUERY_SEQ must map to TYPE 0xC1");
static_assert(CMD_REQ_INFO == 0xC2, "CMD_REQ_INFO must map to TYPE 0xC2");
static_assert(CMD_WIFI_MODE == 0xC4, "CMD_WIFI_MODE must map to TYPE 0xC4");
static_assert(CMD_WIFI_STA_CONFIG == 0xC5,
              "CMD_WIFI_STA_CONFIG must map to TYPE 0xC5");
static_assert(CMD_WIFI_AP_CONFIG == 0xC6,
              "CMD_WIFI_AP_CONFIG must map to TYPE 0xC6");
static_assert(CMD_WIFI_STATUS == 0xC7, "CMD_WIFI_STATUS must map to TYPE 0xC7");
static_assert(CMD_INIT_COMMIT == 0xC8, "CMD_INIT_COMMIT must map to TYPE 0xC8");
static_assert(CMD_INIT_STATUS == 0xC9, "CMD_INIT_STATUS must map to TYPE 0xC9");
static_assert(CMD_INIT_FACTORY_RESET == 0xCA,
              "CMD_INIT_FACTORY_RESET must map to TYPE 0xCA");
static_assert(CMD_BLE_CONTROL == 0xCB, "CMD_BLE_CONTROL must map to TYPE 0xCB");
static_assert(CMD_AUDIO_TEST == 0xD0, "CMD_AUDIO_TEST must map to TYPE 0xD0");
static_assert(CMD_VOLUME == 0xD1, "CMD_VOLUME must map to TYPE 0xD1");
static_assert(CMD_RECORD == 0xD2, "CMD_RECORD must map to TYPE 0xD2");
static_assert(CMD_STOP == 0xE0, "CMD_STOP must map to TYPE 0xE0");
static_assert(CMD_FORWARD == 0xE1, "CMD_FORWARD must map to TYPE 0xE1");
static_assert(CMD_MOVE_VECTOR == 0xEB, "CMD_MOVE_VECTOR must map to TYPE 0xEB");
static_assert(CMD_GIMBAL_VECTOR == 0xEC,
              "CMD_GIMBAL_VECTOR must map to TYPE 0xEC");
static_assert(DAT_BAT == 0x80, "DAT_BAT must map to TYPE 0x80");
static_assert(DAT_WIFI_STATUS == 0x86, "DAT_WIFI_STATUS must map to TYPE 0x86");
static_assert(DAT_INIT_STATE == 0x87, "DAT_INIT_STATE must map to TYPE 0x87");
static_assert(payloadLengthValid(DAT_INIT_STATE, DAT_INIT_STATE_LEN),
              "DAT_INIT_STATE LEN mismatch");
static_assert(DAT_MEM == 0x90, "DAT_MEM must map to TYPE 0x90");
static_assert(DAT_REQ == 0xA0, "DAT_REQ must map to TYPE 0xA0");
static_assert(ACK_RESULT == 0x00, "ACK_RESULT must map to TYPE 0x00");
static_assert(ACK_SESSION_INIT == 0x01, "ACK_SESSION_INIT must map to TYPE 0x01");
static_assert(ACK_ERROR == 0x10, "ACK_ERROR must map to TYPE 0x10");
static_assert(LOG_RECORD_BYTES == 3, "LOG record must be TYPE+LEN+param");
static_assert(LOG_MAX_PAYLOAD_BYTES == 24, "LOG payload must cap at 8 records");
static_assert(payloadLengthValid(CMD_SESSION_INIT, 1), "LEN mismatch");
static_assert(payloadLengthValid(CMD_STOP, 0), "LEN mismatch");
static_assert(payloadLengthValid(CMD_MOVE_VECTOR, 2), "LEN mismatch");
static_assert(payloadLengthValid(CMD_GIMBAL_VECTOR, 2), "LEN mismatch");
static_assert(!payloadLengthValid(CMD_MOVE_VECTOR, 1), "LEN mismatch");
static_assert(payloadLengthValid(CMD_REQ_INFO, 0), "LEN mismatch");
static_assert(payloadLengthValid(CMD_WIFI_MODE, 1), "LEN mismatch");
static_assert(payloadLengthValid(CMD_WIFI_STA_CONFIG, 96), "LEN mismatch");
static_assert(!payloadLengthValid(CMD_WIFI_STA_CONFIG, 97), "LEN mismatch");
static_assert(!payloadLengthValid(CMD_WIFI_STA_CONFIG, 1), "LEN mismatch");
static_assert(payloadLengthValid(DAT_BAT, 2), "LEN mismatch");
static_assert(payloadLengthValid(DAT_WHEEL, 4), "LEN mismatch");
static_assert(payloadLengthValid(DAT_MEM, DAT_MEM_LEN), "LEN mismatch");
static_assert(payloadLengthValid(DAT_WIFI_STATUS, DAT_WIFI_STATUS_LEN), "LEN mismatch");
static_assert(payloadLengthValid(makeLogType(LOG_ZONE_WARN, LOG_MOD_BLE), 3),
              "LOG frame must carry whole records");
static_assert(!payloadLengthValid(makeLogType(LOG_ZONE_WARN, LOG_MOD_BLE), 4),
              "LOG frame must not carry partial records");

// ==================== PAYLOAD 大端读写 ====================
// 多字节数值一律高字节在前；定点数由调用方先放大再取整。
// 调用方必须保证 payload 至少 2 B；本组函数不做越界检查，可在 ISR 里调用。
inline uint16_t readU16(const uint8_t* payload) {
  return static_cast<uint16_t>(
      (static_cast<uint16_t>(payload[0]) << 8) | payload[1]);
}

inline int16_t readI16(const uint8_t* payload) {
  return static_cast<int16_t>(readU16(payload));
}

inline void writeU16(uint8_t* payload, uint16_t value) {
  payload[0] = static_cast<uint8_t>((value >> 8) & 0xFF);
  payload[1] = static_cast<uint8_t>(value & 0xFF);
}

inline void writeI16(uint8_t* payload, int16_t value) {
  writeU16(payload, static_cast<uint16_t>(value));
}

// CRC-8/ATM：覆盖 HEAD + SEQ + TYPE + LEN + PAYLOAD（到最后一个负载字节）。
// 全工程唯一实现：Cmd / Dat / Log 的出口都调用它，各模块不再重算。
// 参数：data 起点必须是帧首 0xAA；length = 4 + LEN（不含 CRC 自身）。
// 返回：1 B 校验值。纯计算、无锁、不阻塞，可在 ISR 里调用；
// 与接收方向共用同一份实现，避免两处算法漂移导致「自测通过、对端拒帧」。
inline uint8_t crc8(const uint8_t* data, size_t length) {
  uint8_t crc = 0x00;
  for (size_t index = 0; index < length; ++index) {
    crc ^= data[index];
    for (uint8_t bit = 0; bit < 8; ++bit) {
      crc = (crc & 0x80) != 0 ? static_cast<uint8_t>((crc << 1) ^ 0x07)
                              : static_cast<uint8_t>(crc << 1);
    }
  }
  return crc;
}

// ==================== 组帧助手（三个帧类共用） ====================
// 帧总长 = 5 + payloadLen；调用方按此长度准备缓冲。
inline constexpr uint16_t frameBytesOf(uint8_t payloadLen) {
  return FRAME_OVERHEAD_BYTES + payloadLen;
}

// 写 HEAD | SEQ | TYPE | LEN，返回帧总长，便于后续填 PAYLOAD。
// 调用方须先自行确认 payloadLen 通过 payloadLengthValid()；
// 本函数不校验长度，也不清 PAYLOAD，PAYLOAD 由调用方按需写入。
// 无锁、无分配、不阻塞，可在 ISR 里调用（缓冲由调用方提供）。
inline uint16_t beginFrame(uint8_t* frame, uint8_t seq, uint8_t type,
                           uint8_t payloadLen) {
  frame[0] = FRAME_HEAD;
  frame[1] = seq;
  frame[2] = type;
  frame[3] = payloadLen;
  return frameBytesOf(payloadLen);
}

// 在帧尾写入 CRC8（覆盖到最后一个 PAYLOAD 字节）。
// 必须在 PAYLOAD 全部写完后调用一次；重复调用会算进半成品字节，帧被对端丢弃。
// 无锁、不阻塞，可在 ISR 里调用。
inline void finishFrame(uint8_t* frame, uint8_t payloadLen) {
  const uint16_t total = frameBytesOf(payloadLen);
  frame[total - 1] = crc8(frame, total - 1);
}

// ==================== 解析定界 ====================
// 从帧头起累计 4 B 后才能读出 LEN；收满 5 + LEN 即为一帧。
// 调用方必须已确认前 4 B 是 HEAD|SEQ|TYPE|LEN；不做 TYPE 合法性检查，
// 解析器应在调用本函数前先用 payloadLengthValid() 拒绝非法 LEN。
inline constexpr uint16_t frameBytesFromHeader(const uint8_t* frame) {
  return frameBytesOf(frame[3]);
}

}  // namespace JiangLingProtocol

#endif  // JIANGLING_PROTOCOL_H
