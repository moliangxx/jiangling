/*
 * motion/GimbalControl.cpp —— 云台两轴控制实现，见 操控.md §6。
 */
#include "GimbalControl.h"

namespace {
int clampPercent(int value) {
  if (value > GimbalControl::PERCENT_MAX) {
    return GimbalControl::PERCENT_MAX;
  }
  if (value < -GimbalControl::PERCENT_MAX) {
    return -GimbalControl::PERCENT_MAX;
  }
  return value;
}

int clampInt(int value, int minValue, int maxValue) {
  if (value < minValue) {
    return minValue;
  }
  if (value > maxValue) {
    return maxValue;
  }
  return value;
}
}  // namespace

void GimbalControl::begin(uint32_t nowMs) {
  _pitchDeg = 0.0f;
  _yawDeg = 0.0f;
  _panPercent = 0;
  _tiltPercent = 0;
  _lastUpdateMs = nowMs;
  _lastCmdAtMs = nowMs;
  _moving = false;
  // 当前没有云台硬件；接上舵机后在此置 true 并补 PWM 输出。
  _ready = false;
}

bool GimbalControl::update(uint32_t nowMs) {
  const uint32_t elapsedMs = static_cast<uint32_t>(nowMs - _lastUpdateMs);
  _lastUpdateMs = nowMs;
  if (!_moving) {
    return false;
  }

  // 只有收到新帧的这段时间才积分；超时后停在当前角度，不回中。
  if (static_cast<uint32_t>(nowMs - _lastCmdAtMs) >= GIMBAL_STREAM_TIMEOUT_MS) {
    _moving = false;
    _panPercent = 0;
    _tiltPercent = 0;
    return true;
  }

  const float dt = static_cast<float>(elapsedMs) / 1000.0f;
  _yawDeg += (static_cast<float>(_panPercent) / PERCENT_MAX) * PAN_RATE_MAX_DPS * dt;
  _pitchDeg +=
      (static_cast<float>(_tiltPercent) / PERCENT_MAX) * TILT_RATE_MAX_DPS * dt;
  clampAngles();
  return false;
}

void GimbalControl::move(int panPercent, int tiltPercent, uint32_t nowMs) {
  _panPercent = clampPercent(panPercent);
  _tiltPercent = clampPercent(tiltPercent);
  if (_panPercent == 0 && _tiltPercent == 0) {
    stop(nowMs);
    return;
  }
  _moving = true;
  _lastCmdAtMs = nowMs;
  _lastUpdateMs = nowMs;
}

void GimbalControl::setPitchRaw(uint8_t pitchRaw) {
  _pitchDeg = static_cast<float>(pitchRaw) * TILT_ANGLE_MAX_DEG / 255.0f;
  _tiltPercent = 0;
  _moving = _panPercent != 0;
  clampAngles();
}

void GimbalControl::setYawRaw(uint8_t yawRaw) {
  _yawDeg = static_cast<float>(yawRaw) * PAN_ANGLE_MAX_DEG / 255.0f;
  _panPercent = 0;
  _moving = _tiltPercent != 0;
  clampAngles();
}

void GimbalControl::stop(uint32_t nowMs) {
  _panPercent = 0;
  _tiltPercent = 0;
  _moving = false;
  _lastCmdAtMs = nowMs;
  _lastUpdateMs = nowMs;
}

uint8_t GimbalControl::pitchRaw() const {
  const float scaled = _pitchDeg * 255.0f / TILT_ANGLE_MAX_DEG;
  return static_cast<uint8_t>(clampInt(static_cast<int>(scaled + 0.5f), 0, 255));
}

uint8_t GimbalControl::yawRaw() const {
  const float scaled = _yawDeg * 255.0f / PAN_ANGLE_MAX_DEG;
  return static_cast<uint8_t>(clampInt(static_cast<int>(scaled + 0.5f), 0, 255));
}

void GimbalControl::clampAngles() {
  if (_pitchDeg < 0.0f) {
    _pitchDeg = 0.0f;
  } else if (_pitchDeg > TILT_ANGLE_MAX_DEG) {
    _pitchDeg = TILT_ANGLE_MAX_DEG;
  }
  // 水平轴循环：超过 360° 折回 0°，低于 0° 折回 360°。
  while (_yawDeg >= PAN_ANGLE_MAX_DEG) {
    _yawDeg -= PAN_ANGLE_MAX_DEG;
  }
  while (_yawDeg < 0.0f) {
    _yawDeg += PAN_ANGLE_MAX_DEG;
  }
}
