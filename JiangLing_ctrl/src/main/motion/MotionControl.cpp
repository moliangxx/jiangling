/*
 * motion/MotionControl.cpp —— 差速移动控制的实现，见 操控.md §3~§5。
 */
#include "MotionControl.h"

#include "Motor.h"
#include "../../base/Protocol.h"

using namespace JiangLingProtocol;

namespace {
int clampPercent(int value) {
  if (value > MotionControl::LEVEL_MAX) {
    return MotionControl::LEVEL_MAX;
  }
  if (value < -MotionControl::LEVEL_MAX) {
    return -MotionControl::LEVEL_MAX;
  }
  return value;
}
}  // namespace

void MotionControl::begin(uint32_t nowMs) {
  clearTargets();
  _mode = MODE_IDLE;
  _lastCmdAtMs = nowMs;
}

bool MotionControl::update(uint32_t nowMs) {
  if (_mode == MODE_IDLE) {
    return false;
  }
  const uint32_t timeoutMs = _mode == MODE_STREAM ? MOTION_STREAM_TIMEOUT_MS
                                                  : MOTION_DISCRETE_TIMEOUT_MS;
  if (static_cast<uint32_t>(nowMs - _lastCmdAtMs) < timeoutMs) {
    return false;
  }
  stop(nowMs);
  return true;
}

void MotionControl::drive(int drivePercent, int turnPercent, uint32_t nowMs) {
  // 越界夹取后按唯一一套差速公式合成，不报错、不中断推送流。
  const int drive = clampPercent(drivePercent);
  const int turn = clampPercent(turnPercent);
  if (drive == 0 && turn == 0) {
    stop(nowMs);
    return;
  }
  applyWheelPercent(clampPercent(drive - turn), clampPercent(drive + turn));
  _mode = MODE_STREAM;
  _lastCmdAtMs = nowMs;
}

void MotionControl::discrete(uint8_t cmdType, uint8_t level, uint32_t nowMs) {
  if (level == 0) {
    stop(nowMs);
    return;
  }
  const int percent = level > LEVEL_MAX ? LEVEL_MAX : static_cast<int>(level);
  switch (cmdType) {
    case CMD_FORWARD:
      applyWheelPercent(percent, percent);
      break;
    case CMD_BACK:
      applyWheelPercent(-percent, -percent);
      break;
    case CMD_LEFT:
      applyWheelPercent(-percent, percent);
      break;
    case CMD_RIGHT:
      applyWheelPercent(percent, -percent);
      break;
    default:
      return;
  }
  _mode = MODE_DISCRETE;
  _lastCmdAtMs = nowMs;
}

void MotionControl::stop(uint32_t nowMs) {
  clearTargets();
  _mode = MODE_IDLE;
  _lastCmdAtMs = nowMs;
}

void MotionControl::applyWheelPercent(int leftPercent, int rightPercent) {
  const int left = clampPercent(leftPercent);
  const int right = clampPercent(rightPercent);
  // 百分比 → 0.01 r/s；方向由符号保留。
  _leftRpsRaw = static_cast<int16_t>(
      (static_cast<int32_t>(left) * MAX_WHEEL_RPS_HUNDREDTHS) / LEVEL_MAX);
  _rightRpsRaw = static_cast<int16_t>(
      (static_cast<int32_t>(right) * MAX_WHEEL_RPS_HUNDREDTHS) / LEVEL_MAX);
  _leftPwm = static_cast<uint8_t>((static_cast<uint16_t>(left < 0 ? -left : left) *
                                   MOTOR_PWM_MAX) /
                                  LEVEL_MAX);
  _rightPwm = static_cast<uint8_t>((static_cast<uint16_t>(right < 0 ? -right : right) *
                                    MOTOR_PWM_MAX) /
                                   LEVEL_MAX);
  // 输出层只认带符号的百分比：正负交给 Motor 决定方向脚，PWM 只管幅值。
  Motor::setPercent(left, right);
}

void MotionControl::clearTargets() {
  _leftRpsRaw = 0;
  _rightRpsRaw = 0;
  _leftPwm = 0;
  _rightPwm = 0;
  // 300 ms 自停、0xE0 急停、discrete/drive 的 0 值都走这里：输出层必须同步归零。
  Motor::stop();
}
