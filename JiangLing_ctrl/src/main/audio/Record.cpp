/*
 * audio/Record.cpp —— 录音缓冲与「录完自动回放」的非阻塞状态机。
 *
 * 缓冲优先放 PSRAM；麦克风/喇叭的 DMA 数据仍走内部 RAM 的小块暂存，
 * 避免把外置 PSRAM 指针直接交给 I2S DMA。
 */
#include "Record.h"

#include <Arduino.h>
#include <cstdlib>
#include <cstring>

#include "AudioBus.h"
#include "Mic.h"
#include "Speaker.h"

namespace {

constexpr size_t BYTES_PER_SAMPLE = sizeof(int16_t);
// 首选容量：16 kHz × 2 B × 10 s = 320 000 B。
constexpr size_t PREFERRED_BYTES =
    AudioBus::SAMPLE_RATE_HZ * 2U * BYTES_PER_SAMPLE;
// 内部 RAM 退回容量：2 s = 64 000 B；不够再退一半（1 s）。
constexpr size_t FALLBACK_BYTES =
    AudioBus::SAMPLE_RATE_HZ * 2U * BYTES_PER_SAMPLE;
constexpr uint16_t MAX_SECONDS = 10;

// 单次从 Mic 取 512 点（Mic 内部上限），单次向 Speaker 喂 192 点。
constexpr size_t READ_CHUNK_SAMPLES = 512;
constexpr size_t PLAY_CHUNK_SAMPLES = 192;

enum class State : uint8_t { Idle, Recording, Playing };

int16_t* s_buffer = nullptr;
size_t s_capacitySamples = 0;
uint16_t s_maxSeconds = 0;
bool s_psram = false;

size_t s_targetSamples = 0;
size_t s_capturedSamples = 0;
State s_state = State::Idle;

// 采集暂存（内部 RAM）：Mic 写这里，再拷进录音缓冲。
int16_t s_readChunk[READ_CHUNK_SAMPLES];

// 回放暂存（内部 RAM）：从录音缓冲拷出后交给 Speaker。
int16_t s_playChunk[PLAY_CHUNK_SAMPLES];
size_t s_playChunkOffset = 0;
size_t s_playChunkCount = 0;
size_t s_playIndex = 0;

void resetProgress() {
  s_targetSamples = 0;
  s_capturedSamples = 0;
  s_playIndex = 0;
  s_playChunkOffset = 0;
  s_playChunkCount = 0;
}

void freeBuffer() {
  if (s_buffer != nullptr) {
    free(s_buffer);
    s_buffer = nullptr;
  }
  s_capacitySamples = 0;
  s_maxSeconds = 0;
  s_psram = false;
}

}  // namespace

