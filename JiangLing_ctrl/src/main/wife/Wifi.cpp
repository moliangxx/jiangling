/*
 * ============================================================
 * main/wife/Wifi.cpp —— WiFi 三模式（STA / AP / APSTA）与局域网 TCP 8080 通道
 * ============================================================
 * 连接生命周期全部在本文件：WiFi.mode/begin/softAP/reconnect 只在这里调用，
 * AiLink 只读取 state()/hasInternet()，不碰 WiFi 栈。
 *
 * 模式选择（本方案不做任何网络凭据持久化）：
 *   上电默认 STA（m.h 的 WIFI_DEFAULT_STA_SSID / WIFI_DEFAULT_STA_PASSWORD 连手机热点）；
 *   20 s 未拿到 IP 进 30 s 退避重连，连续 3 次失败才起兜底 AP（192.168.4.1）；
 *   AP 期间后台继续重连，STA 一连上立即关 AP；
 *   配网命令 0xC4/0xC5/0xC6 经 UDP 8081 下发，先暂存 RAM，0xC8 才应用并换网；
 *   常量默认值只读、不写 NVS，0xC5 只覆盖 RAM。
 *
 * 蓝牙已于 2026-11-11 整体移除：本文件是命令的唯一入口，没有第二套解析器。
 *
 * 外网探测放在独立任务（probeTask）里做 DNS 解析，避免阻塞 loop()：
 * 每 60 s 探一次；连上路由器时把下次探测提前到立即执行，结果缓存到 _internetOk
 * （跨核访问一律经 internetProbeOk()/setInternetProbe() 的临界区）。
 *
 * 组帧与 CRC8 都在 Cmd/Dat/Log 内部完成；本文件只做三件事：
 *   收方向：拆包、校验、把 CMD 交给 Cmd::add()（唯一命令入口）；
 *   发方向：Cmd::get()/Dat::get()/Log::get() 取出完整帧后写 socket；
 *   断链即停：STA 掉线或 TCP 回程断开都调用 RuntimeCmd::stopAll()。
 * ============================================================
 */
#include "Wifi.h"

#include <cstring>

#include "../../base/m.h"
#include "../../base/Protocol.h"
#include "../../base/util/cmd/Cmd.h"
#include "../../base/util/data/Data.h"
#include "../../base/util/log/Log.h"
#include "../runtime/RuntimeCmd.h"

using namespace JiangLingProtocol;

namespace {
// 日志码：模块仍用 LOG_MOD_SYSTEM，param 落在协议的「数值/码段」0x20~0x7F，
// 不新增 TYPE、不改协议表。
enum WifiLogCode : uint8_t {
  WIFI_LOG_NOT_CONFIGURED = LOG_PARAM_NUM_BASE + 0,
  WIFI_LOG_ROUTER_CONNECTED = LOG_PARAM_NUM_BASE + 1,
  WIFI_LOG_ROUTER_LOST = LOG_PARAM_NUM_BASE + 2,
  WIFI_LOG_SSID_NOT_FOUND = LOG_PARAM_NUM_BASE + 3,
  WIFI_LOG_AUTH_FAILED = LOG_PARAM_NUM_BASE + 4,
  WIFI_LOG_INTERNET_OK = LOG_PARAM_NUM_BASE + 6,
  WIFI_LOG_INTERNET_LOST = LOG_PARAM_NUM_BASE + 7,
  WIFI_LOG_TCP_CLIENT_CONNECTED = LOG_PARAM_NUM_BASE + 8,
  WIFI_LOG_TCP_CLIENT_DISCONNECTED = LOG_PARAM_NUM_BASE + 9,
  WIFI_LOG_RX_OVERFLOW = LOG_PARAM_NUM_BASE + 10,
  // 0x2B 原「为 BLE 让路」事件：蓝牙移除后不再产生，码值保留不复用。
  WIFI_LOG_LAN_PAUSED_FOR_BLE = LOG_PARAM_NUM_BASE + 11,
  WIFI_LOG_STA_START = LOG_PARAM_NUM_BASE + 12,
  WIFI_LOG_MODE_STA = LOG_PARAM_NUM_BASE + 13,
  WIFI_LOG_MODE_AP = LOG_PARAM_NUM_BASE + 14,
  WIFI_LOG_MODE_APSTA = LOG_PARAM_NUM_BASE + 15,
  WIFI_LOG_AP_FAILED = LOG_PARAM_NUM_BASE + 16,
  // 0x31：连续失败达阈值时会先起兜底 AP，码值不变。
  WIFI_LOG_STA_FAILED = LOG_PARAM_NUM_BASE + 17
};
}  // namespace

WifiCommunication::WifiCommunication()
    : _mode(WifiMode::Sta),
      _state(WifiState::Disconnected),
      _failure(WifiFailureReason::None),
      _started(false),
      _apActive(false),
      _apSsid(WIFI_AP_SSID),
      _apPassword(WIFI_AP_PASSWORD),
      _connectStartMs(0),
      _nextRetryMs(0),
      _staFailCount(0),
      _fallbackApActive(false),
      _attemptSettled(false),
      _internetOk(false),
      _nextProbeMs(0),
      _server(WIFI_TCP_PORT),
      _client(),
      _serverStarted(false),
      _cmdUdp(),
      _cmdUdpStarted(false),
      _cmdUdpBuffer{},
      _discoNextMs(0),
      _discoBurstLeft(0),
      _discoSeq(0),
      _configured(false),
      _rxBuffer{},
      _rxLength(0),
      _lastRxAtMs(0),
      _lastLogSentAtMs(0),
      _callback(nullptr),
      _callbackContext(nullptr) {
}

