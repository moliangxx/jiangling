/*
 * ============================================================
 * main/wife/Wifi.h —— WiFi 连接与局域网通道（仅近程，不做公网）
 * ============================================================
 * 【职责】
 *   1. WiFi 连接的唯一管理者：begin()、模式切换、状态查询、断线处理、事件回调；
 *   2. 三种接入模式：
 *        STA     —— 连路由器；连上后局域网易用，有外网时 AI 通道可用；
 *        AP      —— 板子自建热点（192.168.4.1），无路由器/无外网也能用 TCP 8080；
 *        APSTA   —— 同时开热点并连路由器；连不上路由器时手机仍可直连热点；
 *   3. 对外只暴露两种「可用性」+ 当前模式：
 *        - 连上路由器（LAN 可用，不需要外网）
 *        - 有外网（AI 云端 OTA/WSS 才需要）
 *      局域网通道门禁 = 已连路由器 **或** AP 已开启；
 *   4. 局域网通道：**UDP 8081 收 CMD（运行期唯一命令入口）**，TCP 8080 收发 ACK / LOG / DAT；
 *      命令交给 Cmd::add()，由 RuntimeCmd 执行（初始化命令也在这一条路径上）。
 *      发方向从 Cmd / Dat / Log 的 get() 取完整帧直接写 socket，本类不组帧、不算 CRC。
 *
 * 【为什么命令走 UDP】
 *   TCP 队头阻塞会让操控一顿一顿；UDP 丢帧不补，下一帧自然接上。
 *   UDP 数据报一包一帧，报文长度 ≠ 5 + LEN 即整包丢弃，不跨包拼接。
 *   回程仍是 TCP 8080，不与操控抢同一条队头。
 *
 * 【回落规则】
 *   上电默认 STA：用 m.h 的 WIFI_DEFAULT_STA_SSID / WIFI_DEFAULT_STA_PASSWORD 直连手机热点；
 *   20 s 未拿到 IP 进退避重连，每 30 s 重试；连续 3 次失败才起兜底 AP（192.168.4.1）；
 *   AP 期间后台仍每 30 s 试 STA，一连上就关 AP（总览 §2.2）。
 *   AP / APSTA 也作为 App 显式换网的入口（0xC4=1/2 + 0xC8）。
 *
 * 【唯一通道】
 *   蓝牙已于 2026-11-11 整体移除：配网、运行期命令、ACK/LOG/DAT 全部走 WiFi，
 *   不存在第二条通道，也不存在通道让路或仲裁。
 *   任一条链路断开都立即停车（与 300 ms 命令看门狗是两层保护）。
 *
 * 【与 AiLink 的关系】
 *   AiLink 不再自己调 WiFi.begin()，只向本类询问 hasInternet()；
 *   STA 连接、重连、外网探测全部由本类完成，AiLink 只做 OTA + WSS。
 *
 * 【不做的事】
 *   不做 WSS/443、不做公网穿透、不做鉴权令牌；
 *   不做配网界面、不做 NVS 存凭据；配网经 UDP 8081 暂存到 RAM，0xC8 提交后按 RAM 参数生效。
 * ============================================================
 */
#ifndef WIFI_LINK_H
#define WIFI_LINK_H

#include <Arduino.h>
#include <WiFi.h>
#include <WiFiUdp.h>

#include "../../base/Protocol.h"

// 局域网命令通道端口（TCP，收 ACK/DAT/LOG 回程 + 历史 CMD 入口）。
constexpr uint16_t WIFI_TCP_PORT = 8080;
// 命令主通道端口（UDP，一包一帧收 CMD）；语音占 8888，互不冲突。
constexpr uint16_t WIFI_UDP_CMD_PORT = 8081;
// 局域网 IP 广播发现端口（UDP 广播，复用 0x87 原帧）；App 监听同一端口，见《数据.md》§5。
constexpr uint16_t WIFI_DISCOVERY_PORT = 8082;
// 连上后连发 3 帧（间隔 200 ms），之后每 5 s 一帧；间隔只是实现常量，协议不约束。
constexpr uint8_t WIFI_DISCOVERY_BURST_FRAMES = 3;
constexpr uint32_t WIFI_DISCOVERY_BURST_INTERVAL_MS = 200;
constexpr uint32_t WIFI_DISCOVERY_INTERVAL_MS = 5000;
// UDP 单包上限 = 最长 V21 帧（5 + 255）。
constexpr size_t WIFI_UDP_MAX_FRAME_BYTES = 260;
// 每次 STA 连接尝试的最长等待（毫秒）；超时进 30 s 退避重连。
constexpr uint32_t WIFI_CONNECT_TIMEOUT_MS = 20000;
// STA 失败后的固定重试间隔：每 30 s 一次，不做指数退避。
constexpr uint32_t WIFI_STA_RETRY_INTERVAL_MS = 30000;
// 连续失败多少次才起兜底 AP（≈2 min：20 s 尝试 + 30 s 退避 ×3）；不要一失败就跳 AP。
constexpr uint8_t WIFI_FALLBACK_AP_AFTER_FAILS = 3;
// 外网探测间隔（毫秒）。探测在独立任务里做，绝不阻塞 loop()。
constexpr uint32_t WIFI_PROBE_INTERVAL_MS = 60000;

