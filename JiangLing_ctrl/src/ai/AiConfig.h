/*
 * ============================================================
 * ai/AiConfig.h —— AI 通道的编译开关与参数
 * ============================================================
 * 【凭据来源】
 *   WiFi 凭据不再写在本文件：由 CMD_WIFI_STA_CONFIG(0xC5) 经 UDP 8081 下发到 RAM（不落 NVS），
 *   上电由 main/wife/Wifi 类读取（编译期默认值，不落 NVS）。AiLink 只询问 Wifi.hasInternet()。
 *
 * 【编解码开关】
 *   AI_ENABLE_OPUS = 0：使用 PCM 直通实现（当前默认）。
 *     上行把 PCM16 原样发给服务端，下行把收到的字节按 PCM16 处理。
 *     注意：小智云端期望 Opus，PCM 直通只用于本机链路验证，云端可能拒绝或返回错误。
 *   AI_ENABLE_OPUS = 1：切换到 ai/Codec.cpp 中的 Opus 空壳。
 *     当前环境拿不到 libopus（github.com:443 不可达，registry 包下载超时），
 *     空壳 begin() 会直接返回失败并打印原因；接入真实库后只需改 Codec.cpp 一处。
 *
 * 【唤醒词开关】
 *   AI_ENABLE_WAKEWORD = 0：不启动 ESP-SR（当前默认）。
 *     原因见 ai/WakeWord.h：预编译库只带英文 Hi ESP 模型，且需要名为 "model"
 *     的 Flash 分区，而 default_16MB.csv 没有该分区。开启前必须改分区表。
 * ============================================================
 */
#ifndef AI_CONFIG_H
#define AI_CONFIG_H

// ---- 编译开关（0/1）----
#define AI_ENABLE_OPUS 0
#define AI_ENABLE_WAKEWORD 0

// ---- 小智云地址（api.tenclass.net，不是 tencent.net）----
#define AI_OTA_URL "https://api.tenclass.net/xiaozhi/ota/"
#define AI_WS_URL "wss://api.tenclass.net/xiaozhi/v1/"

// ---- 超时 / 重连（毫秒）----
#define AI_OTA_TIMEOUT_MS 10000
#define AI_WS_CONNECT_TIMEOUT_MS 8000
#define AI_HELLO_TIMEOUT_MS 10000
#define AI_RECONNECT_MIN_MS 5000
#define AI_RECONNECT_MAX_MS 30000

// ---- 音频参数（服务端 hello 里的 audio_params 必须一致）----
#define AI_SAMPLE_RATE 16000       // 上行采样率；下行可能是 24000，需重采样
#define AI_CHANNELS 1              // 单声道
#define AI_FRAME_MS 60             // 文档给 60 ms
#define AI_FRAME_SAMPLES (AI_SAMPLE_RATE * AI_FRAME_MS / 1000)  // 960

// 上行累积缓冲：Mic::read 单次最多 512 采样，需要攒满一帧再编。
#define AI_UPLINK_SAMPLES 1024
// 下行环形缓冲（采样点）：16 kHz 下约 0.5 s，用来吃网络抖动。
#define AI_DOWNLINK_SAMPLES 8192
// 每次给 Speaker 喂的采样数（10 ms），保持 DMA 不停又不长时间占用任务。
#define AI_PLAY_CHUNK_SAMPLES 160

// ---- 本地空闲关闭（不是协议定义，是我们自己加的）----
// 从 tts.state=stop 起算，30 s 内没有新的唤醒/listen/tts 就关闭本轮会话。
#define AI_IDLE_CLOSE_MS 30000

// ---- 自报身份 ----
#define AI_FIRMWARE_VERSION "1.0.0"
#define AI_BOARD_NAME "jiangling-esp32s3"

#endif  // AI_CONFIG_H