void WifiCommunication::applyDefaults(const char* defaultSsid,
                                      const char* defaultPassword) {
  // 不读 NVS：上电用编译期常量作 STA 连手机热点；传入值只覆盖 RAM。
  const bool hasSsid = defaultSsid != nullptr && defaultSsid[0] != '\0';
  _ssid = hasSsid ? defaultSsid : WIFI_DEFAULT_STA_SSID;
  _password = hasSsid ? (defaultPassword != nullptr ? defaultPassword : "")
                      : WIFI_DEFAULT_STA_PASSWORD;
  _apSsid = WIFI_AP_SSID;
  _apPassword = WIFI_AP_PASSWORD;
  _mode = WifiMode::Sta;  // 不持久化模式：上电一律先作 STA 连默认热点
}

bool WifiCommunication::begin(const char* ssid, const char* password) {
  if (_started) {
    return true;
  }
  _started = true;
  applyDefaults(ssid, password);

  // 上电默认 STA：applyMode 会 WiFi.begin() 连默认热点，UDP 8081 / TCP 8080
  // 在拿到 IP 后开放；连不上不阻塞后续初始化——超时自停与断链停车都不依赖网络。
  applyMode(_mode);

  // 探测任务与模式无关：AP 下 WiFi.status() 非连，只把外网状态置否，不做探测。
  const BaseType_t ok = xTaskCreatePinnedToCore(
      probeTask, "wifi_probe", 4096, this, 1, nullptr, 1);
  if (ok != pdPASS) {
    // 探测任务创建失败只影响「有外网」判定，不影响局域网通道。
    // 0x27 在 日志.md 中是 I 级（外网状态未知也是状态，不是故障），故用 I。
    JL_LOGI(LOG_MOD_SYSTEM, WIFI_LOG_INTERNET_LOST,
                LOG_SERIAL_TEXT("WiFi: probe task creation failed; internet state unknown."));
  }

  Serial.printf("[WIFI] 启动完成 模式=%s STA_SSID=%s（默认凭据，未持久化）\n",
                modeName(_mode), _ssid.c_str());
  return true;
}

bool WifiCommunication::stageMode(WifiMode mode) {
  if (!_started) {
    return false;
  }
  if (static_cast<uint8_t>(mode) > WIFI_CFG_MODE_MAX) {
    JL_LOGW(LOG_MOD_SYSTEM, WIFI_LOG_CONFIG_INVALID,
                LOG_SERIAL_TEXT("WiFi: mode rejected; out of range."));
    return false;
  }
  // 只做范围预检：凭据可以晚于 0xC4 下发（App 顺序是 0xC4 → 0xC5 → 0xC8），
  // 「切 STA 却没有凭据」在 0xC8 提交时由 commitInit() 拒绝并回 ACK 5。
  return true;
}

bool WifiCommunication::splitCredentials(const uint8_t* payload, uint8_t length,
                                         String& ssid, String& password) {
  if (payload == nullptr || length < WIFI_CREDENTIAL_MIN_LEN) {
    return false;
  }
  const uint8_t ssidLen = payload[0];
  if (ssidLen == 0 || ssidLen > WIFI_SSID_MAX_BYTES ||
      static_cast<uint16_t>(1) + ssidLen > length) {
    return false;
  }
  const uint8_t passwordLen = static_cast<uint8_t>(length - 1 - ssidLen);
  if (passwordLen > WIFI_PASSWORD_MAX_BYTES) {
    return false;
  }
  if (passwordLen != 0 && passwordLen < WIFI_PASSWORD_MIN_BYTES) {
    // WPA2 密码要么为空（开放网络），要么 8~63 B；1~7 B 一定是填错了。
    return false;
  }
  ssid = String(reinterpret_cast<const char*>(payload + 1), ssidLen);
  password = passwordLen == 0
                 ? String()
                 : String(reinterpret_cast<const char*>(payload + 1 + ssidLen),
                          passwordLen);
  return !ssid.isEmpty();
}

bool WifiCommunication::stageStaCredentials(const uint8_t* payload,
                                            uint8_t length) {
  String ssid;
  String password;
  if (!splitCredentials(payload, length, ssid, password)) {
    JL_LOGW(LOG_MOD_SYSTEM, WIFI_LOG_CONFIG_INVALID,
                LOG_SERIAL_TEXT("WiFi: STA credentials rejected; malformed payload."));
    return false;
  }

  // 只存 RAM、不重连：WiFi 仍跑现有网络，直到 0xC8 提交后才切换。
  _ssid = ssid;
  _password = password;
  JL_LOGI(LOG_MOD_SYSTEM, WIFI_LOG_CONFIG_APPLIED,
           LOG_SERIAL_TEXT("WiFi: STA credentials staged (RAM only)."));
  return true;
}

bool WifiCommunication::stageApCredentials(const uint8_t* payload,
                                           uint8_t length) {
  String ssid;
  String password;
  if (!splitCredentials(payload, length, ssid, password)) {
    JL_LOGW(LOG_MOD_SYSTEM, WIFI_LOG_CONFIG_INVALID,
                LOG_SERIAL_TEXT("WiFi: AP credentials rejected; malformed payload."));
    return false;
  }

  // 只存 RAM：热点若已开着也不重建，等 0xC8 一起应用。
  _apSsid = ssid;
  _apPassword = password;
  JL_LOGI(LOG_MOD_SYSTEM, WIFI_LOG_CONFIG_APPLIED,
           LOG_SERIAL_TEXT("WiFi: AP credentials staged (RAM only)."));
  return true;
}

