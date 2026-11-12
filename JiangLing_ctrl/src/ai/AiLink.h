/*
 * ============================================================
 * ai/AiLink.h —— 小智云 AI 通道（OTA / WSS / 音频 / 字幕 / 会话）
 * ============================================================
 * 【职责】
 *   - WiFi 不归本模块管：连接与三态由 main/wife/Wifi 负责（凭据只存 RAM，不落 NVS），
 *     本模块只轮询 Wifi.hasInternet()；
 *   - OTA 取 websocket.url / token / version，激活码打到串口；
 *   - WSS 握手、hello、心跳、断线重连；
 *   - 上行：Mic → 编解码接口 → WSS 二进制帧；
 *   - 下行：WSS 二进制帧 → 编解码接口 → 重采样到 16 kHz → Speaker；
 *   - 字幕：stt / tts.sentence_start / llm 文本暂存到 Subtitle；
 *   - 会话：wake() 开始一轮问答，tts.stop 起 30 s 空闲关闭。
 *
 * 【与协议帧的关系】
 *   不改 V21 帧格式、不往 Cmd/Dat/Log 队列塞任何东西；
 *   需要通知 App 时由后续版本通过现有 Cmd 出口新增命令，本模块只提供接口。
 * ============================================================
 */
#ifndef AI_LINK_H
#define AI_LINK_H

#include <Arduino.h>

namespace AiLink {

enum class SessionState : uint8_t {
  Offline = 0,     // 没有外网或 WSS 未连接
  Ready,           // 已握手，等待唤醒
  Listening,       // 已发 listen start，正在上行
  Speaking,        // 服务端 TTS 播报中（半双工：停上行）
};

// 启动 AI 任务（只启动一次）。不阻塞；凭据缺失时由 Wifi 决定是否可用。
bool begin();

bool isStarted();

// 触发一轮问答：唤醒词、按键都可以调用。
// 未连接时只置一个 pending 标志，连上后自动开始。
void wake();

// 关闭当前会话（远程操控开启时调用）：发 listen stop，恢复等待唤醒。
void closeSession();

SessionState state();
const char* stateName(SessionState value);

}  // namespace AiLink

#endif  // AI_LINK_H