// 配置类事件码：模块仍用 LOG_MOD_SYSTEM，param 接在 0x20 码段之后，不新增 TYPE。
// 本方案不做网络凭据持久化：0x32 只表示「配置已应用到 RAM」，0x34 保留不用。
constexpr uint8_t WIFI_LOG_CONFIG_APPLIED =
    JiangLingProtocol::LOG_PARAM_NUM_BASE + 18;
constexpr uint8_t WIFI_LOG_STORAGE_UNUSED =
    JiangLingProtocol::LOG_PARAM_NUM_BASE + 20;
// WIFI_LOG_CONFIG_INVALID(0x33) / WIFI_LOG_LEN_INVALID(0x35) 定义在 Protocol.h，供 Cmd.cpp 共用。
// 0x25：一次 STA 连接尝试超时（配网提交后 20 s 未拿到 IP 也用它）。
constexpr uint8_t WIFI_LOG_CONNECT_TIMEOUT =
    JiangLingProtocol::LOG_PARAM_NUM_BASE + 5;

// 接入模式：STA 连路由器 / AP 自建热点 / APSTA 两者并存。
enum class WifiMode : uint8_t {
  Sta = 0,
  Ap = 1,
  ApSta = 2
};

// 三态（与模式正交）：未连路由器 / 已连路由器但无外网 / 已连路由器且有外网。
// AP 模式下没有路由器，state 恒为 Disconnected，但 isLanReady() 仍为 true。
enum class WifiState : uint8_t {
  Disconnected = 0,
  LanOnly = 1,
  Internet = 2
};

// 连接失败原因；由本类统一识别，AiLink 不重复打印。
enum class WifiFailureReason : uint8_t {
  None = 0,
  NotConfigured,
  SsidNotFound,
  AuthFailed,
  ConnectTimeout,
  ConnectionLost
};

class WifiCommunication {
  public:
    // 状态变化回调：连接、断线、外网有无变化都会回调一次（同一状态不重复回调）。
    using StateCallback = void (*)(WifiState state, void* context);

    WifiCommunication();

    // 启动：上电默认 STA（凭据取 m.h 常量 WIFI_DEFAULT_STA_SSID / PASSWORD）；
    // 传入的凭据只作为 RAM 暂存（当前调用方传 nullptr，即用常量），换网由 0xC8 提交。
    bool begin(const char* ssid, const char* password);

    // ---- 初始化（0xC4~0xC8）：只暂存，不立即换网 ----
    // 0xC4：模式合法性预检；真正生效在 0xC8（0xC8 会再次携带模式）。
    bool stageMode(WifiMode mode);
    // 0xC5 / 0xC6：凭据 PAYLOAD = [ssidLen u8][SSID(ssidLen)][密码(剩余字节)]，
    // 密码可为空（开放网络）；只写 RAM，WiFi 仍跑现有网络，直到 0xC8 才重连。
    bool stageStaCredentials(const uint8_t* payload, uint8_t length);
    bool stageApCredentials(const uint8_t* payload, uint8_t length);
    // 0xC8：应用暂存的模式与凭据并重连；失败返回 false（调用方回 ACK_ST_ERROR）。
    bool commitInit(WifiMode mode);
    // 0xCA：清空 RAM 中的配网数据，回到编译期默认凭据（随后由调用方重启）。
    void factoryReset();

    // 主循环调用：推进 STA 重连/回落、维护三态、收发 TCP 8080 帧；全程非阻塞。
    void update();

    WifiMode mode() const;
    WifiState state() const;
    WifiFailureReason failureReason() const;

    // 局域网通道门禁：已连路由器（LanOnly/Internet）或 AP 热点已开启。
    bool isLanReady() const;
    // AI 云端门禁：必须同时满足「连上路由器」与「外网探测成功」。
    bool hasInternet() const;

    // 给日志/诊断用的只读信息（AP 模式下 localIp 返回 192.168.4.1）。
    String localIp() const;
    String macAddress() const;
    int32_t rssi() const;
    // 当前出口 IPv4 拆成 4 字节（DAT_WIFI_STATUS 用）；无地址时返回 false 并填 0。
    bool localIpBytes(uint8_t out[4]) const;