bool WifiCommunication::commitInit(WifiMode mode) {
  if (!_started) {
    return false;
  }
  if (static_cast<uint8_t>(mode) > WIFI_CFG_MODE_MAX) {
    return false;
  }
  if (mode == WifiMode::Sta && _ssid.length() == 0) {
    return false;
  }
  if (mode == WifiMode::Ap && _apSsid.length() == 0) {
    return false;
  }
  // 先关旧网络与旧回程，再按暂存的模式/凭据起网；都是 RAM 生效，不写 NVS。
  applyMode(mode);
  _configured = true;  // 0x87.flags.bit0：本次运行已应用过 App 下发的凭据
  JL_LOGI(LOG_MOD_SYSTEM, WIFI_LOG_CONFIG_APPLIED,
           LOG_SERIAL_TEXT("WiFi: init committed; reconnecting with staged config."));
  return true;
}

void WifiCommunication::factoryReset() {
  // 清掉本次运行的配网数据，回到「刚上电」状态：常量凭据 + STA；调用方随后重启。
  _ssid = WIFI_DEFAULT_STA_SSID;
  _password = WIFI_DEFAULT_STA_PASSWORD;
  _apSsid = WIFI_AP_SSID;
  _apPassword = WIFI_AP_PASSWORD;
  _mode = WifiMode::Sta;
  _configured = false;
  _failure = WifiFailureReason::None;
  _staFailCount = 0;
  _fallbackApActive = false;
  _attemptSettled = false;
  Serial.println("[WIFI] 恢复出厂：RAM 配网数据已清空，重启后回常量网络");
}

void WifiCommunication::applyMode(WifiMode next) {
  stopNetwork();
  _mode = next;
  _failure = WifiFailureReason::None;
  setInternetProbe(false, 0);
  _staFailCount = 0;
  _fallbackApActive = false;
  _attemptSettled = false;
  _discoBurstLeft = 0;
  _discoNextMs = 0;
  _connectStartMs = millis();
  _nextRetryMs = 0;
  setState(WifiState::Disconnected);

  if (_mode == WifiMode::Sta) {
    startSta();
    JL_LOGI(LOG_MOD_SYSTEM, WIFI_LOG_MODE_STA,
             LOG_SERIAL_TEXT("WiFi: mode STA."));
    return;
  }

  if (_mode == WifiMode::Ap) {
    startAp();
    JL_LOGI(LOG_MOD_SYSTEM, WIFI_LOG_MODE_AP,
             LOG_SERIAL_TEXT("WiFi: mode AP."));
    return;
  }

  // APSTA：先开热点，再并行尝试路由器；热点始终可用。
  WiFi.mode(WIFI_AP_STA);
  WiFi.setSleep(false);
  WiFi.persistent(false);
  WiFi.setAutoReconnect(false);
  if (!startAp()) {
    JL_LOGW(LOG_MOD_SYSTEM, WIFI_LOG_AP_FAILED,
                LOG_SERIAL_TEXT("WiFi: APSTA hotpot failed to start."));
  }
  if (_ssid.length() > 0) {
    WiFi.begin(_ssid.c_str(), _password.c_str());
  }
  JL_LOGI(LOG_MOD_SYSTEM, WIFI_LOG_MODE_APSTA,
           LOG_SERIAL_TEXT("WiFi: mode APSTA."));
}

void WifiCommunication::startSta() {
  WiFi.persistent(false);
  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false);
  WiFi.setAutoReconnect(false);
  WiFi.begin(_ssid.c_str(), _password.c_str());
}

bool WifiCommunication::startAp() {
  if (_mode == WifiMode::Ap) {
    WiFi.persistent(false);
    WiFi.mode(WIFI_AP);
    WiFi.setSleep(false);
  }

  const bool ok = WiFi.softAP(_apSsid.c_str(), _apPassword.c_str(),
                              WIFI_AP_CHANNEL, false, WIFI_AP_MAX_CLIENTS);
  if (!ok) {
    _apActive = false;
    JL_LOGW(LOG_MOD_SYSTEM, WIFI_LOG_AP_FAILED,
                LOG_SERIAL_TEXT("WiFi: softAP failed to start."));
    Serial.println("[WIFI] AP 启动失败");
    return false;
  }

  _apActive = true;
  // 热点一起来就连发 3 帧（AP / 兜底 AP 也要能被自动发现），之后转 5 s 周期。
  _discoBurstLeft = WIFI_DISCOVERY_BURST_FRAMES;
  _discoNextMs = millis();
  if (!_serverStarted) {
    _server.begin(WIFI_TCP_PORT);
    _serverStarted = true;
  }
  Serial.printf("[WIFI] AP 已启动 SSID=%s IP=%s TCP=%u\n",
                _apSsid.c_str(),
                WiFi.softAPIP().toString().c_str(),
                static_cast<unsigned>(WIFI_TCP_PORT));
  return true;
}

void WifiCommunication::startFallbackAp() {
  if (_fallbackApActive) {
    return;
  }
  // APSTA：热点起来的同时保留 STA，后台继续按 30 s 重连；
  // 连上后由 handleRouterUp() 关掉热点，回到纯 STA。
  WiFi.mode(WIFI_AP_STA);
  WiFi.setSleep(false);
  WiFi.persistent(false);
  WiFi.setAutoReconnect(false);
  if (!startAp()) {
    // 热点起不来只记日志：退避重连照旧，不影响本地停止逻辑。
    Serial.println("[WIFI] 兜底 AP 启动失败，继续重连 STA");
    return;
  }
  _fallbackApActive = true;
  Serial.printf("[WIFI] 连续 %u 次连不上默认网络，已起兜底 AP SSID=%s IP=%s\n",
                static_cast<unsigned>(_staFailCount), _apSsid.c_str(),
                WiFi.softAPIP().toString().c_str());
  JL_LOGW(LOG_MOD_SYSTEM, WIFI_LOG_MODE_AP,
              LOG_SERIAL_TEXT("WiFi: fallback AP started; STA retry continues."));
}

