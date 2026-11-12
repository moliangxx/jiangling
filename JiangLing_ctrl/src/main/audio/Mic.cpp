/*
 * Mic.cpp —— INMP441 采集与非阻塞 RMS/峰值统计。
 */
#include "Mic.h"

#include <Arduino.h>
#include <math.h>
#include "AudioBus.h"
#include "esp_err.h"

namespace {

// DMA 一帧最多 512 个采样；原始数据按 32 bit 暂存，转换后再写回调用方缓冲区。
constexpr size_t MIC_MAX_CHUNK_SAMPLES = 512;
int32_t s_rawSamples[MIC_MAX_CHUNK_SAMPLES];

bool s_started = false;
bool s_busy = false;
uint16_t s_lastRms = 0;
uint16_t s_lastPeak = 0;

}  // namespace

namespace Mic {

bool begin() {
  if (s_started) {
    return true;
  }
  if (!AudioBus::begin()) {
    Serial.println("[Mic] begin failed: I2S bus not ready");
    return false;
  }
  s_started = true;
  s_lastRms = 0;
  s_lastPeak = 0;
  Serial.println("[Mic] INMP441 ready: 16 kHz, 32-bit slot -> PCM16 (high 16 bits)");
  return true;
}

bool isReady() {
  return s_started;
}

void setBusy(bool busy) {
  // 只置标志，不关闭 I2S：App 通话期间麦克风由 Voice 独占，AI 靠此标志让路。
  s_busy = busy;
}

bool isBusy() {
  return s_busy;
}

size_t read(int16_t* out, size_t maxSamples) {
  if (!s_started || out == nullptr || maxSamples == 0) {
    return 0;
  }
  if (maxSamples > MIC_MAX_CHUNK_SAMPLES) {
    maxSamples = MIC_MAX_CHUNK_SAMPLES;
  }

  size_t bytesRead = 0;
  // 0 tick 超时 = 立即返回。ESP_ERR_TIMEOUT 只表示当前 DMA 还没有新数据。
  esp_err_t err = i2s_channel_read(AudioBus::rxHandle(), s_rawSamples,
                                   maxSamples * sizeof(int32_t), &bytesRead, 0);
  if (err != ESP_OK) {
    if (err != ESP_ERR_TIMEOUT) {
      Serial.printf("[Mic] i2s_channel_read failed: %s (%d)\n", esp_err_to_name(err),
                    static_cast<int>(err));
    }
    return 0;
  }

  const size_t sampleCount = bytesRead / sizeof(int32_t);
  if (sampleCount == 0) {
    return 0;
  }

  uint64_t sumSquares = 0;
  uint16_t peak = 0;
  for (size_t i = 0; i < sampleCount; ++i) {
    // 24 bit 数据左对齐在 32 bit 槽中，右移 16 位即取高 16 bit。
    const int16_t value = static_cast<int16_t>(s_rawSamples[i] >> 16);
    out[i] = value;

    const int32_t absValue = value < 0 ? -static_cast<int32_t>(value) : value;
    if (absValue > static_cast<int32_t>(peak)) {
      peak = static_cast<uint16_t>(absValue);
    }
    sumSquares += static_cast<uint64_t>(static_cast<int32_t>(value) * value);
  }

  s_lastRms = static_cast<uint16_t>(sqrtf(static_cast<float>(sumSquares) / sampleCount));
  s_lastPeak = peak;
  return sampleCount;
}

uint16_t rms() {
  return s_lastRms;
}

uint16_t peak() {
  return s_lastPeak;
}

void end() {
  if (!s_started) {
    return;
  }
  s_started = false;
  AudioBus::end();
}

}  // namespace Mic
