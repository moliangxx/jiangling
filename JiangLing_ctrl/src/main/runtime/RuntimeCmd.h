/*
 * main/runtime/RuntimeCmd.h —— 运行期命令执行器（唯一入口：WiFi）
 *
 * 职责：
 *   1. 作为 Cmd 的 ActionHandler，执行命令主体（运动 / 云台 / 音频 / 录音 / 信息补拉 / 配网）；
 *   2. 在 ACK 发出之后执行「先回复再动作」的待办（播放测试音、启动录音、补发 DAT_INFO、换网）；
 *   3. 调度运行期 DAT：0x80 电压 5 s、0x82 转速 0.5 s、0x86 WiFi 状态 5 s（含跳变补发）；
 *   4. 对外提供 stopAll()，供 WiFi 链路断开时立即停车。
 *
 * 协作：
 *   Wifi.cpp 收到 UDP 8081 的 CMD 后交给 Cmd::add()，本类只被 Cmd 回调；
 *   组帧 / 去重 / 会话 / ACK 全部在 Cmd 内完成，本类只返回 ACK_ST_* 状态码。
 */
#ifndef RUNTIME_CMD_H
#define RUNTIME_CMD_H

#include <Arduino.h>

#include "../motion/GimbalControl.h"
#include "../motion/MotionControl.h"

class RuntimeCmd {
  public:
    void begin();
    // 主循环调用：运动/云台超时自停 + DAT 周期调度，全程非阻塞。
    void update();
    // ACK 发出后执行待办动作；由 WiFi 的回复发送循环调用。
    void applyPendingActions();
    // 一轮一次性 DAT（0x83~0x85 + 0x90）；握手 ACK 后与 CMD_REQ_INFO 时调用。
    void sendDeviceInfoRound();
    // 任一条链路断开时立即停车：清目标、清两个流式计时，不等超时。
    void stopAll();
    // 板 → App 只能发 DAT_REQ：当前唯一调用点是请求码 2（录音）。
    bool requestRecording();

  private:
    static constexpr uint32_t DAT_SLOW_PERIOD_MS = 5000;   // 电压 / WiFi 状态
    static constexpr uint32_t DAT_WHEEL_PERIOD_MS = 500;   // 转速（快变）
    static constexpr uint32_t DAT_EDGE_MIN_INTERVAL_MS = 100;
    static constexpr uint16_t DAT_BAT_EDGE_RAW = 10;       // 电压 0.10 V
    static constexpr uint16_t DAT_WHEEL_EDGE_RAW = 25;     // 转速 0.25 r/s

    // 0xC8 提交后等待新网络就绪的上限；超时回 [0xC8][5]，连接保持、可重试。
    static constexpr uint32_t INIT_COMMIT_TIMEOUT_MS = 20000;
    // 0xCA 回完 ACK 再重启的缓冲时间，保证 7 B ACK 已写进 TCP。
    static constexpr uint32_t INIT_RESTART_DELAY_MS = 150;

    static constexpr uint8_t FW_MAJOR = 1;
    static constexpr uint8_t FW_MINOR = 10;
    static constexpr uint8_t FW_PATCH = 0;
    static constexpr char MODEL_NAME[] = "JiangLing-AI";
    static constexpr uint8_t BATTERY_CELLS = 2;
    static constexpr uint8_t BATTERY_CHEMISTRY = 0;
    static constexpr uint16_t WHEEL_DIAMETER_MM = 68;
    static constexpr uint8_t MAX_GEAR_PERCENT = 100;

    static uint8_t ActionThunk(void* context, uint8_t seq, uint8_t cmdType,
                               const uint8_t* payload, uint8_t payloadLength);
    uint8_t execute(uint8_t seq, uint8_t cmdType, const uint8_t* payload,
                    uint8_t payloadLength);
    void sendDatIfDue(uint32_t nowMs);
    void sendWifiStatus(uint32_t nowMs);
    void pollInitCommit(uint32_t nowMs);

    MotionControl _motion;
    GimbalControl _gimbal;

    // ACK 之后要做的动作。
    uint8_t _pendingAudioAction;   // 0xFF = 无
    uint8_t _pendingRecordSeconds;
    bool _infoRoundPending;
    uint8_t _pendingDisplayType;
    uint8_t _pendingDisplayValue;
    bool _pendingDisplayValid;

    // DAT 调度：电压与 WiFi 状态各 5 s，就绪后先补一条基线。
    uint32_t _lastBatAtMs;
    uint32_t _lastBatEdgeAtMs;
    bool _batPrimed;
    uint16_t _lastBatRaw;
    uint32_t _lastWifiStatusAtMs;
    bool _wifiStatusPrimed;
    uint8_t _lastWifiMode;
    uint8_t _lastWifiState;

    // DAT_WHEEL：只接左后轮一路测速，右轮固定上报无效值。
    uint32_t _lastWheelAtMs;
    uint32_t _lastWheelEdgeAtMs;
    bool _wheelPrimed;
    int16_t _lastWheelRaw;

    // 换网（0xC8）：ACK 先发，真正 applyMode 放到 update() 里做，
    // 避免在 UDP 解析过程中拆掉回程 TCP、把 ACK 自己写丢。
    bool _initCommitQueued;      // 已受理 0xC8，等 update() 应用
    bool _initCommitSubmitted;   // 收到过 0xC8（0xC9 的 0/3/4 判定用）
    bool _initCommitPending;     // 已应用，等新网络就绪 / 超时
    bool _initCommitDone;        // 初始化完成（0xC9 回 3）
    uint8_t _initCommitMode;     // 0xC8[0] 原样保存
    uint8_t _initCommitSeq;      // 0xC8 的 SEQ，完成回执要原样带回
    uint32_t _initCommitStartMs;
    bool _initResultPending;     // 还有一条 [0xC8][3/5] 没发出去
    uint8_t _initResultStatus;
    uint32_t _restartAtMs;       // 0 = 不重启
};

extern RuntimeCmd Runtime;

#endif  // RUNTIME_CMD_H
