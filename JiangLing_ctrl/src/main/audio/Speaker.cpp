/*
 * Speaker.cpp —— MAX98357A 非阻塞播放与测试音生成。
 */
#include "Speaker.h"

#include <Arduino.h>
#include <freertos/FreeRTOS.h>
#include <freertos/semphr.h>
#include <math.h>
#include "AudioBus.h"
#include "esp_err.h"

// 行为取舍开关：I2S TX 发送互斥（0 = 关闭互斥，回到纯 0 tick 写）。
// 通话优先：拿不到锁的一方本次直接返回 0，由调用方下一轮再喂，绝不阻塞。
#ifndef SPEAKER_TX_MUTEX
#define SPEAKER_TX_MUTEX 1
#endif

namespace {

bool s_started = false;

#if SPEAKER_TX_MUTEX
SemaphoreHandle_t s_txMux = nullptr;
#endif

// 软件音量百分比；100 表示不缩放，直接写原始采样。
uint8_t s_volume = 100;

// 测试音最长 200 ms；16 kHz 下为 3200 个采样点（约 6.4 KB）。
constexpr size_t SPEAKER_TONE_MAX_SAMPLES = AudioBus::SAMPLE_RATE_HZ / 5;
int16_t s_toneSamples[SPEAKER_TONE_MAX_SAMPLES];

// 缩放用的临时块：每次最多处理 256 个采样（512 B），避免额外占用大缓冲。
constexpr size_t SPEAKER_GAIN_CHUNK_SAMPLES = 256;
int16_t s_gainChunk[SPEAKER_GAIN_CHUNK_SAMPLES];

// 把单个采样按百分比缩放：先放大到 int32 再除，最后限幅回 int16。
int16_t scaleSample(int16_t value, uint8_t percent) {
  if (percent == 0) {
    return 0;
  }
  const int32_t scaled = static_cast<int32_t>(value) * percent / 100;
  if (scaled > 32767) {
    return 32767;
  }
  if (scaled < -32768) {
    return -32768;
  }
  return static_cast<int16_t>(scaled);
}

// 把一块 PCM16 以 0 tick 超时写入 DMA；返回真正排入的采样点数。
size_t writeDma(const int16_t* samples, size_t sampleCount) {
  size_t bytesWritten = 0;
  esp_err_t err = i2s_channel_write(AudioBus::txHandle(), samples,
                                    sampleCount * sizeof(int16_t), &bytesWritten, 0);
  if (err != ESP_OK && err != ESP_ERR_TIMEOUT) {
    Serial.printf("[Speaker] i2s_channel_write failed: %s (%d)\n", esp_err_to_name(err),
                  static_cast<int>(err));
    return 0;
  }
  return bytesWritten / sizeof(int16_t);
}

// play() 的实际实现，调用前必须已拿到 TX 互斥锁。
size_t playLocked(const int16_t* samples, size_t sampleCount) {
  // 100%：不缩放，直接把调用方缓冲交给 DMA，省一次拷贝。
  if (s_volume >= 100) {
    return writeDma(samples, sampleCount);
  }

  // 其他音量：分块缩放后再写，DMA 满即返回本次已排入的点数。
  size_t total = 0;
  while (total < sampleCount) {
    size_t chunk = sampleCount - total;
    if (chunk > SPEAKER_GAIN_CHUNK_SAMPLES) {
      chunk = SPEAKER_GAIN_CHUNK_SAMPLES;
    }
    for (size_t i = 0; i < chunk; ++i) {
      s_gainChunk[i] = scaleSample(samples[total + i], s_volume);
    }

    const size_t queued = writeDma(s_gainChunk, chunk);
    total += queued;
    if (queued < chunk) {
      // DMA 满：未写的采样由调用方下次接着喂。
      break;
    }
  }
  return total;
}

}  // namespace

namespace Speaker {

bool begin() {
  if (s_started) {
    return true;
  }
  if (!AudioBus::begin()) {
    Serial.println("[Speaker] begin failed: I2S bus not ready");
    return false;
  }
#if SPEAKER_TX_MUTEX
  if (s_txMux == nullptr) {
    s_txMux = xSemaphoreCreateMutex();
    if (s_txMux == nullptr) {
      Serial.println("[Speaker] begin failed: TX mutex not created");
      AudioBus::end();
      return false;
    }
  }
#endif
  s_started = true;
  Serial.println("[Speaker] MAX98357A ready: 16 kHz, mono, volume by hardware GAIN pin");
  return true;
}

bool isReady() {
  return s_started;
}

void setVolume(uint8_t percent) {
  if (percent > 100) {
    percent = 100;
  }
  s_volume = percent;
  Serial.printf("[Speaker] software volume=%u%%\n", static_cast<unsigned int>(percent));
}

uint8_t volume() {
  return s_volume;
}

size_t play(const int16_t* samples, size_t sampleCount) {
  if (!s_started || samples == nullptr || sampleCount == 0) {
    return 0;
  }

#if SPEAKER_TX_MUTEX
  // 0 tick 取锁：另一个任务（AI 播报 / 通话）正在写 I2S 时本次直接让路。
  if (s_txMux == nullptr || xSemaphoreTake(s_txMux, 0) != pdTRUE) {
    return 0;
  }
#endif
  const size_t total = playLocked(samples, sampleCount);
#if SPEAKER_TX_MUTEX
  xSemaphoreGive(s_txMux);
#endif
  return total;
}

size_t playTestTone(uint16_t frequencyHz, uint16_t durationMs) {
  if (!s_started) {
    return 0;
  }
  if (frequencyHz == 0) {
    frequencyHz = 1000;
  }
  if (durationMs == 0) {
    durationMs = 200;
  }
  if (durationMs > 200) {
    durationMs = 200;
  }

  const size_t sampleCount =
      static_cast<size_t>(AudioBus::SAMPLE_RATE_HZ) * durationMs / 1000;
  // 峰值约为满量程的 1/4，避免测试时喇叭过响；音量仍由硬件 GAIN 决定。
  constexpr float TONE_AMPLITUDE = 8000.0f;

  for (size_t i = 0; i < sampleCount; ++i) {
    const float t = static_cast<float>(i) / static_cast<float>(AudioBus::SAMPLE_RATE_HZ);
    s_toneSamples[i] = static_cast<int16_t>(TONE_AMPLITUDE * sinf(2.0f * PI * frequencyHz * t));
  }

  const size_t queued = play(s_toneSamples, sampleCount);
  if (queued == 0) {
    Serial.println("[Speaker] playTestTone: DMA full, tone not queued");
  }
  return queued;
}

void end() {
  if (!s_started) {
    return;
  }
  s_started = false;
  AudioBus::end();
}

}  // namespace Speaker