void WifiCommunication::stopNetwork() {
  // 模式切换前先把已建立的 TCP 连接清干净，避免留下半开连接。
  closeClient("模式切换");
  _rxLength = 0;
  // UDP 命令口随网络一起关闭，模式切换后由 serveCmdUdp() 重新绑定。
  if (_cmdUdpStarted) {
    _cmdUdp.stop();
    _cmdUdpStarted = false;
  }

  if (_apActive) {
    WiFi.softAPdisconnect(true);
    _apActive = false;
  }
  if (_mode != WifiMode::Ap) {
    WiFi.disconnect(true);
  }
}

void WifiCommunication::update() {
  if (!_started) {
    return;
  }
  const uint32_t nowMs = millis();

  if (_mode == WifiMode::Ap) {
    // AP 模式没有路由器可等：state 恒为 Disconnected，LAN 由热点提供。
    setInternetProbe(false, 0);
    serveCmdUdp();          // 命令主通道先于回程处理
    serveDiscovery(nowMs);  // AP 档同样广播 0x87，App 不必手填 192.168.4.1
    serveLan(nowMs);
    return;
  }

  // STA 或 APSTA：先推进路由器连接状态，再服务 LAN。
  if (WiFi.status() == WL_CONNECTED) {
    handleRouterUp(nowMs);
  } else {
    handleRouterDown(nowMs);
  }
  // 命令优先：先收 UDP 8081，再处理 TCP 回程，避免命令被回程写阻塞。
  serveCmdUdp();
  serveDiscovery(nowMs);
  serveLan(nowMs);
}

void WifiCommunication::handleRouterUp(uint32_t nowMs) {
  // 兜底 AP 只是配网/等待窗口：STA 一连上就关热点，回到纯 STA。
  if (_fallbackApActive) {
    _fallbackApActive = false;
    closeClient("关闭兜底 AP");
    if (_apActive) {
      WiFi.softAPdisconnect(true);
      _apActive = false;
    }
    WiFi.mode(WIFI_STA);
    WiFi.setSleep(false);
    // 模式切换会让原来的监听套接字失效：下面按 _serverStarted=false 重新 begin。
    _server.stop();
    _serverStarted = false;
    Serial.println("[WIFI] STA 已连上，关闭兜底 AP");
    JL_LOGI(LOG_MOD_SYSTEM, WIFI_LOG_MODE_STA,
             LOG_SERIAL_TEXT("WiFi: STA recovered; fallback AP closed."));
  }
  _staFailCount = 0;

  if (_state == WifiState::Disconnected) {
    _failure = WifiFailureReason::None;
    _connectStartMs = nowMs;
    setInternetProbe(internetProbeOk(), nowMs);  // 连上后立即探测一次外网
    if (!_serverStarted) {
      _server.begin(WIFI_TCP_PORT);
      _serverStarted = true;
    }
    Serial.printf("[WIFI] 已连路由器 IP=%s RSSI=%d 模式=%s\n",
                  WiFi.localIP().toString().c_str(),
                  static_cast<int>(WiFi.RSSI()),
                  modeName(_mode));
    // 拿到 IP 后连发 3 帧地址广播，之后转 5 s 周期（数据.md §5）。
    _discoBurstLeft = WIFI_DISCOVERY_BURST_FRAMES;
    _discoNextMs = nowMs;
    JL_LOGI(LOG_MOD_SYSTEM, WIFI_LOG_ROUTER_CONNECTED,
             LOG_SERIAL_TEXT("WiFi: router connected; LAN channel ready."));
  }

  const WifiState next =
      internetProbeOk() ? WifiState::Internet : WifiState::LanOnly;
  setState(next);
  _attemptSettled = false;
}

