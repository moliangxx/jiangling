/*
 * ============================================================
 * main/wife/Voice.h —— 语音通话通道（独立 UDP 8888 / 轻量帧）
 * ============================================================
 * 【职责】
 *   1. 用 WiFiUDP 在 8888 端口收/发语音轻量帧（双向同端口，板子 SoftAP
 *      地址 192.168.4.1）；对端地址由会话首包的源 IP:port 学习，不硬编码；
 *   2. 会话期间常驻读麦泵：每 20 ms 从 Mic 读 320 采样（640 B），组包上行；
 *   3. 下行包进 8 槽抖动缓冲，按时间戳顺序、攒 3 包起播，缺包用上一帧补；
 *   4. 半双工（一期不做 AEC）：收到对端语音即停采并播放，对端转到静音心跳
 *      后恢复读麦上行；两个方向永不重叠；
 *   5. 会话边界见《语音.md》§1.5：App 开关是唯一开关源，不设空闲超时，
 *      只有 bit5 结束帧 / 2 s 无包 / WiFi 掉线 / 音频故障才收尾，收尾 ≤ 500 ms。
 *
 * 【帧格式（权威定义在《语音.md》§10）】
 *   [flags 1 B][ts_lo 1 B][ts_hi 1 B][PCM16LE 640 B] = 643 B，20 ms / 50 包每秒
 *   flags: bit7 方向(0=App→板, 1=板→App) / bit6 START / bit5 END /
 *          bit4 SILENCE / bit3~0 保留填 0
 *   时间戳只用于排序、丢包判断与抖动缓冲，不做法定序号。
 *
 * 【不做的事】
 *   不做去重、不做 ACK、不做 CRC、不进能力闸门、不参与优先级队列；
 *   不碰 ctrl 的 TCP 8080、不碰 LOG 帧出口、不改日志等级。
 * ============================================================
 */
#ifndef MAIN_WIFE_VOICE_H
#define MAIN_WIFE_VOICE_H

#include <Arduino.h>
#include "../../base/Protocol.h"

namespace Voice {

// 传输：独立 UDP，双向同端口。
constexpr uint16_t UDP_PORT = 8888;

// 帧：3 B 头 + 640 B 负载 = 643 B，20 ms 一帧（50 包/s）。
constexpr size_t HEADER_BYTES = 3;
constexpr size_t SAMPLES_PER_PACKET = 320;  // 20 ms @ 16 kHz
constexpr size_t PCM_BYTES = SAMPLES_PER_PACKET * 2;
constexpr size_t PACKET_BYTES = HEADER_BYTES + PCM_BYTES;

// flags 位（byte0）。
constexpr uint8_t FLAG_BOARD_TO_APP = 0x80;  // bit7 方向：1 = 板 → App
constexpr uint8_t FLAG_START = 0x40;         // bit6 会话首包
constexpr uint8_t FLAG_END = 0x20;           // bit5 会话结束帧
constexpr uint8_t FLAG_SILENCE = 0x10;       // bit4 静音 / DTX

// 节拍与收尾（全部与「空闲」无关）。
constexpr uint32_t FRAME_INTERVAL_MS = 20;   // 一帧音频时长
constexpr uint32_t RX_TIMEOUT_MS = 2000;     // 2 s 无任何包判链路断开
constexpr uint32_t HEARTBEAT_MS = 500;       // 只播/只听方向每 500 ms 静音心跳
constexpr uint32_t CLOSE_MS = 500;           // CLOSING 收尾上限
constexpr uint32_t PLAY_HOLD_MS = 300;       // 半双工方向迟滞

// 抖动缓冲：8 槽 = 160 ms，攒 3 包（60 ms）起播；超水位丢最旧。
constexpr uint8_t JITTER_SLOTS = 8;
constexpr uint8_t JITTER_PREROLL = 3;

// 本地日志事件码：沿用 LOG_PARAM_NUM_BASE 数值段，不新增 TYPE、不改 Protocol.h。
constexpr uint8_t VOICE_LOG_SESSION_OPEN =
    JiangLingProtocol::LOG_PARAM_NUM_BASE + 22;
constexpr uint8_t VOICE_LOG_SESSION_CLOSE =
    JiangLingProtocol::LOG_PARAM_NUM_BASE + 23;
constexpr uint8_t VOICE_LOG_LINK_LOST =
    JiangLingProtocol::LOG_PARAM_NUM_BASE + 24;
constexpr uint8_t VOICE_LOG_AUDIO_FAULT =
    JiangLingProtocol::LOG_PARAM_NUM_BASE + 25;

// 启动语音通道：绑定 UDP 8888（WiFi 未就绪时由 update() 自动重试）。
void begin();

// 主循环调用：收包、读麦泵、抖动缓冲播放、会话状态机；全程非阻塞。
void update();

// 当前是否处于会话（ACTIVE 或 CLOSING）。
bool isActive();

}  // namespace Voice

#endif  // MAIN_WIFE_VOICE_H
