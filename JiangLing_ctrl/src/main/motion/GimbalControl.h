/*
 * motion/GimbalControl.h —— 云台两轴控制（0xEC 角速度分量 + 0xE5/0xE6 绝对角度）
 *
 * 职责：
 *   1. 接收 0xEC 的 pan / tilt 两轴角速度百分比（-100~+100），按满量程 90°/s
 *      积分成角度，并夹到协议允许范围（水平 0~360°、俯仰 0~180°）；
 *   2. 超过 GIMBAL_STREAM_TIMEOUT_MS 没有新帧即停转，角度保持、不回中；
 *   3. 0xE5 / 0xE6 是位置型绝对角度，直接写入当前角度（0~255 线性映射）。
 *
 * 边界：
 *   不含协议解析、不组 ACK、不驱动舵机 PWM。当前无云台硬件，isReady() 恒为 false，
 *   调用方据此回 ACK_ST_CANNOT(6)，与 0xE5/0xE6 的既有口径一致。
 */
#ifndef JIANGLING_GIMBAL_CONTROL_H
#define JIANGLING_GIMBAL_CONTROL_H

#include <stdint.h>

class GimbalControl {
  public:
    static constexpr int PERCENT_MAX = 100;
    static constexpr float PAN_RATE_MAX_DPS = 90.0f;
    static constexpr float TILT_RATE_MAX_DPS = 90.0f;
    static constexpr float PAN_ANGLE_MAX_DEG = 360.0f;
    static constexpr float TILT_ANGLE_MAX_DEG = 180.0f;
    static constexpr uint32_t GIMBAL_STREAM_TIMEOUT_MS = 300;

    void begin(uint32_t nowMs);

    // 返回 true 表示本轮因超时停转，调用方据此记日志。
    bool update(uint32_t nowMs);

    // 0xEC：pan 正 = 向右，tilt 正 = 向上，各 -100~+100（该轴最大角速度百分比）。
    void move(int panPercent, int tiltPercent, uint32_t nowMs);

    // 0xE5 / 0xE6：0~255 线性映射到 0~180°（俯仰）/ 0~360°（水平）。
    void setPitchRaw(uint8_t pitchRaw);
    void setYawRaw(uint8_t yawRaw);

    // 0xE0：立即停转，角度保持。
    void stop(uint32_t nowMs);

    bool isMoving() const {
      return _moving;
    }
    bool isReady() const {
      return _ready;
    }
    // 当前角度按 0xE5/0xE6 的 0~255 量纲回读，便于复用位置型接口。
    uint8_t pitchRaw() const;
    uint8_t yawRaw() const;
    int panPercent() const {
      return _panPercent;
    }
    int tiltPercent() const {
      return _tiltPercent;
    }

  private:
    void clampAngles();

    float _pitchDeg;
    float _yawDeg;
    int _panPercent;
    int _tiltPercent;
    uint32_t _lastUpdateMs;
    uint32_t _lastCmdAtMs;
    bool _moving;
    bool _ready;
};

#endif  // JIANGLING_GIMBAL_CONTROL_H