void WifiCommunication::handleRouterDown(uint32_t nowMs) {
  if (_state != WifiState::Disconnected) {
    closeClient("路由器断开");
    // 断链即停：与 300 ms 命令看门狗是两层保护，这里立即清目标。
    Runtime.stopAll();
    setState(WifiState::Disconnected);
    setInternetProbe(false, 0);
    _connectStartMs = nowMs;
    JL_LOGW(LOG_MOD_SYSTEM, WIFI_LOG_ROUTER_LOST,
                LOG_SERIAL_TEXT("WiFi: router connection lost; LAN channel down."));
  }

  // 失败原因只在变化时记录一次，避免每次重试都刷屏。
  WifiFailureReason reason = _failure;
  switch (WiFi.status()) {
    case WL_NO_SSID_AVAIL:
      reason = WifiFailureReason::SsidNotFound;
      break;
    case WL_CONNECT_FAILED:
      reason = WifiFailureReason::AuthFailed;
      break;
    case WL_CONNECTION_LOST:
      reason = WifiFailureReason::ConnectionLost;
      break;
    default:
      break;
  }
  if (reason != WifiFailureReason::ConnectTimeout &&
      static_cast<uint32_t>(nowMs - _connectStartMs) > WIFI_CONNECT_TIMEOUT_MS) {
    reason = WifiFailureReason::ConnectTimeout;
  }

  if (reason != _failure) {
    _failure = reason;
    switch (reason) {
      case WifiFailureReason::SsidNotFound:
        JL_LOGW(LOG_MOD_SYSTEM, WIFI_LOG_SSID_NOT_FOUND,
                    LOG_SERIAL_TEXT("WiFi: SSID not found; check 2.4 GHz router and signal."));
        break;
      case WifiFailureReason::AuthFailed:
        JL_LOGW(LOG_MOD_SYSTEM, WIFI_LOG_AUTH_FAILED,
                    LOG_SERIAL_TEXT("WiFi: authentication failed; check SSID/password."));
        break;
      case WifiFailureReason::ConnectTimeout:
        JL_LOGW(LOG_MOD_SYSTEM, WIFI_LOG_CONNECT_TIMEOUT,
                    LOG_SERIAL_TEXT("WiFi: connect timeout."));
        break;
      case WifiFailureReason::ConnectionLost:
        JL_LOGW(LOG_MOD_SYSTEM, WIFI_LOG_ROUTER_LOST,
                    LOG_SERIAL_TEXT("WiFi: connection lost; retrying with backoff."));
        break;
      default:
        break;
    }
  }

  // 一次连接尝试结算一次：超时才计失败，避免刚 begin 就被判失败。
  if (static_cast<uint32_t>(nowMs - _connectStartMs) > WIFI_CONNECT_TIMEOUT_MS &&
      !_attemptSettled) {
    _attemptSettled = true;
    if (_staFailCount < 0xFF) {
      ++_staFailCount;
    }
    Serial.printf("[WIFI] STA 第 %u 次连接失败，%u s 后重试\n",
                  static_cast<unsigned>(_staFailCount),
                  static_cast<unsigned>(WIFI_STA_RETRY_INTERVAL_MS / 1000));
    JL_LOGW(LOG_MOD_SYSTEM, WIFI_LOG_STA_FAILED,
                LOG_SERIAL_TEXT("WiFi: STA attempt failed; retry in 30 s."));
    // 连续失败到阈值才起兜底 AP；AP 期后台仍按同一节奏重连。
    if (_mode == WifiMode::Sta && !_fallbackApActive &&
        _staFailCount >= WIFI_FALLBACK_AP_AFTER_FAILS) {
      startFallbackAp();
    }
    _nextRetryMs = nowMs + WIFI_STA_RETRY_INTERVAL_MS;
  }

  // 退避到点才重试；重试即开始新一次尝试，重新计 20 s 超时。
  if (_attemptSettled && static_cast<int32_t>(nowMs - _nextRetryMs) >= 0) {
    _attemptSettled = false;
    _connectStartMs = nowMs;
    WiFi.reconnect();
  }
}

void WifiCommunication::serveCmdUdp() {
  if (!isLanReady()) {
    return;
  }
  if (!_cmdUdpStarted) {
    // INADDR_ANY：STA 换 IP、AP 切换都不需要重新绑定。
    if (!_cmdUdp.begin(WIFI_UDP_CMD_PORT)) {
      return;  // 绑定失败下轮再试，不影响 TCP 回程
    }
    _cmdUdpStarted = true;
    Serial.printf("[WIFI] UDP 命令通道就绪 端口=%u\n",
                  static_cast<unsigned>(WIFI_UDP_CMD_PORT));
  }

  // 非阻塞：一次把排队的数据报全部取完，命令优先于回程。
  while (true) {
    const int size = _cmdUdp.parsePacket();
    if (size <= 0) {
      break;
    }
    // 必须先 read 再判长：WiFiUDP 未读走时 parsePacket() 可能反复报告同一包，
    // 直接 continue 会让本循环无法退出（表现为主循环卡死）。
    const int readBytes = _cmdUdp.read(_cmdUdpBuffer, sizeof(_cmdUdpBuffer));
    if (readBytes <= 0) {
      continue;
    }
    if (size > static_cast<int>(WIFI_UDP_MAX_FRAME_BYTES)) {
      continue;  // 超长包一定不是合法 V21 帧，已读走并丢弃
    }
    handleCmdDatagram(_cmdUdpBuffer, static_cast<size_t>(readBytes));
  }

  // ACK 走回程 TCP 8080；TCP 未接入时回复留在 Cmd 队列里（容量 8，满了计数丢弃）。
  if (_client.connected()) {
    flushCmdReplies();
  }
}

