/*
 * ============================================================
 * ai/WakeWord.cpp —— ESP-SR WakeNet 封装
 * ============================================================
 * 默认（AI_ENABLE_WAKEWORD=0）不编译 ESP-SR 调用，只打印限制；
 * 打开后从 Mic 拉 PCM 喂给 WakeNet，检测到 Hi ESP 时回调。
 * 详细限制见 ai/WakeWord.h 文件头。
 * ============================================================
 */
#include "WakeWord.h"

#include "AiConfig.h"
#include "../main/audio/Mic.h"

#if AI_ENABLE_WAKEWORD
#include <ESP_SR.h>
#endif

namespace {

WakeWord::DetectedCallback g_callback = nullptr;
bool g_active = false;

#if AI_ENABLE_WAKEWORD
// ESP-SR 的填充回调：把 Mic 的单声道 PCM 攒满 len 字节。
// Mic::read 是非阻塞的，所以这里用 vTaskDelay 小步等待，直到凑满或超时。
esp_err_t wakeFill(void* arg, void* out, size_t len, size_t* bytesRead,
                   uint32_t timeoutMs) {
  (void)arg;
  int16_t* dst = static_cast<int16_t*>(out);
  const size_t wantSamples = len / sizeof(int16_t);
  size_t got = 0;
  const uint32_t startMs = millis();

  while (got < wantSamples && (millis() - startMs) < timeoutMs) {
    const size_t chunk = (wantSamples - got > 512) ? 512 : (wantSamples - got);
    const size_t n = Mic::read(dst + got, chunk);
    got += n;
    if (n == 0) {
      vTaskDelay(pdMS_TO_TICKS(4));
    }
  }

  if (bytesRead != nullptr) {
    *bytesRead = got * sizeof(int16_t);
  }
  return ESP_OK;
}

void wakeEvent(void* arg, sr_event_t event, int commandId, int phraseId) {
  (void)arg;
  (void)commandId;
  (void)phraseId;
  if (event == SR_EVENT_WAKEWORD && g_callback != nullptr) {
    g_callback();
  }
}
#endif

}  // namespace

namespace WakeWord {

bool begin() {
#if AI_ENABLE_WAKEWORD
  if (g_active) {
    return true;
  }
  if (!Mic::isReady()) {
    Serial.println("[AI] 唤醒词未启动：Mic 未就绪");
    return false;
  }

  // 这里只启唤醒词模式；中文命令词模型在预编译库里是 CONFIG_SR_MN_CN_NONE，不可用。
  const esp_err_t err = sr_start(wakeFill, nullptr, SR_CHANNELS_MONO,
                                 SR_MODE_WAKEWORD, "M", nullptr, 0,
                                 wakeEvent, nullptr);
  if (err != ESP_OK) {
    Serial.printf("[AI] 唤醒词启动失败：sr_start=%d（常见原因：缺少 model 分区或模型未启用）\n",
                  static_cast<int>(err));
    return false;
  }
  g_active = true;
  Serial.println("[AI] 唤醒词已启动：英文 \"Hi ESP\"（无中文模型）");
  return true;
#else
  Serial.println("[AI] 唤醒词未启用（AI_ENABLE_WAKEWORD=0）：");
  Serial.println("[AI]   预编译 ESP_SR 只有英文 Hi ESP 模型，中文唤醒词不可用；");
  Serial.println("[AI]   且需自定义 model 分区，当前 default_16MB.csv 没有。");
  return false;
#endif
}

void pause() {
#if AI_ENABLE_WAKEWORD
  if (g_active) {
    sr_pause();
  }
#endif
}

void resume() {
#if AI_ENABLE_WAKEWORD
  // 麦克风仍被 App 语音占用时不允许恢复检测，等下一次通话结束后再 resume。
  if (Mic::isBusy()) {
    return;
  }
  if (g_active) {
    sr_resume();
  }
#endif
}

void end() {
#if AI_ENABLE_WAKEWORD
  if (g_active) {
    sr_stop();
    g_active = false;
  }
#endif
}

bool isActive() {
  return g_active;
}

void setDetectedCallback(DetectedCallback callback) {
  g_callback = callback;
}

}  // namespace WakeWord