    // 当前是否有局域网 TCP 客户端；运行期 DAT/LOG 只在回程在线时排队。
    bool hasTcpClient() const;

    // 外网探测结果：probeTask 在另一个核写，这里统一走临界区读写。
    bool internetProbeOk() const;
    uint32_t probeNextMs() const;
    void setInternetProbe(bool ok, uint32_t nextMs);

    void setStateCallback(StateCallback callback, void* context);

    static const char* modeName(WifiMode mode);
    static const char* stateName(WifiState state);
    static const char* failureName(WifiFailureReason reason);

  private:
    static constexpr size_t RX_BUFFER_BYTES = 320;  // ≥ 最长帧 260 B
    static constexpr size_t RX_CHUNK_BYTES = 64;
    static constexpr uint32_t PARTIAL_FRAME_TIMEOUT_MS = 100;
    static constexpr uint32_t FRAME_SEND_INTERVAL_MS = 20;
    static constexpr char PROBE_HOST[] = "api.tenclass.net";

    static void probeTask(void* arg);
    void applyMode(WifiMode next);
    void startSta();
    bool startAp();
    // 连续失败达阈值后起兜底 AP（APSTA：保留 STA 后台重连），STA 连上后由 update 关闭。
    void startFallbackAp();
    void stopNetwork();
    void setState(WifiState next);
    void handleRouterUp(uint32_t nowMs);
    void handleRouterDown(uint32_t nowMs);
    void serveLan(uint32_t nowMs);
    // UDP 8081 命令主通道：非阻塞轮询，收到完整单帧就交给 Cmd::add()。
    void serveCmdUdp();
    // UDP 8082 广播：STA 拿到 IP 后播报本机地址（复用 0x87 帧），解决 App 首连拿不到 IP 的死锁。
    void serveDiscovery(uint32_t nowMs);
    void sendDiscoveryFrame(const IPAddress& target, const uint8_t* frame,
                            size_t length);
    void handleCmdDatagram(const uint8_t* data, size_t length);
    void acceptClient();
    void closeClient(const char* reason);
    void processRxData(const uint8_t* data, size_t length);
    void parseRxBuffer();
    void flushCmdReplies();
    void drainFrames(uint32_t nowMs);
    void dropRx(uint16_t count);

    // 运行期配置：只存 RAM，不写任何持久化存储；上电用编译期默认凭据。
    void applyDefaults(const char* defaultSsid, const char* defaultPassword);
    static bool splitCredentials(const uint8_t* payload, uint8_t length,
                                 String& ssid, String& password);

    WifiMode _mode;
    WifiState _state;
    WifiFailureReason _failure;
    bool _started;
    bool _apActive;
    String _ssid;
    String _password;
    String _apSsid;
    String _apPassword;

    // 连接与重连节奏
    uint32_t _connectStartMs;
    uint32_t _nextRetryMs;
    uint8_t _staFailCount;      // 连续失败次数；≥ WIFI_FALLBACK_AP_AFTER_FAILS 起兜底 AP
    bool _fallbackApActive;     // 兜底 AP 是否由失败路径拉起（成功后自动关）
    bool _attemptSettled;  // 本次连接尝试是否已结算（超时记一次 0x31）

    // 外网探测：probeTask 写，update() 读；读写都走 _probeMux。
    volatile bool _internetOk;
    volatile uint32_t _nextProbeMs;
    mutable portMUX_TYPE _probeMux = portMUX_INITIALIZER_UNLOCKED;

    // 局域网 TCP 通道
    WiFiServer _server;
    // mutable：hasTcpClient() 是 const 查询，但 WiFiClient::connected() 不是 const。
    mutable WiFiClient _client;
    bool _serverStarted;
    // 局域网 UDP 命令通道（8081）：只收 CMD，一包一帧。
    WiFiUDP _cmdUdp;
    bool _cmdUdpStarted;
    uint8_t _cmdUdpBuffer[WIFI_UDP_MAX_FRAME_BYTES];

    // 局域网 IP 广播发现（8082，复用 0x87）：连上先连发 3 帧，之后 5 s 一帧。
    uint32_t _discoNextMs;
    uint8_t _discoBurstLeft;
    uint8_t _discoSeq;      // 广播独立 SEQ 计数器，与 Data::_nextSeq 不共用
    bool _configured;       // 本次运行是否已应用过 App 下发的凭据（0x87.flags.bit0）
    uint8_t _rxBuffer[RX_BUFFER_BYTES];
    uint16_t _rxLength;
    uint32_t _lastRxAtMs;
    uint32_t _lastLogSentAtMs;

    StateCallback _callback;
    void* _callbackContext;
};

extern WifiCommunication Wifi;

#endif  // WIFI_LINK_H