namespace Record {

bool begin() {
  if (s_buffer != nullptr) {
    return true;
  }

  size_t bytes = 0;
  const size_t psramSize = ESP.getPsramSize();
  const size_t psramFree = ESP.getFreePsram();

  if (psramSize > 0 && psramFree >= PREFERRED_BYTES) {
    void* memory = ps_malloc(PREFERRED_BYTES);
    if (memory != nullptr) {
      s_buffer = static_cast<int16_t*>(memory);
      bytes = PREFERRED_BYTES;
      s_psram = true;
    }
  }
  if (psramSize == 0) {
    Serial.println("[Record] PSRAM size=0; fall back to internal RAM");
  } else if (s_buffer == nullptr) {
    Serial.printf("[Record] PSRAM free=%u B < need %u B; fall back to internal RAM\n",
                  static_cast<unsigned int>(psramFree),
                  static_cast<unsigned int>(PREFERRED_BYTES));
  }

  // PSRAM 不可用或分配失败：退回内部 RAM，并按实际容量收紧时长。
  if (s_buffer == nullptr) {
    const size_t fallbackSizes[] = {FALLBACK_BYTES, FALLBACK_BYTES / 2};
    for (size_t index = 0; index < sizeof(fallbackSizes) / sizeof(fallbackSizes[0]);
         ++index) {
      void* memory = malloc(fallbackSizes[index]);
      if (memory != nullptr) {
        s_buffer = static_cast<int16_t*>(memory);
        bytes = fallbackSizes[index];
        break;
      }
    }
  }

  if (s_buffer == nullptr) {
    Serial.println("[Record] buffer allocation failed; recording disabled");
    return false;
  }

  s_capacitySamples = bytes / BYTES_PER_SAMPLE;
  uint16_t seconds = static_cast<uint16_t>(s_capacitySamples / AudioBus::SAMPLE_RATE_HZ);
  if (seconds > MAX_SECONDS) {
    seconds = MAX_SECONDS;
  }
  if (seconds == 0) {
    freeBuffer();
    Serial.println("[Record] buffer too small (< 1 s); recording disabled");
    return false;
  }

  s_maxSeconds = seconds;
  Serial.printf("[Record] buffer %u B in %s; max=%u s\n",
                static_cast<unsigned int>(bytes),
                s_psram ? "PSRAM" : "internal RAM",
                static_cast<unsigned int>(s_maxSeconds));
  return true;
}

bool isReady() {
  return s_buffer != nullptr && s_maxSeconds > 0;
}

uint16_t maxSeconds() {
  return s_maxSeconds;
}

bool isRecording() {
  return s_state == State::Recording;
}

bool isPlaying() {
  return s_state == State::Playing;
}

bool isBusy() {
  return s_state != State::Idle;
}

StartCode start(uint16_t seconds) {
  if (!isReady()) {
    return START_NO_BUFFER;
  }
  if (s_state != State::Idle) {
    return START_BUSY;
  }
  if (!Mic::isReady()) {
    return START_NO_MIC;
  }
  if (seconds == 0 || seconds > s_maxSeconds) {
    return START_TOO_LONG;
  }

  resetProgress();
  s_targetSamples = static_cast<size_t>(seconds) * AudioBus::SAMPLE_RATE_HZ;
  s_state = State::Recording;
  Serial.printf("[Record] start: %u s (%u samples)\n",
                static_cast<unsigned int>(seconds),
                static_cast<unsigned int>(s_targetSamples));
  return START_OK;
}

void stop() {
  if (s_state == State::Idle) {
    return;
  }
  Serial.println("[Record] stopped before completion");
  resetProgress();
  s_state = State::Idle;
}

void update() {
  if (s_state == State::Recording) {
    while (s_capturedSamples < s_targetSamples) {
      size_t want = s_targetSamples - s_capturedSamples;
      if (want > READ_CHUNK_SAMPLES) {
        want = READ_CHUNK_SAMPLES;
      }
      const size_t got = Mic::read(s_readChunk, want);
      if (got == 0) {
        // 当前没有新数据：保留进度，下一轮 loop 继续，不等待。
        return;
      }
      std::memcpy(s_buffer + s_capturedSamples, s_readChunk,
                  got * BYTES_PER_SAMPLE);
      s_capturedSamples += got;
    }

    // 录满：立刻转入回放，缓冲内容就是刚才这段。
    s_playIndex = 0;
    s_playChunkOffset = 0;
    s_playChunkCount = 0;
    if (!Speaker::isReady()) {
      Serial.println("[Record] capture done but speaker is not ready; playback skipped");
      resetProgress();
      s_state = State::Idle;
      return;
    }
    Serial.printf("[Record] capture done: %u samples; playback start\n",
                  static_cast<unsigned int>(s_capturedSamples));
    s_state = State::Playing;
    return;
  }

  if (s_state == State::Playing) {
    while (s_playIndex < s_capturedSamples) {
      if (s_playChunkOffset == 0) {
        size_t remain = s_capturedSamples - s_playIndex;
        s_playChunkCount = remain > PLAY_CHUNK_SAMPLES ? PLAY_CHUNK_SAMPLES : remain;
        std::memcpy(s_playChunk, s_buffer + s_playIndex,
                    s_playChunkCount * BYTES_PER_SAMPLE);
      }

      const size_t queued =
          Speaker::play(s_playChunk + s_playChunkOffset,
                        s_playChunkCount - s_playChunkOffset);
      if (queued == 0) {
        // DMA 已满：保留块内偏移，下一轮继续。
        return;
      }

      s_playChunkOffset += queued;
      if (s_playChunkOffset >= s_playChunkCount) {
        s_playIndex += s_playChunkCount;
        s_playChunkOffset = 0;
        s_playChunkCount = 0;
      }
    }

    Serial.println("[Record] playback done");
    resetProgress();
    s_state = State::Idle;
  }
}

void end() {
  resetProgress();
  s_state = State::Idle;
  freeBuffer();
}

}  // namespace Record
