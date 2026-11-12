/*
 * motion/MotionControl.h —— 差速底盘移动控制（0xEB 两分量 + 旧离散命令）
 *
 * 职责：
 *   1. 持有目标轮速（0.01 r/s）与 PWM，把「百分比」换算成轮速；
 *   2. 流式命令 0xEB 每收到一帧就刷新计时，超过 MOTION_STREAM_TIMEOUT_MS 无新帧
 *      自动停车（超时值是可调常量，见 操控.md §4.2）；
 *   3. 旧离散命令 0xE1~0xE4 仍按 1 s 超时自停，与流式计时互不干扰；
 *   4. stop() 立即清零目标与计时，不等超时（0xE0 / 0xEB 的 0/0）。
 *
 * 边界：
 *   不含协议解析、不组 ACK、不驱动 GPIO；解析层只调用 drive()/discrete()/stop()。
 *   差速合成只有一套公式：左轮 = drive − turn、右轮 = drive + turn（操控.md §3.3）。
 */
#ifndef JIANGLING_MOTION_CONTROL_H
#define JIANGLING_MOTION_CONTROL_H

#include <stdint.h>

class MotionControl {
  public:
    // 底盘换算基准：最大轮速 5.00 r/s（0.01 r/s 单位）、PWM 满量程 255、百分比满量程 100。
    static constexpr int16_t MAX_WHEEL_RPS_HUNDREDTHS = 500;
    static constexpr uint16_t MOTOR_PWM_MAX = 255;
    static constexpr int LEVEL_MAX = 100;

    // 流式命令超时：约 200 ms 一帧，取 1.5 倍帧间隔，单帧抖动不会误停。
    static constexpr uint32_t MOTION_STREAM_TIMEOUT_MS = 300;
    // 旧离散运动命令（0xE1~0xE4）保持原 1 s 失联保护。
    static constexpr uint32_t MOTION_DISCRETE_TIMEOUT_MS = 1000;

    void begin(uint32_t nowMs);

    // 返回 true 表示本轮发生了「超时自动停车」，调用方据此记一条日志。
    bool update(uint32_t nowMs);

    // 0xEB：drive / turn 各 -100~+100，越界夹取；全 0 等价停车。
    void drive(int drivePercent, int turnPercent, uint32_t nowMs);

    // 0xE1~0xE4：level 为 0~100 的百分比，0 等价停车。
    void discrete(uint8_t cmdType, uint8_t level, uint32_t nowMs);

    // 0xE0：立即停、清目标、清计时。
    void stop(uint32_t nowMs);

    bool isActive() const {
      return _mode != MODE_IDLE;
    }
    int16_t leftRpsRaw() const {
      return _leftRpsRaw;
    }
    int16_t rightRpsRaw() const {
      return _rightRpsRaw;
    }
    uint8_t leftPwm() const {
      return _leftPwm;
    }
    uint8_t rightPwm() const {
      return _rightPwm;
    }

  private:
    enum Mode : uint8_t {
      MODE_IDLE = 0,
      MODE_STREAM = 1,
      MODE_DISCRETE = 2
    };

    void applyWheelPercent(int leftPercent, int rightPercent);
    void clearTargets();

    int16_t _leftRpsRaw;
    int16_t _rightRpsRaw;
    uint8_t _leftPwm;
    uint8_t _rightPwm;
    Mode _mode;
    uint32_t _lastCmdAtMs;
};

#endif  // JIANGLING_MOTION_CONTROL_H
