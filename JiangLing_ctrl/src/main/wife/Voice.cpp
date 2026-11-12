/*
 * Voice.cpp —— 语音轻量帧的 UDP 收发、读麦泵与抖动缓冲。
 *
 * 设计依据：index/江灵通信协议/语音.md §10（帧定义）、§1.5（会话边界）、
 * §5.3.1（半双工）、§7（状态机）。
 */
#include "Voice.h"

#include <Arduino.h>
#include <WiFi.h>
#include <WiFiUdp.h>
#include <string.h>

#include "../../ai/AiLink.h"
#include "../../ai/WakeWord.h"
#include "../../base/util/log/Log.h"
#include "../audio/Mic.h"
#include "../audio/Speaker.h"
#include "Wifi.h"

namespace {

using namespace JiangLingProtocol;

enum class State : uint8_t {
  Idle = 0,     // 无会话
  Active = 1,   // 双向流（半双工调度）
  Closing = 2,  // 收尾（≤ 500 ms）
  Fault = 3,    // 音频未就绪，拒收
};

// 抖动缓冲的一槽：时间戳 + 一帧 PCM16。
struct Slot {
  uint16_t ts;
  int16_t pcm[Voice::SAMPLES_PER_PACKET];
};

State s_state = State::Idle;

WiFiUDP s_udp;
bool s_socketStarted = false;
uint32_t s_nextSocketRetryMs = 0;

// 对端地址：会话首包学习，会话结束遗忘。
IPAddress s_peerIp;
uint16_t s_peerPort = 0;
bool s_peerValid = false;

uint32_t s_lastRxMs = 0;
uint32_t s_lastNonSilentRxMs = 0;  // 0 = 本会话还没收到过非静音包
uint32_t s_lastTxMs = 0;
uint32_t s_nextMicMs = 0;
uint32_t s_nextPlayMs = 0;
uint32_t s_closingUntilMs = 0;
uint32_t s_faultUntilMs = 0;
bool s_playbackMode = false;

// 抖动缓冲。
Slot s_slots[Voice::JITTER_SLOTS];
uint8_t s_slotCount = 0;
bool s_primed = false;
bool s_hasPlayed = false;
uint16_t s_lastPlayedTs = 0;
int16_t s_lastFrame[Voice::SAMPLES_PER_PACKET];
uint8_t s_plcCount = 0;

// 读麦泵：不足 320 采样时暂存，凑满一帧再发。
int16_t s_micBlock[Voice::SAMPLES_PER_PACKET];
size_t s_micFilled = 0;

uint8_t s_txPacket[Voice::PACKET_BYTES];
uint8_t s_rxPacket[Voice::PACKET_BYTES];

bool audioReady() {
  return Mic::isReady() && Speaker::isReady();
}

// 16 bit 时间戳差值（约 65.5 s 回绕）；只用于排序，不是法定序号。
int16_t tsDiff(uint16_t a, uint16_t b) {
  return static_cast<int16_t>(a - b);
}

void resetJitter() {
  s_slotCount = 0;
  s_primed = false;
  s_hasPlayed = false;
  s_plcCount = 0;
  s_lastPlayedTs = 0;
}

void resetMic() {
  s_micFilled = 0;
  s_nextMicMs = 0;
}

void buildPacket(uint8_t flags, const int16_t* samples, uint16_t ts) {
  s_txPacket[0] = static_cast<uint8_t>(flags | Voice::FLAG_BOARD_TO_APP);
  s_txPacket[1] = static_cast<uint8_t>(ts & 0xFF);
  s_txPacket[2] = static_cast<uint8_t>((ts >> 8) & 0xFF);

  uint8_t* dst = s_txPacket + Voice::HEADER_BYTES;
  if (samples == nullptr) {
    memset(dst, 0, Voice::PCM_BYTES);
    return;
  }
  // PCM16 小端：低字节在前。
  for (size_t i = 0; i < Voice::SAMPLES_PER_PACKET; ++i) {
    const uint16_t raw = static_cast<uint16_t>(samples[i]);
    dst[2 * i] = static_cast<uint8_t>(raw & 0xFF);
    dst[2 * i + 1] = static_cast<uint8_t>((raw >> 8) & 0xFF);
  }
}

bool sendPacket(const int16_t* samples, uint8_t flags) {
  if (!s_peerValid || !s_socketStarted) {
    return false;
  }
  buildPacket(flags, samples, static_cast<uint16_t>(millis() & 0xFFFF));
  if (!s_udp.beginPacket(s_peerIp, s_peerPort)) {
    return false;
  }
  s_udp.write(s_txPacket, Voice::PACKET_BYTES);
  s_udp.endPacket();
  s_lastTxMs = millis();
  return true;
}

// 按时间戳插入：重复/回退（早于已播放帧）的包直接丢。
void pushDownlink(uint16_t ts, const uint8_t* payload) {
  if (s_hasPlayed && tsDiff(ts, s_lastPlayedTs) <= 0) {
    return;
  }
  for (uint8_t i = 0; i < s_slotCount; ++i) {
    if (s_slots[i].ts == ts) {
      return;
    }
  }
  // 超水位丢最旧，宁可断一拍也不让延迟无限增长。
  if (s_slotCount >= Voice::JITTER_SLOTS) {
    memmove(&s_slots[0], &s_slots[1],
            sizeof(Slot) * (Voice::JITTER_SLOTS - 1));
    --s_slotCount;
  }

  uint8_t pos = s_slotCount;
  while (pos > 0 && tsDiff(ts, s_slots[pos - 1].ts) < 0) {
    s_slots[pos] = s_slots[pos - 1];
    --pos;
  }
  s_slots[pos].ts = ts;
  for (size_t i = 0; i < Voice::SAMPLES_PER_PACKET; ++i) {
    s_slots[pos].pcm[i] =
        static_cast<int16_t>(payload[2 * i] |
                             (static_cast<uint16_t>(payload[2 * i + 1]) << 8));
  }
  ++s_slotCount;
}

void popFront() {
  if (s_slotCount == 0) {
    return;
  }
  memmove(&s_slots[0], &s_slots[1],
          sizeof(Slot) * (Voice::JITTER_SLOTS - 1));
  --s_slotCount;
}

// 播放侧：攒够 3 包起播，之后每 20 ms 一帧；缺包时最多补 2 帧上一帧。
void playNext(uint32_t now) {
  if (!s_primed) {
    if (s_slotCount < Voice::JITTER_PREROLL) {
      return;
    }
    s_primed = true;
    s_nextPlayMs = now;
  }
  if (static_cast<int32_t>(now - s_nextPlayMs) < 0) {
    return;
  }

  if (s_slotCount == 0) {
    if (s_hasPlayed && s_plcCount < 2) {
      Speaker::play(s_lastFrame, Voice::SAMPLES_PER_PACKET);
      ++s_plcCount;
    }
    s_nextPlayMs = now + Voice::FRAME_INTERVAL_MS;
    return;
  }

  const size_t queued =
      Speaker::play(s_slots[0].pcm, Voice::SAMPLES_PER_PACKET);
  if (queued == 0) {
    // DMA 满：保留这一帧，稍后重试（不推进 20 ms 节拍）。
    s_nextPlayMs = now + 5;
    return;
  }

  s_lastPlayedTs = s_slots[0].ts;
  s_hasPlayed = true;
  s_plcCount = 0;
  memcpy(s_lastFrame, s_slots[0].pcm, sizeof(s_lastFrame));
  popFront();

  s_nextPlayMs += Voice::FRAME_INTERVAL_MS;
  if (static_cast<int32_t>(now - s_nextPlayMs) > static_cast<int32_t>(Voice::FRAME_INTERVAL_MS)) {
    // 落后超过一帧就丢掉欠账，避免越播越慢。
    s_nextPlayMs = now + Voice::FRAME_INTERVAL_MS;
  }
}

// 读麦泵：按 20 ms 节奏凑满 320 采样（640 B）后组包上行。
void pumpMic(uint32_t now) {
  if (!Mic::isReady()) {
    return;
  }
  if (static_cast<int32_t>(now - s_nextMicMs) < 0) {
    return;
  }
  s_nextMicMs = (static_cast<int32_t>(now - s_nextMicMs) > 40)
                      ? now + Voice::FRAME_INTERVAL_MS
                      : s_nextMicMs + Voice::FRAME_INTERVAL_MS;

  size_t filled = s_micFilled;
  while (filled < Voice::SAMPLES_PER_PACKET) {
    const size_t n =
        Mic::read(s_micBlock + filled, Voice::SAMPLES_PER_PACKET - filled);
    if (n == 0) {
      break;
    }
    filled += n;
  }
  s_micFilled = 0;
  if (filled == 0) {
    return;
  }
  if (filled < Voice::SAMPLES_PER_PACKET) {
    memset(s_micBlock + filled, 0,
           (Voice::SAMPLES_PER_PACKET - filled) * sizeof(int16_t));
  }
  sendPacket(s_micBlock, 0);  // 只带方向位（bit7）
}

void beginSession(const IPAddress& ip, uint16_t port, uint32_t now) {
  s_peerIp = ip;
  s_peerPort = port;
  s_peerValid = true;
  s_state = State::Active;

  resetJitter();
  resetMic();
  s_playbackMode = false;
  s_lastNonSilentRxMs = 0;
  s_lastRxMs = now;
  s_nextPlayMs = now;
  s_lastTxMs = now - Voice::HEARTBEAT_MS;

  // 硬互斥：语音一开，AI 立即让位（语音.md §1.7、§1.9）。
  AiLink::closeSession();
  // 置麦克风占用标志：App 通话期间 AI / 唤醒词都不得读麦。
  Mic::setBusy(true);
  WakeWord::pause();

  // 回一帧 START（静音负载），App 收到即确认板子已进入会话。
  sendPacket(nullptr, Voice::FLAG_START | Voice::FLAG_SILENCE);

  JL_LOGI(LOG_MOD_AUDIO, Voice::VOICE_LOG_SESSION_OPEN,
          LOG_SERIAL_TEXT("Voice session open"));
}

void enterFault(uint32_t now) {
  s_state = State::Fault;
  s_faultUntilMs = now + Voice::RX_TIMEOUT_MS;
  s_peerValid = false;
  resetJitter();
  resetMic();
  s_playbackMode = false;
  JL_LOGE(LOG_MOD_AUDIO, Voice::VOICE_LOG_AUDIO_FAULT,
          LOG_SERIAL_TEXT("Voice refused: audio not ready"));
}

void enterClosing(bool sendEnd, uint32_t now) {
  if (s_state == State::Closing) {
    return;
  }
  s_state = State::Closing;
  s_closingUntilMs = now + Voice::CLOSE_MS;
  resetJitter();
  resetMic();
  s_playbackMode = false;
  // 收尾阶段不再读麦、不再播放；回一帧 END（若对端还在）。
  if (sendEnd) {
    sendPacket(nullptr, Voice::FLAG_END | Voice::FLAG_SILENCE);
  }
  JL_LOGI(LOG_MOD_AUDIO, Voice::VOICE_LOG_SESSION_CLOSE,
          LOG_SERIAL_TEXT("Voice session closing"));
}

void finishSession() {
  s_state = State::Idle;
  s_peerValid = false;
  resetJitter();
  resetMic();
  s_playbackMode = false;
  s_lastNonSilentRxMs = 0;
  s_lastRxMs = 0;
  // 通话结束才清占用位；AI 在下一轮采集前重新检查 Mic::isBusy()。
  Mic::setBusy(false);
  WakeWord::resume();
}

void handleIncoming(const IPAddress& srcIp, uint16_t srcPort, int length,
                    uint32_t now) {
  const uint8_t flags = s_rxPacket[0];
  if ((flags & Voice::FLAG_BOARD_TO_APP) != 0) {
    return;  // 只处理 App → 板
  }

  if (s_state == State::Idle) {
    if (!audioReady()) {
      enterFault(now);
      return;
    }
    // 首包即会话开始：对端地址由源 IP:port 学习。
    beginSession(srcIp, srcPort, now);
  }
  if (s_state != State::Active) {
    return;
  }
  if (!(srcIp == s_peerIp) || srcPort != s_peerPort) {
    return;
  }

  s_lastRxMs = now;

  if ((flags & Voice::FLAG_END) != 0) {
    enterClosing(true, now);
    return;
  }
  if ((flags & Voice::FLAG_SILENCE) != 0) {
    return;  // 静音心跳只用于保活与方向判断
  }
  if (length < static_cast<int>(Voice::PACKET_BYTES)) {
    return;
  }

  const uint16_t ts =
      static_cast<uint16_t>(s_rxPacket[1] |
                            (static_cast<uint16_t>(s_rxPacket[2]) << 8));
  pushDownlink(ts, s_rxPacket + Voice::HEADER_BYTES);
  s_lastNonSilentRxMs = now;
}

void readIncoming(uint32_t now) {
  int size = s_udp.parsePacket();
  uint8_t bursts = 0;
  while (size > 0 && bursts < 16) {
    const IPAddress srcIp = s_udp.remoteIP();
    const uint16_t srcPort = s_udp.remotePort();
    const int packetSize = size;
    const int readLength = s_udp.read(s_rxPacket, Voice::PACKET_BYTES);
    size = s_udp.parsePacket();
    ++bursts;
    // 长度必须与数据报完全一致：超长包会被 read 截断，余量再被 parsePacket()
    // 当成新包解析，必须整包丢弃。
    if (packetSize > static_cast<int>(Voice::PACKET_BYTES) ||
        readLength != packetSize ||
        readLength < static_cast<int>(Voice::HEADER_BYTES)) {
      continue;
    }
    handleIncoming(srcIp, srcPort, readLength, now);
  }
}

void ensureSocket(uint32_t now) {
  if (s_socketStarted) {
    return;
  }
  if (static_cast<int32_t>(now - s_nextSocketRetryMs) < 0) {
    return;
  }
  if (s_udp.begin(Voice::UDP_PORT) != 0) {
    s_socketStarted = true;
    Serial.printf("[VOICE] UDP %u bound\n",
                  static_cast<unsigned int>(Voice::UDP_PORT));
  } else {
    s_nextSocketRetryMs = now + 500;
  }
}

void serviceActive(uint32_t now) {
  if (!audioReady()) {
    enterClosing(false, now);
    return;
  }
  // 2 s 无任何包 = 断链判定，不是空闲超时；空闲多久都不结束会话。
  if (static_cast<uint32_t>(now - s_lastRxMs) >= Voice::RX_TIMEOUT_MS) {
    JL_LOGW(LOG_MOD_AUDIO, Voice::VOICE_LOG_LINK_LOST,
            LOG_SERIAL_TEXT("Voice link lost (2 s no packet)"));
    enterClosing(true, now);
    return;
  }
  if (!Wifi.isLanReady()) {
    enterClosing(false, now);
    return;
  }

  // 半双工方向：收到对端非静音包即进入播放方向，静音心跳 300 ms 后回到上行。
  const bool playback =
      (s_lastNonSilentRxMs != 0) &&
      (static_cast<uint32_t>(now - s_lastNonSilentRxMs) < Voice::PLAY_HOLD_MS);
  if (playback != s_playbackMode) {
    s_playbackMode = playback;
    resetJitter();
    resetMic();
    s_nextPlayMs = now;
  }

  if (playback) {
    s_micFilled = 0;  // 播时不采
    playNext(now);
    if (static_cast<uint32_t>(now - s_lastTxMs) >= Voice::HEARTBEAT_MS) {
      sendPacket(nullptr, Voice::FLAG_SILENCE);
    }
  } else {
    // 采时不播
    pumpMic(now);
  }
}

}  // namespace

