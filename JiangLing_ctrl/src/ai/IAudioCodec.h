/*
 * ============================================================
 * ai/IAudioCodec.h —— 音频编解码抽象接口
 * ============================================================
 * 【为什么抽象】
 *   当前环境拿不到 libopus（GitHub 不可达、registry 包下载超时），
 *   但整条 AI 链路不应该因此停摆。把「PCM → 网络字节」和
 *   「网络字节 → PCM」抽成接口后，默认用 PCM 直通把链路跑通；
 *   网络恢复后只需在 ai/Codec.cpp 里实现 Opus 分支，其他文件不用改。
 *
 * 【统一约定】
 *   PCM：int16_t、单声道、采样率由 begin() 的 sampleRate 决定。
 *   上行：encode() 把 samples 个 PCM 采样编码成不超过 outCapacity 字节。
 *   下行：decode() 把 bytes 字节解码成不超过 maxSamples 个 PCM 采样。
 *   返回 0 一律表示失败或本帧无数据，调用方必须按「跳过本帧」处理，
 *   不允许把 0 当成正常空帧反复重试。
 * ============================================================
 */
#ifndef AI_AUDIO_CODEC_H
#define AI_AUDIO_CODEC_H

#include <Arduino.h>

class IAudioCodec {
  public:
    virtual ~IAudioCodec() {}

    // 初始化编码器/解码器；失败返回 false，原因由实现打印。
    virtual bool begin(uint32_t sampleRate, uint8_t channels, uint16_t frameMs) = 0;

    // 释放资源；可重复调用。
    virtual void end() = 0;

    // 用于日志与 hello 里的 format 字段，例如 "pcm16" / "opus"。
    virtual const char* name() const = 0;

    // 返回编码后的字节数；0 表示失败。
    virtual size_t encode(const int16_t* pcm, size_t samples,
                          uint8_t* out, size_t outCapacity) = 0;

    // 返回解码出的采样点数；0 表示失败。
    virtual size_t decode(const uint8_t* in, size_t bytes,
                          int16_t* pcm, size_t maxSamples) = 0;
};

// 全局唯一实例；具体实现由 AI_ENABLE_OPUS 选择（见 Codec.cpp）。
IAudioCodec& audioCodec();

#endif  // AI_AUDIO_CODEC_H
