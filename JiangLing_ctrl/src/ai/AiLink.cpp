/*
 * ============================================================
 * ai/AiLink.cpp —— 小智云 AI 通道实现
 * ============================================================
 * 【数据流】
 *   Mic::read → 攒 60 ms(960 采样) → codec.encode → WSS 二进制
 *   WSS 二进制 → codec.decode → 重采样到 16 kHz → 环形缓冲 → Speaker::play
 *
 * 【半双工（无 AEC）】
 *   硬件没有回声消除，喇叭的声音会被 INMP441 收回去。因此服务端
 *   tts.state=start 时停止上行，tts.state=stop 后再恢复；不发送 abort，
 *   不做「边播边听」。这是规避方案，不是真正的全双工。
 *
 * 【空闲关闭（自定义，不是协议定义）】
 *   tts.state=stop 视为「这一轮播报结束」，从这一刻起算 30 s；
 *   期间收到 stt/llm/tts.start 会重置计时。超时发 listen stop 并回到 Ready。
 *
 * 【未验证部分】
 *   没有硬件、没有 WiFi 凭据、没有 libopus，以下只能静态编写：
 *   - 真实云端的 hello 字段与二进制帧版本；
 *   - PCM 直通是否被云端接受（大概率不接受）；
 *   - I2S 与 TFT 的并发表现。
 * ============================================================
 */
#include "AiLink.h"

#include "AiConfig.h"
#include "IAudioCodec.h"
#include "Subtitle.h"
#include "WakeWord.h"
#include "../main/audio/Mic.h"
#include "../main/audio/Speaker.h"
#include "../main/wife/Voice.h"
#include "../main/wife/Wifi.h"

#include <Arduino.h>
#include <ArduinoJson.h>
#include <HTTPClient.h>
#include <WebSocketsClient.h>
#include <WiFiClientSecure.h>
#include <ctype.h>
#include <stdarg.h>
#include <cstring>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>

// 行为取舍开关：通话期间 AI 播报让路（0 = 关闭让路）。
#ifndef AI_PLAYBACK_YIELD_TO_VOICE
#define AI_PLAYBACK_YIELD_TO_VOICE 1
#endif