namespace Voice {

void begin() {
  s_state = State::Idle;
  s_peerValid = false;
  s_socketStarted = false;
  s_nextSocketRetryMs = 0;
  resetJitter();
  resetMic();

  ensureSocket(millis());
  Serial.printf("[VOICE] light frame UDP %u: %u B = %u B header + %u B PCM16LE\n",
                static_cast<unsigned int>(UDP_PORT),
                static_cast<unsigned int>(PACKET_BYTES),
                static_cast<unsigned int>(HEADER_BYTES),
                static_cast<unsigned int>(PCM_BYTES));
  if (!audioReady()) {
    Serial.println("[VOICE] audio not ready: session start will be refused");
  }
}

void update() {
  const uint32_t now = millis();
  ensureSocket(now);
  if (!s_socketStarted) {
    return;
  }

  readIncoming(now);

  switch (s_state) {
    case State::Active:
      serviceActive(now);
      break;
    case State::Closing:
      if (static_cast<int32_t>(now - s_closingUntilMs) >= 0) {
        finishSession();
      }
      break;
    case State::Fault:
      if (static_cast<int32_t>(now - s_faultUntilMs) >= 0) {
        s_state = State::Idle;
      }
      break;
    case State::Idle:
    default:
      break;
  }
}

bool isActive() {
  return s_state == State::Active || s_state == State::Closing;
}

}  // namespace Voice