void WifiCommunication::serveDiscovery(uint32_t nowMs) {
  // 两种档位都广播：STA 已连发 STA 地址；只有热点（AP / 兜底 AP）时发 192.168.4.1。
  // App 在 AP 档也能自动发现，不必手填 IP（数据.md §5）。
  const bool staReady = WiFi.status() == WL_CONNECTED;
  if (!staReady && !_apActive) {
    return;  // 既没连路由器也没开热点，没有可广播的本机地址
  }
  if (!_cmdUdpStarted) {
    return;  // 与命令通道复用同一个已绑定套接字，不额外占端口
  }
  if (static_cast<int32_t>(nowMs - _discoNextMs) < 0) {
    return;
  }

  // 复用 0x87 DAT_INIT_STATE 原帧：TCP 上报与 UDP 广播同一份 payload（数据.md §5）。
  const bool apIdentity = !staReady;
  const IPAddress selfIp = apIdentity ? WiFi.softAPIP() : WiFi.localIP();
  const IPAddress selfMask =
      apIdentity ? WiFi.softAPSubnetMask() : WiFi.subnetMask();

  uint8_t frame[frameBytesOf(DAT_INIT_STATE_LEN)] = {};
  beginFrame(frame, _discoSeq++, DAT_INIT_STATE, DAT_INIT_STATE_LEN);
  uint8_t* payload = frame + FRAME_HEADER_BYTES;
  const uint8_t wifiState = _state == WifiState::Internet
                                ? 2
                                : (_state == WifiState::LanOnly ? 1 : 0);
  payload[0] = apIdentity ? 4 : 3;  // stage：3=STA 已拿到 IP；4=热点已起（降级）
  payload[1] = apIdentity ? 1 : 0;  // wifiMode：0=STA、1=AP（兜底 AP 也说 1）
  payload[2] = wifiState;
  payload[3] = 0;         // peerStage：无副板心跳时恒 0
  uint8_t flags = 0;
  if (_configured) {
    flags |= 0x01;        // bit0 configured
  }
  if (wifiState == 2) {
    flags |= 0x08;        // bit3 internetOk
  }
  payload[4] = flags;
  // payload 的 IPv4 必须是数据包源地址（App 会比对），所以按当前出口填。
  for (uint8_t index = 0; index < 4; ++index) {
    payload[5 + index] = selfIp[index];
  }
  finishFrame(frame, DAT_INIT_STATE_LEN);

  // 受限广播与子网广播各发一份：手机热点上单发 255.255.255.255 有时到不了 App。
  // AP 档子网广播即 192.168.4.255。
  const uint32_t ipRaw = static_cast<uint32_t>(selfIp);
  const uint32_t maskRaw = static_cast<uint32_t>(selfMask);
  const uint32_t subnetBcast = ipRaw | ~maskRaw;
  sendDiscoveryFrame(IPAddress(255, 255, 255, 255), frame, sizeof(frame));
  if (maskRaw != 0 && subnetBcast != 0xFFFFFFFFu && subnetBcast != ipRaw) {
    const IPAddress bcast(static_cast<uint8_t>((subnetBcast >> 24) & 0xFF),
                          static_cast<uint8_t>((subnetBcast >> 16) & 0xFF),
                          static_cast<uint8_t>((subnetBcast >> 8) & 0xFF),
                          static_cast<uint8_t>(subnetBcast & 0xFF));
    sendDiscoveryFrame(bcast, frame, sizeof(frame));
  }

  if (_discoBurstLeft > 0) {
    --_discoBurstLeft;
    _discoNextMs = nowMs + WIFI_DISCOVERY_BURST_INTERVAL_MS;
  } else {
    _discoNextMs = nowMs + WIFI_DISCOVERY_INTERVAL_MS;
  }
}

void WifiCommunication::sendDiscoveryFrame(const IPAddress& target,
                                           const uint8_t* frame,
                                           size_t length) {
  _cmdUdp.beginPacket(target, WIFI_DISCOVERY_PORT);
  _cmdUdp.write(frame, length);
  _cmdUdp.endPacket();
}

void WifiCommunication::handleCmdDatagram(const uint8_t* data, size_t length) {
  // 一包一帧：长度必须等于 5 + LEN，短包/粘包都整包丢弃，不跨包拼接。
  if (data == nullptr || length < FRAME_HEADER_BYTES + 1) {
    return;
  }
  if (data[0] != FRAME_HEAD) {
    return;
  }
  const uint8_t type = data[2];
  const uint8_t payloadLength = data[3];
  if (!payloadLengthValid(type, payloadLength)) {
    return;
  }
  if (length != frameBytesOf(payloadLength)) {
    return;
  }
  if (crc8(data, length - 1) != data[length - 1]) {
    return;
  }
  if (kindOf(type) != KIND_CMD) {
    return;  // 方向非法：UDP 只接受 App → 板 的 CMD
  }
  // 唯一解析入口：会话门禁、去重、参数范围、ACK 组包都在 Cmd 内。
  Cmd.add(data[1], type, data + FRAME_HEADER_BYTES, payloadLength);}

void WifiCommunication::serveLan(uint32_t nowMs) {
  if (!isLanReady()) {
    return;
  }

  // 运行期只有 WiFi 一条链路：命令/ACK/日志/遥测同进同出，不需要通道让路。
  acceptClient();
  if (!_client.connected()) {
    return;
  }

  // 读 socket：一次最多 64 B，循环到 available() 为空，不等待。
  uint8_t chunk[RX_CHUNK_BYTES];
  while (_client.available()) {
    const size_t readBytes = _client.read(chunk, sizeof(chunk));
    if (readBytes == 0) {
      break;
    }
    processRxData(chunk, readBytes);
  }

  if (!_client.connected() && !_client.available()) {
    closeClient("TCP 客户端断开");
    return;
  }

  flushCmdReplies();
  drainFrames(nowMs);
}

void WifiCommunication::acceptClient() {
  if (_client.connected()) {
    return;
  }
  WiFiClient incoming = _server.accept();
  if (!incoming) {
    return;
  }
  _client = incoming;
  _client.setNoDelay(true);
  _rxLength = 0;
  _lastRxAtMs = millis();
  // 新链路从「未握手」开始，等待 App 发 CMD_SESSION_INIT。
  // 先清掉上一条链路残留的 ACK，避免新客户端收到旧 SEQ 的回执。
  Cmd.clear();
  Cmd.resetSession();
  Serial.printf("[WIFI] TCP 客户端接入：%s\n",
                _client.remoteIP().toString().c_str());
  JL_LOGI(LOG_MOD_SYSTEM, WIFI_LOG_TCP_CLIENT_CONNECTED,
           LOG_SERIAL_TEXT("WiFi: LAN TCP client connected."));
}