namespace {

// ---------------- 运行状态 ----------------
WebSocketsClient g_ws;
String g_extraHeaders;  // setExtraHeaders 只存指针，必须活到连接结束

volatile bool g_taskStarted = false;
volatile bool g_wsOpened = false;
volatile bool g_wsClosed = false;
volatile bool g_wsError = false;
volatile bool g_serverHello = false;
volatile bool g_wakePending = false;
// closeSession() 可能从命令执行路径调用，只置标志，真正的 listen stop 在 AI 任务里执行，
// 避免跨任务调用 WebSocketsClient（它没有做线程安全保证）。
volatile bool g_closePending = false;

uint32_t g_openedAtMs = 0;
uint32_t g_serverRate = 24000;  // 服务端下行采样率；hello 会覆盖

AiLink::SessionState g_state = AiLink::SessionState::Offline;
bool g_sessionActive = false;
bool g_speaking = false;
uint32_t g_idleStartMs = 0;
bool g_idleCounting = false;

// 音频
int16_t g_uplink[AI_UPLINK_SAMPLES];
size_t g_uplinkFill = 0;
uint8_t g_encodeBuf[2048];

int16_t g_downRing[AI_DOWNLINK_SAMPLES];
size_t g_downHead = 0;   // 下一个写入位置
size_t g_downTail = 0;   // 下一个读取位置
size_t g_downCount = 0;

// OTA 缓存
String g_wsUrl;
String g_token;
uint32_t g_lastOtaMs = 0;
uint32_t g_reconnectDelayMs = AI_RECONNECT_MIN_MS;

// 任务句柄仅用于防止重复创建
TaskHandle_t g_task = nullptr;

// ---------------- 小工具 ----------------
void logLine(const char* fmt, ...) {
  char buf[256];
  va_list args;
  va_start(args, fmt);
  vsnprintf(buf, sizeof(buf), fmt, args);
  va_end(args);
  Serial.printf("[AI] %s\n", buf);
}

String makeClientId(const String& mac) {
  String hex;
  for (size_t i = 0; i < mac.length(); ++i) {
    const char c = mac.charAt(i);
    if (isxdigit(static_cast<int>(c))) {
      hex += c;
    }
  }
  while (hex.length() < 12) {
    hex += "0";
  }
  return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-4" +
         hex.substring(0, 3) + "-a" + hex.substring(3, 6) + "-" +
         hex.substring(0, 12);
}

bool parseWssUrl(const String& url, String& host, uint16_t& port, String& path) {
  String s = url;
  s.trim();
  if (!s.startsWith("wss://") && !s.startsWith("ws://")) {
    return false;
  }
  const int schemeEnd = s.indexOf("://");
  String rest = s.substring(schemeEnd + 3);
  const int slash = rest.indexOf('/');
  String hostPort = (slash < 0) ? rest : rest.substring(0, slash);
  path = (slash < 0) ? String("/") : rest.substring(slash);

  const int colon = hostPort.indexOf(':');
  if (colon >= 0) {
    host = hostPort.substring(0, colon);
    port = static_cast<uint16_t>(hostPort.substring(colon + 1).toInt());
  } else {
    host = hostPort;
    port = 443;
  }
  if (port == 0) {
    port = 443;
  }
  return host.length() > 0;
}

// 下行重采样：服务端可能是 24 kHz，I2S 输出固定 16 kHz。
// 线性插值，够语音用；音乐场景会略有失真，后续可换 polyphase。
size_t resampleTo16k(const int16_t* in, size_t inSamples, uint32_t inRate,
                     int16_t* out, size_t outCapacity) {
  if (in == nullptr || out == nullptr || inSamples == 0 || inRate == 0) {
    return 0;
  }
  if (inRate == AI_SAMPLE_RATE) {
    const size_t n = (inSamples < outCapacity) ? inSamples : outCapacity;
    memcpy(out, in, n * sizeof(int16_t));
    return n;
  }

  size_t outCount = static_cast<size_t>(
      (static_cast<uint64_t>(inSamples) * AI_SAMPLE_RATE) / inRate);
  if (outCount > outCapacity) {
    outCount = outCapacity;
  }
  if (outCount == 0) {
    return 0;
  }

  for (size_t i = 0; i < outCount; ++i) {
    // 源位置 = i * (inRate / 16000)，用 64 位避免溢出。
    const uint64_t posNum = static_cast<uint64_t>(i) * inRate;
    const uint32_t pos = static_cast<uint32_t>(posNum / AI_SAMPLE_RATE);
    const uint32_t frac = static_cast<uint32_t>(posNum % AI_SAMPLE_RATE);
    const int16_t a = in[pos];
    const int16_t b = (pos + 1 < inSamples) ? in[pos + 1] : a;
    const int32_t mixed = static_cast<int32_t>(a) +
                          (static_cast<int32_t>(b - a) *
                           static_cast<int32_t>(frac)) /
                              AI_SAMPLE_RATE;
    out[i] = static_cast<int16_t>(mixed);
  }
  return outCount;
}

void ringPush(const int16_t* samples, size_t count) {
  for (size_t i = 0; i < count; ++i) {
    if (g_downCount == AI_DOWNLINK_SAMPLES) {
      // 缓冲满：丢最旧的一小段，保证播报不被新数据顶掉。
      g_downTail = (g_downTail + 1) % AI_DOWNLINK_SAMPLES;
      --g_downCount;
    }
    g_downRing[g_downHead] = samples[i];
    g_downHead = (g_downHead + 1) % AI_DOWNLINK_SAMPLES;
    ++g_downCount;
  }
}

size_t ringPop(int16_t* out, size_t maxCount) {
  size_t n = (g_downCount < maxCount) ? g_downCount : maxCount;
  for (size_t i = 0; i < n; ++i) {
    out[i] = g_downRing[g_downTail];
    g_downTail = (g_downTail + 1) % AI_DOWNLINK_SAMPLES;
  }
  g_downCount -= n;
  return n;
}

void setState(AiLink::SessionState next) {
  if (g_state == next) {
    return;
  }
  g_state = next;
  Serial.printf("[AI] 状态 → %s\n", AiLink::stateName(next));
}

// ---------------- OTA ----------------
String buildOtaBody(const String& mac, const String& clientId) {
  JsonDocument doc;
  doc["version"] = 2;
  doc["mac_address"] = mac;
  doc["uuid"] = clientId;

  JsonObject app = doc["application"].to<JsonObject>();
  app["name"] = AI_BOARD_NAME;
  app["version"] = AI_FIRMWARE_VERSION;

  JsonObject board = doc["board"].to<JsonObject>();
  board["type"] = AI_BOARD_NAME;
  board["name"] = "JiangLing";

  JsonObject chip = doc["chip"].to<JsonObject>();
  chip["model"] = "esp32s3";
  chip["cores"] = 2;

  JsonObject flash = doc["flash"].to<JsonObject>();
  flash["size"] = ESP.getFlashChipSize();

  JsonObject ram = doc["ram"].to<JsonObject>();
  ram["total"] = ESP.getHeapSize();
  ram["free"] = ESP.getFreeHeap();

  String body;
  serializeJson(doc, body);
  return body;
}

bool otaFetch(const String& mac, const String& clientId) {
  logLine("OTA 请求：%s", AI_OTA_URL);

  WiFiClientSecure tls;
  tls.setInsecure();  // PoC：不校验证书；正式版应换 CA bundle + SNTP

  HTTPClient http;
  http.setConnectTimeout(AI_OTA_TIMEOUT_MS);
  http.setTimeout(AI_OTA_TIMEOUT_MS);
  http.setFollowRedirects(HTTPC_STRICT_FOLLOW_REDIRECTS);
  if (!http.begin(tls, AI_OTA_URL)) {
    logLine("OTA 失败：URL 初始化失败");
    return false;
  }
  http.addHeader("Content-Type", "application/json");
  http.addHeader("Device-Id", mac);
  http.addHeader("Client-Id", clientId);
  http.addHeader("User-Agent", String(AI_BOARD_NAME) + "/" + AI_FIRMWARE_VERSION);

  const String body = buildOtaBody(mac, clientId);
  const int code = http.POST(body);
  if (code <= 0) {
    logLine("OTA 请求失败：%s（code=%d）", http.errorToString(code).c_str(), code);
    http.end();
    return false;
  }

  String resp = http.getString();
  http.end();
  logLine("OTA HTTP=%d，响应 %u 字节", code, static_cast<unsigned>(resp.length()));

  if (code < 200 || code >= 300) {
    logLine("OTA 非 2xx，响应前 200 字：%s", resp.substring(0, 200).c_str());
    return false;
  }

  JsonDocument doc;
  const DeserializationError err = deserializeJson(doc, resp);
  if (err) {
    logLine("OTA JSON 解析失败：%s；前 200 字：%s", err.c_str(),
            resp.substring(0, 200).c_str());
    return false;
  }

  const char* url = doc["websocket"]["url"] | "";
  const char* tok = doc["websocket"]["token"] | "";
  const int wsVersion = doc["websocket"]["version"] | 0;

  g_wsUrl = (url[0] != '\0') ? String(url) : String(AI_WS_URL);
  g_token = String(tok);

  logLine("websocket.url=%s（version=%d）", g_wsUrl.c_str(), wsVersion);
  if (g_token.length() > 0) {
    logLine("token=%s…（长度=%u）",
            g_token.substring(0, g_token.length() > 8 ? 8 : g_token.length()).c_str(),
            static_cast<unsigned>(g_token.length()));
  } else {
    logLine("OTA 未下发 token");
  }

  const char* actCode = doc["activation"]["code"] | "";
  const char* actMsg = doc["activation"]["message"] | "";
  if (actCode[0] != '\0') {
    Serial.println();
    Serial.println("============================================================");
    Serial.printf("[AI] >>> 设备需要激活，6 位验证码：%s\n", actCode);
    if (actMsg[0] != '\0') {
      Serial.printf("[AI] >>> 服务端提示：%s\n", actMsg);
    }
    Serial.println("[AI] >>> 打开 https://xiaozhi.me 控制台 → 添加设备 → 输入验证码");
    Serial.println("============================================================");
    Serial.println();
  } else {
    logLine("未要求激活（设备可能已绑定）");
  }

  g_lastOtaMs = millis();
  return true;
}

// ---------------- 会话消息 ----------------
void sendText(const String& text) {
  if (!g_ws.isConnected()) {
    return;
  }
  logLine("WSS 发送：%s", text.c_str());
  String copy = text;  // sendTXT 需要非 const 引用
  g_ws.sendTXT(copy);
}

void startListening() {
  if (!g_ws.isConnected()) {
    g_wakePending = true;
    logLine("wake 挂起：WSS 未连接，连上后自动开始");
    return;
  }
  if (g_sessionActive && g_speaking) {
    logLine("wake 忽略：TTS 正在播报（半双工下不打断）");
    return;
  }

  g_uplinkFill = 0;
  g_downHead = 0;
  g_downTail = 0;
  g_downCount = 0;
  g_sessionActive = true;
  g_speaking = false;
  g_idleCounting = false;
  g_idleStartMs = 0;

  WakeWord::pause();  // 会话期间把 I2S RX 让给上行

  sendText("{\"type\":\"listen\",\"state\":\"start\",\"mode\":\"auto\"}");
  setState(AiLink::SessionState::Listening);
}

void stopListening() {
  if (g_sessionActive) {
    sendText("{\"type\":\"listen\",\"state\":\"stop\"}");
  }
  g_sessionActive = false;
  g_speaking = false;
  g_idleCounting = false;
  g_uplinkFill = 0;
  setState(AiLink::SessionState::Ready);
  WakeWord::resume();
}

// ---------------- 服务端文本 ----------------
void handleServerText(const uint8_t* payload, size_t length) {
  String raw(reinterpret_cast<const char*>(payload), length);
  String shown = raw.length() > 300 ? (raw.substring(0, 300) + "…") : raw;
  logLine("WSS 文本（%u B）：%s", static_cast<unsigned>(length), shown.c_str());

  JsonDocument doc;
  if (deserializeJson(doc, payload, length)) {
    logLine("文本帧不是 JSON，按原文保留");
    return;
  }

  const char* type = doc["type"] | "";

  if (strcmp(type, "hello") == 0) {
    const char* sessionId = doc["session_id"] | "";
    const char* transport = doc["transport"] | "";
    g_serverRate = doc["audio_params"]["sample_rate"] | 24000;
    logLine("服务端 hello：session_id=%s transport=%s", sessionId, transport);
    logLine("服务端音频：rate=%u channels=%d frame=%d ms",
            static_cast<unsigned>(g_serverRate),
            static_cast<int>(doc["audio_params"]["channels"] | 0),
            static_cast<int>(doc["audio_params"]["frame_duration"] | 0));
    if (strcmp(transport, "websocket") == 0) {
      g_serverHello = true;
    } else {
      logLine("transport=%s 与本地 websocket 不一致，握手判失败", transport);
      g_wsError = true;
    }
    return;
  }

  if (strcmp(type, "stt") == 0) {
    const char* text = doc["text"] | "";
    Subtitle::push("stt", text);
    logLine("识别结果：%s", text);
    if (g_sessionActive) {
      g_idleCounting = false;
      g_idleStartMs = millis();  // 有新交互，重新计时
    }
    return;
  }

  if (strcmp(type, "llm") == 0) {
    const char* text = doc["text"] | "";
    const char* emotion = doc["emotion"] | "";
    if (text[0] != '\0') {
      Subtitle::push("llm", text);
      logLine("LLM 文本：%s", text);
    } else if (emotion[0] != '\0') {
      logLine("LLM 情绪：%s", emotion);
    }
    g_idleCounting = false;
    g_idleStartMs = millis();
    return;
  }

  if (strcmp(type, "tts") == 0) {
    const char* ttsState = doc["state"] | "";
    if (strcmp(ttsState, "start") == 0) {
      // 半双工：播报开始就停上行，避免喇叭声音被麦克风收回去。
      g_speaking = true;
      g_idleCounting = false;
      setState(AiLink::SessionState::Speaking);
      logLine("TTS 开始，上行暂停（无 AEC 半双工）");
    } else if (strcmp(ttsState, "sentence_start") == 0) {
      const char* text = doc["text"] | "";
      Subtitle::push("tts", text);
      logLine("字幕：%s", text);
      g_idleCounting = false;
    } else if (strcmp(ttsState, "stop") == 0) {
      g_speaking = false;
      g_idleCounting = true;
      g_idleStartMs = millis();  // 30 s 从「播报结束」起算
      setState(AiLink::SessionState::Listening);
      logLine("TTS 结束，开始 30 s 空闲计时");
    } else {
      logLine("TTS 未知状态：%s", ttsState);
    }
    return;
  }

  if (strcmp(type, "alert") == 0 || strcmp(type, "system") == 0) {
    const char* message = doc["message"] | "";
    logLine("服务端 %s：%s", type, message);
    return;
  }

  logLine("未处理的消息类型：%s（已忽略内容）", type);
}

// ---------------- 音频下行 ----------------
void handleServerBinary(const uint8_t* payload, size_t length) {
  // 解码输出与重采样输出都放静态区：回调运行在 AI 任务里，
  // 单线程访问，避免在 10 KB 任务栈上再放两块 4 KB 的临时数组。
  static int16_t decoded[2048];
  static int16_t resampled[2048];
  const size_t decodedCount =
      audioCodec().decode(payload, length, decoded, 2048);
  if (decodedCount == 0) {
    logLine("下行音频解码失败（%u B），跳过", static_cast<unsigned>(length));
    return;
  }

  const size_t outCount = resampleTo16k(decoded, decodedCount, g_serverRate,
                                        resampled, 2048);
  if (outCount == 0) {
    return;
  }
  ringPush(resampled, outCount);
}

// ---------------- 音频上行 / 播放 ----------------
void pumpUplink() {
  if (!g_sessionActive || g_speaking || !g_ws.isConnected()) {
    return;
  }
  // App 语音通话占用麦克风时 AI 必须让出：不读麦、不攒帧（语音.md §1.9）。
  if (Mic::isBusy()) {
    g_uplinkFill = 0;
    return;
  }
  if (g_uplinkFill >= AI_UPLINK_SAMPLES) {
    return;
  }

  const size_t n = Mic::read(g_uplink + g_uplinkFill,
                                AI_UPLINK_SAMPLES - g_uplinkFill);
  g_uplinkFill += n;

  while (g_uplinkFill >= AI_FRAME_SAMPLES) {
    const size_t bytes = audioCodec().encode(
        g_uplink, AI_FRAME_SAMPLES, g_encodeBuf, sizeof(g_encodeBuf));
    if (bytes > 0) {
      g_ws.sendBIN(g_encodeBuf, bytes, false);
    }
    const size_t remain = g_uplinkFill - AI_FRAME_SAMPLES;
    if (remain > 0) {
      memmove(g_uplink, g_uplink + AI_FRAME_SAMPLES,
              remain * sizeof(int16_t));
    }
    g_uplinkFill = remain;
  }
}

void pumpPlayback() {
  if (g_downCount == 0 || !Speaker::isReady()) {
    return;
  }
#if AI_PLAYBACK_YIELD_TO_VOICE
  // 通话优先：手机对讲占用喇叭时 AI 播报直接让路（不写 I2S DMA，样本留在环里）。
  if (Voice::isActive()) {
    return;
  }
#endif
  int16_t chunk[AI_PLAY_CHUNK_SAMPLES];
  const size_t n = ringPop(chunk, AI_PLAY_CHUNK_SAMPLES);
  const size_t queued = Speaker::play(chunk, n);
  if (queued < n) {
    // DMA 满：把没排进去的样本退回缓冲区尾部，下一轮再喂。
    const size_t back = n - queued;
    for (size_t i = back; i > 0; --i) {
      g_downTail = (g_downTail + AI_DOWNLINK_SAMPLES - 1) % AI_DOWNLINK_SAMPLES;
      g_downRing[g_downTail] = chunk[queued + i - 1];
      ++g_downCount;
    }
  }
}

void checkIdle() {
  if (!g_sessionActive || g_speaking || !g_idleCounting) {
    return;
  }
  if (millis() - g_idleStartMs >= AI_IDLE_CLOSE_MS) {
    logLine("30 s 无新交互，关闭本轮会话");
    stopListening();
  }
}

// ---------------- WSS ----------------
void onWsEvent(WStype_t type, uint8_t* payload, size_t length) {
  switch (type) {
    case WStype_CONNECTED:
      g_wsOpened = true;
      g_openedAtMs = millis();
      g_wsClosed = false;
      g_wsError = false;
      logLine("WSS 已连接（101 升级完成）");
      break;
    case WStype_DISCONNECTED:
      g_wsClosed = true;
      g_wsOpened = false;
      logLine("WSS 已断开");
      break;
    case WStype_ERROR:
      g_wsError = true;
      logLine("WSS 错误：%s",
              length > 0 ? reinterpret_cast<const char*>(payload)
                         : "库未给出原因（DNS/TLS/鉴权失败常见）");
      break;
    case WStype_TEXT:
      handleServerText(payload, length);
      break;
    case WStype_BIN:
      handleServerBinary(payload, length);
      break;
    default:
      break;
  }
}

bool openWss(const String& mac, const String& clientId) {
  String host;
  String path;
  uint16_t port = 443;
  if (!parseWssUrl(g_wsUrl, host, port, path)) {
    logLine("websocket 地址无法解析：%s", g_wsUrl.c_str());
    return false;
  }
  logLine("连接 WSS host=%s port=%u path=%s", host.c_str(),
          static_cast<unsigned>(port), path.c_str());

  g_extraHeaders = String("Protocol-Version: 1\r\n") +
                   "Device-Id: " + mac + "\r\n" +
                   "Client-Id: " + clientId + "\r\n";
  if (g_token.length() > 0) {
    g_extraHeaders = "Authorization: Bearer " + g_token + "\r\n" + g_extraHeaders;
  }

  g_wsOpened = false;
  g_wsClosed = false;
  g_wsError = false;
  g_serverHello = false;

  g_ws.beginSSL(host.c_str(), port, path.c_str(), "", "arduino");
  g_ws.setExtraHeaders(g_extraHeaders.c_str());
  g_ws.onEvent(onWsEvent);
  g_ws.setReconnectInterval(0);
  g_ws.enableHeartbeat(15000, 3000, 2);
  return true;
}

String buildHello() {
  JsonDocument doc;
  doc["type"] = "hello";
  doc["version"] = 1;
  doc["transport"] = "websocket";

  JsonObject ap = doc["audio_params"].to<JsonObject>();
  // PCM 直通时写 pcm16；换成 Opus 后自动变成 opus。
  ap["format"] = audioCodec().name();
  ap["sample_rate"] = AI_SAMPLE_RATE;
  ap["channels"] = AI_CHANNELS;
  ap["frame_duration"] = AI_FRAME_MS;

  JsonObject features = doc["features"].to<JsonObject>();
  features["mcp"] = false;

  String out;
  serializeJson(doc, out);
  return out;
}

void onWakeWordDetected() {
  // App 语音通话占用麦克风时忽略唤醒：唤醒词检测期间已 pause，这里是二道保险。
  if (Mic::isBusy()) {
    return;
  }
  g_wakePending = true;
}

// ---------------- 任务主体 ----------------
void aiTask(void*) {
  logLine("===== AI 通道启动 =====");

  if (!audioCodec().begin(AI_SAMPLE_RATE, AI_CHANNELS, AI_FRAME_MS)) {
    logLine("编解码初始化失败，AI 通道停止");
    vTaskDelete(nullptr);
    return;
  }

  if (AI_ENABLE_WAKEWORD) {
    WakeWord::setDetectedCallback(onWakeWordDetected);
    WakeWord::begin();
  } else {
    Serial.println("[AI] 唤醒词关闭：需要 model 分区 + 仅英文 Hi ESP，详见 ai/WakeWord.h");
  }

  bool loggedNoInternet = false;

  while (true) {
    if (!Wifi.hasInternet()) {
      if (!loggedNoInternet) {
        logLine("等待外网：当前 WiFi 状态=%s（凭据只存在 RAM，不落盘）",
                WifiCommunication::stateName(Wifi.state()));
        loggedNoInternet = true;
        setState(AiLink::SessionState::Offline);
      }
      vTaskDelay(pdMS_TO_TICKS(2000));
      continue;
    }
    loggedNoInternet = false;

    const String mac = Wifi.macAddress();
    const String clientId = makeClientId(mac);

    // OTA 只在首次或超过 10 分钟时刷新，避免长连接期间反复打注册接口。
    const bool needOta = (g_wsUrl.length() == 0) ||
                         (millis() - g_lastOtaMs > 600000UL);
    if (needOta && !otaFetch(mac, clientId)) {
      logLine("OTA 失败，30 s 后重试");
      vTaskDelay(pdMS_TO_TICKS(30000));
      continue;
    }

    if (!openWss(mac, clientId)) {
      vTaskDelay(pdMS_TO_TICKS(AI_RECONNECT_MAX_MS));
      continue;
    }

    const uint32_t connectAt = millis();
    bool helloSent = false;
    bool helloTimeoutLogged = false;

    while (true) {
      g_ws.loop();

      if (g_wsError) {
        logLine("WSS 错误，准备重连");
        break;
      }
      if (g_wsClosed) {
        logLine("WSS 断开，准备重连");
        break;
      }

      if (!g_wsOpened && (millis() - connectAt > AI_WS_CONNECT_TIMEOUT_MS)) {
        logLine("WSS 建连超时（%d ms）", AI_WS_CONNECT_TIMEOUT_MS);
        break;
      }

      if (g_wsOpened && !helloSent) {
        String hello = buildHello();
        logLine("发送 hello：%s", hello.c_str());
        g_ws.sendTXT(hello);
        helloSent = true;
      }

      if (g_wsOpened && !g_serverHello && !helloTimeoutLogged &&
          (millis() - g_openedAtMs > AI_HELLO_TIMEOUT_MS)) {
        helloTimeoutLogged = true;
        logLine("服务端 hello 超时（%d ms）", AI_HELLO_TIMEOUT_MS);
        break;
      }

      if (g_serverHello) {
        if (g_state == AiLink::SessionState::Offline) {
          setState(AiLink::SessionState::Ready);
          g_reconnectDelayMs = AI_RECONNECT_MIN_MS;
          Serial.println("[AI] ===== 握手成功：OTA + WSS + hello =====");
        }
        if (g_wakePending) {
          g_wakePending = false;
          startListening();
        }
      }

      // 远程操控等场景可能在任何时刻要求关闭会话，放在握手块外更稳妥。
      if (g_closePending) {
        g_closePending = false;
        stopListening();
      }

      pumpUplink();
      pumpPlayback();
      checkIdle();

      vTaskDelay(pdMS_TO_TICKS(10));
    }

    // 断开清理：先停会话，再按退避等待重连。
    if (g_sessionActive) {
      g_sessionActive = false;
      g_speaking = false;
      g_idleCounting = false;
      WakeWord::resume();
    }
    g_ws.disconnect();
    setState(AiLink::SessionState::Offline);
    logLine("重连退避 %u ms", static_cast<unsigned>(g_reconnectDelayMs));
    vTaskDelay(pdMS_TO_TICKS(g_reconnectDelayMs));
    g_reconnectDelayMs *= 2;
    if (g_reconnectDelayMs > AI_RECONNECT_MAX_MS) {
      g_reconnectDelayMs = AI_RECONNECT_MAX_MS;
    }
  }
}

}  // namespace

// ---------------- 对外接口 ----------------
namespace AiLink {

bool begin() {
  if (g_taskStarted) {
    return true;
  }
  const BaseType_t ok = xTaskCreatePinnedToCore(
      aiTask, "ai_link", 10200, nullptr, 2, &g_task, 0);
  if (ok != pdPASS) {
    Serial.println("[AI] 任务创建失败（内存不足）");
    return false;
  }
  g_taskStarted = true;
  return true;
}

bool isStarted() {
  return g_taskStarted;
}

void wake() {
  g_wakePending = true;
}

void closeSession() {
  // 可能在命令执行路径里调用：只置标志，由 AI 任务发 listen stop。
  g_closePending = true;
}

SessionState state() {
  return g_state;
}

const char* stateName(SessionState value) {
  switch (value) {
    case SessionState::Offline: return "Offline";
    case SessionState::Ready: return "Ready";
    case SessionState::Listening: return "Listening";
    case SessionState::Speaking: return "Speaking";
    default: return "Unknown";
  }
}

}  // namespace AiLink
