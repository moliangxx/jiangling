/*
 * ============================================================
 * ai/Codec.cpp —— 编解码实现选择
 * ============================================================
 * 【当前状态：PCM 直通】
 *   AI_ENABLE_OPUS = 0 时使用 PcmCodec：
 *     上行 = PCM16 原样发送（960 采样 = 1920 B / 60 ms）；
 *     下行 = 收到的字节按 PCM16 解释。
 *   PCM 直通能让 OTA/WSS/会话/字幕/计时整条链路在本机验证，
 *   但小智云端期望 Opus，真实对话大概率不成立，这是明确的已知限制。
 *
 * 【Opus 接入点（等网络恢复后只改本文件）】
 *   1. 安装 libopus（PlatformIO registry 或 vendored 源码），确认有 opus.h；
 *   2. 在 OpusCodec::begin 里创建编码器/解码器：
 *        enc = opus_encoder_create(16000, 1, OPUS_APPLICATION_VOIP, &err);
 *        dec = opus_decoder_create(16000, 1, &err);
 *        opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(0));      // S3 省 CPU
 *        opus_encoder_ctl(enc, OPUS_SET_BITRATE(24000));
 *   3. encode 用 opus_encode(enc, pcm, 960, out, cap)；
 *   4. decode 用 opus_decode(dec, in, bytes, pcm, maxSamples, 0)；
 *   5. 把 AI_ENABLE_OPUS 改成 1。
 *   以下空壳只保证「打开开关也能编译」，begin() 明确返回失败，不伪造可用。
 * ============================================================
 */
#include "IAudioCodec.h"
#include "AiConfig.h"

namespace {

// ------------------------------------------------------------
// PCM 直通实现
// ------------------------------------------------------------
class PcmCodec : public IAudioCodec {
  public:
    bool begin(uint32_t sampleRate, uint8_t channels, uint16_t frameMs) override {
      (void)sampleRate;
      (void)channels;
      (void)frameMs;
      _ready = true;
      Serial.println("[AI] 编解码：PCM16 直通（云端期望 Opus，仅本机链路验证）");
      return true;
    }

    void end() override {
      _ready = false;
    }

    const char* name() const override {
      return "pcm16";
    }

    size_t encode(const int16_t* pcm, size_t samples,
                  uint8_t* out, size_t outCapacity) override {
      if (!_ready || pcm == nullptr || out == nullptr) {
        return 0;
      }
      const size_t bytes = samples * sizeof(int16_t);
      if (bytes > outCapacity) {
        return 0;
      }
      memcpy(out, pcm, bytes);
      return bytes;
    }

    size_t decode(const uint8_t* in, size_t bytes,
                  int16_t* pcm, size_t maxSamples) override {
      if (!_ready || in == nullptr || pcm == nullptr) {
        return 0;
      }
      size_t samples = bytes / sizeof(int16_t);
      if (samples > maxSamples) {
        samples = maxSamples;
      }
      memcpy(pcm, in, samples * sizeof(int16_t));
      return samples;
    }

  private:
    bool _ready = false;
};

#if AI_ENABLE_OPUS
// ------------------------------------------------------------
// Opus 空壳：接口齐全但不可用，避免把「能编译」误当成「能用」
// ------------------------------------------------------------
class OpusCodec : public IAudioCodec {
  public:
    bool begin(uint32_t sampleRate, uint8_t channels, uint16_t frameMs) override {
      (void)sampleRate;
      (void)channels;
      (void)frameMs;
      Serial.println("[AI] Opus 不可用：libopus 未接入（见 src/ai/Codec.cpp 顶部接入点）");
      return false;
    }

    void end() override {}

    const char* name() const override {
      return "opus";
    }

    size_t encode(const int16_t* pcm, size_t samples,
                  uint8_t* out, size_t outCapacity) override {
      (void)pcm;
      (void)samples;
      (void)out;
      (void)outCapacity;
      return 0;
    }

    size_t decode(const uint8_t* in, size_t bytes,
                  int16_t* pcm, size_t maxSamples) override {
      (void)in;
      (void)bytes;
      (void)pcm;
      (void)maxSamples;
      return 0;
    }
};
#endif

}  // namespace

IAudioCodec& audioCodec() {
#if AI_ENABLE_OPUS
  static OpusCodec codec;
#else
  static PcmCodec codec;
#endif
  return codec;
}