void WifiCommunication::closeClient(const char* reason) {
  if (!_client.connected() && _rxLength == 0) {
    return;
  }
  _client.stop();
  _rxLength = 0;
  // 运行期只有 WiFi 一条会话，断开就清干净（含未发完的 ACK）。
  Cmd.clear();
  Cmd.resetSession();
  // DAT 现在不受握手门禁限制，必须清残留，避免旧连接排队的帧发到新客户端。
  Dat.clear();
  // 断链即停：回程一断立即清目标，不等 300 ms 看门狗。
  Runtime.stopAll();
  Serial.printf("[WIFI] TCP 客户端关闭：%s\n", reason != nullptr ? reason : "");
  JL_LOGI(LOG_MOD_SYSTEM, WIFI_LOG_TCP_CLIENT_DISCONNECTED,
           LOG_SERIAL_TEXT("WiFi: LAN TCP client disconnected."));
}

void WifiCommunication::processRxData(const uint8_t* data, size_t length) {
  if (length == 0) {
    return;
  }
  if (length > RX_BUFFER_BYTES || _rxLength + length > RX_BUFFER_BYTES) {
    // 超长写入多半是链路错位：直接断开，让下一次连接从干净状态开始。
    JL_LOGW(LOG_MOD_SYSTEM, WIFI_LOG_RX_OVERFLOW,
                LOG_SERIAL_TEXT("WiFi: RX buffer overflow; closing TCP client."));
    closeClient("RX 缓冲溢出");
    return;
  }
  std::memcpy(_rxBuffer + _rxLength, data, length);
  _rxLength = static_cast<uint16_t>(_rxLength + length);
  _lastRxAtMs = millis();
  parseRxBuffer();
}

void WifiCommunication::parseRxBuffer() {
  while (_rxLength > 0) {
    // 只处理方向合法的 CMD；ACK/LOG/DAT 属于板 → App，收到一律丢弃。
    if (_rxBuffer[0] != FRAME_HEAD) {
      dropRx(1);
      continue;
    }
    if (_rxLength < FRAME_HEADER_BYTES) {
      break;  // 还没收到 LEN，无法定界
    }
    const uint8_t type = _rxBuffer[2];
    const uint8_t payloadLength = _rxBuffer[3];
    if (!payloadLengthValid(type, payloadLength)) {
      // TYPE 未知或 LEN 与 TYPE 允许范围不符：丢头重扫，并记一次错误日志。
      JL_LOGW(LOG_MOD_SYSTEM, WIFI_LOG_LEN_INVALID,
                  LOG_SERIAL_TEXT("WiFi: frame dropped; TYPE/LEN mismatch."));
      dropRx(1);
      continue;
    }
    const size_t frameLength = frameBytesOf(payloadLength);
    if (_rxLength < frameLength) {
      break;  // 等剩余字节，超时由末尾兜底清缓冲
    }
    if (crc8(_rxBuffer, frameLength - 1) != _rxBuffer[frameLength - 1]) {
      dropRx(1);
      continue;
    }

    if (kindOf(type) == KIND_CMD) {
      // 协议层校验（SEQ=0、会话门禁、去重、参数范围）都在 Cmd 内完成。
      Cmd.add(_rxBuffer[1], type, _rxBuffer + FRAME_HEADER_BYTES, payloadLength);
    }
    dropRx(static_cast<uint16_t>(frameLength));
  }

  if (_rxLength > 0 &&
      static_cast<uint32_t>(millis() - _lastRxAtMs) > PARTIAL_FRAME_TIMEOUT_MS) {
    // 半截帧超时：丢弃缓冲重新同步，防止一次错位把后续帧全带偏。
    _rxLength = 0;
  }
}

void WifiCommunication::flushCmdReplies() {
  uint8_t frame[CMD_REPLY_FRAME_BYTES];
  bool infoRound = false;
  while (Cmd.get(frame, infoRound)) {
    // 写不出去说明对端已断或发送缓冲异常：按断链处理，立即停车。
    if (_client.write(frame, CMD_REPLY_FRAME_BYTES) == 0) {
      closeClient("ACK 写入失败");
      break;
    }
    // CMD_REQ_INFO 的 DAT_INFO 与握手补拉：帧由 RuntimeCmd 统一组装进 Dat 队列。
    if (infoRound) {
      Runtime.sendDeviceInfoRound();
    }
    Runtime.applyPendingActions();
  }
}

void WifiCommunication::drainFrames(uint32_t nowMs) {
  // DAT 不再依赖握手：0x86 兼作回程心跳，客户端一连上就要发（App 9 s 断链判定）。
  // LOG 仍只在会话就绪后推送，避免未握手时刷屏。

  // DAT 先于 LOG：两者同走 TCP 8080，遥测优先占发送缓冲。
  uint8_t frame[DAT_FRAME_MAX_BYTES];
  uint8_t length = 0;
  while (Dat.get(frame, length)) {
    _client.write(frame, length);
  }

  if (!Cmd.isSessionReady()) {
    return;
  }

  // LOG 限速：一帧 20 ms，避免把 TCP 写缓冲瞬间灌满；通道开关只在 Log.h 定义。
  if (Log.channelEnabled(LOG_CHANNEL_FRAME) &&
      static_cast<uint32_t>(nowMs - _lastLogSentAtMs) >=
          FRAME_SEND_INTERVAL_MS) {
    uint8_t logFrame[LOG_FRAME_MAX_BYTES];
    uint8_t logLength = 0;
    if (Log.get(logFrame, logLength)) {
      _client.write(logFrame, logLength);
      _lastLogSentAtMs = nowMs;
    }
  }
}

void WifiCommunication::dropRx(uint16_t count) {
  if (count >= _rxLength) {
    _rxLength = 0;
    return;
  }
  std::memmove(_rxBuffer, _rxBuffer + count, _rxLength - count);
  _rxLength = static_cast<uint16_t>(_rxLength - count);
}

void WifiCommunication::probeTask(void* arg) {
  WifiCommunication* self = static_cast<WifiCommunication*>(arg);
  while (true) {
    if (WiFi.status() == WL_CONNECTED) {
      const uint32_t nowMs = millis();
      if (static_cast<int32_t>(nowMs - self->probeNextMs()) >= 0) {
        IPAddress resolved;
        const bool ok = WiFi.hostByName(PROBE_HOST, resolved) == 1;
        // 结果与下次探测时间一起写，读写都在临界区内，避免跨核看到半更新状态。
        self->setInternetProbe(ok, millis() + WIFI_PROBE_INTERVAL_MS);
      }
    } else {
      self->setInternetProbe(false, 0);
    }
    vTaskDelay(pdMS_TO_TICKS(1000));
  }
}

bool WifiCommunication::internetProbeOk() const {
  portENTER_CRITICAL(&_probeMux);
  const bool ok = _internetOk;
  portEXIT_CRITICAL(&_probeMux);
  return ok;
}

uint32_t WifiCommunication::probeNextMs() const {
  portENTER_CRITICAL(&_probeMux);
  const uint32_t nextMs = _nextProbeMs;
  portEXIT_CRITICAL(&_probeMux);
  return nextMs;
}

void WifiCommunication::setInternetProbe(bool ok, uint32_t nextMs) {
  portENTER_CRITICAL(&_probeMux);
  _internetOk = ok;
  _nextProbeMs = nextMs;
  portEXIT_CRITICAL(&_probeMux);
}

void WifiCommunication::setState(WifiState next) {
  if (next == _state) {
    return;
  }
  _state = next;

  if (next == WifiState::Internet) {
    Serial.printf("[WIFI] 外网探测成功 IP=%s\n", WiFi.localIP().toString().c_str());
    JL_LOGI(LOG_MOD_SYSTEM, WIFI_LOG_INTERNET_OK,
             LOG_SERIAL_TEXT("WiFi: internet probe OK."));
  } else if (next == WifiState::LanOnly) {
    // 只在从「有外网」降级时记录一次，避免每次探测失败都打日志。
    JL_LOGI(LOG_MOD_SYSTEM, WIFI_LOG_INTERNET_LOST,
             LOG_SERIAL_TEXT("WiFi: router connected but internet unavailable."));
  }

  if (_callback != nullptr) {
    _callback(_state, _callbackContext);
  }
}

WifiMode WifiCommunication::mode() const {
  return _mode;
}

WifiState WifiCommunication::state() const {
  return _state;
}

WifiFailureReason WifiCommunication::failureReason() const {
  return _failure;
}

bool WifiCommunication::isLanReady() const {
  return _apActive || _state == WifiState::LanOnly ||
         _state == WifiState::Internet;
}

bool WifiCommunication::hasInternet() const {
  return _state == WifiState::Internet;
}

String WifiCommunication::localIp() const {
  if (_mode == WifiMode::Ap || (_apActive && _state == WifiState::Disconnected)) {
    return WiFi.softAPIP().toString();
  }
  return WiFi.localIP().toString();
}

String WifiCommunication::macAddress() const {
  return WiFi.macAddress();
}

bool WifiCommunication::localIpBytes(uint8_t out[4]) const {
  if (out == nullptr) {
    return false;
  }
  const IPAddress ip =
      (_mode == WifiMode::Ap || (_apActive && _state == WifiState::Disconnected))
          ? WiFi.softAPIP()
          : WiFi.localIP();
  const bool valid = static_cast<uint32_t>(ip) != 0;
  for (uint8_t index = 0; index < 4; ++index) {
    out[index] = valid ? ip[index] : 0;
  }
  return valid;
}

int32_t WifiCommunication::rssi() const {
  return WiFi.RSSI();
}

bool WifiCommunication::hasTcpClient() const {
  return _client.connected();
}

void WifiCommunication::setStateCallback(StateCallback callback,
                                         void* context) {
  _callback = callback;
  _callbackContext = context;
}

const char* WifiCommunication::modeName(WifiMode mode) {
  switch (mode) {
    case WifiMode::Sta: return "STA";
    case WifiMode::Ap: return "AP";
    case WifiMode::ApSta: return "APSTA";
    default: return "未知";
  }
}

const char* WifiCommunication::stateName(WifiState state) {
  switch (state) {
    case WifiState::Disconnected: return "未连路由器";
    case WifiState::LanOnly: return "已连路由器（无外网）";
    case WifiState::Internet: return "已连路由器（有外网）";
    default: return "未知";
  }
}

const char* WifiCommunication::failureName(WifiFailureReason reason) {
  switch (reason) {
    case WifiFailureReason::None: return "无";
    case WifiFailureReason::NotConfigured: return "未配置 SSID/密码";
    case WifiFailureReason::SsidNotFound: return "找不到 SSID";
    case WifiFailureReason::AuthFailed: return "密码或加密方式不对";
    case WifiFailureReason::ConnectTimeout: return "连接超时";
    case WifiFailureReason::ConnectionLost: return "连接中断";
    default: return "未知";
  }
}

WifiCommunication Wifi;
