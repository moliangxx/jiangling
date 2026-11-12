/*
 * motion/Motor.cpp —— TB6612FNG 输出层实现，引脚见 Motor.h 顶部。
 *
 * PWM：LEDC 低频模式、20 kHz、10 bit（0~1023）。
 *   20 kHz 高于人耳可听上限，避免电机啸叫；10 bit 对 0~100% 的分辨率足够
 *   （每档约 0.1%）。两路共用 LEDC_TIMER_0（同频同分辨率，各自一个通道）。
 *
 * 用 ESP-IDF 的 driver/ledc.h 而不是 Arduino 的 ledcSetup/ledcAttach，
 * 是为了在 core 2.x / 3.x 两套 API 下都能编译。
 */
#include "Motor.h"

#if MOTOR_OUTPUT_ENABLE

#include <driver/ledc.h>

namespace {

// 接线文档 §2.4/§2.5：两块 TB6612，各驱动一侧；8 条信号线全在右侧排针。
constexpr uint8_t PIN_LEFT_PWM = 1;
constexpr uint8_t PIN_LEFT_IN1 = 2;
constexpr uint8_t PIN_LEFT_IN2 = 42;
constexpr uint8_t PIN_LEFT_STBY = 41;

constexpr uint8_t PIN_RIGHT_PWM = 40;
constexpr uint8_t PIN_RIGHT_IN1 = 39;
constexpr uint8_t PIN_RIGHT_IN2 = 38;
constexpr uint8_t PIN_RIGHT_STBY = 47;

constexpr ledc_mode_t LEDC_MODE = LEDC_LOW_SPEED_MODE;
constexpr ledc_timer_t LEDC_TIMER = LEDC_TIMER_0;
constexpr ledc_channel_t LEFT_CHANNEL = LEDC_CHANNEL_0;
constexpr ledc_channel_t RIGHT_CHANNEL = LEDC_CHANNEL_1;
constexpr uint32_t PWM_FREQ_HZ = 20000;
constexpr ledc_timer_bit_t PWM_RESOLUTION = LEDC_TIMER_10_BIT;
constexpr uint32_t PWM_MAX_DUTY = (1u << 10) - 1;  // 1023

bool s_ready = false;

void writeDuty(ledc_channel_t channel, int percentAbs) {
  const uint32_t duty =
      static_cast<uint32_t>(percentAbs) * PWM_MAX_DUTY / 100u;
  ledc_set_duty(LEDC_MODE, channel, duty);
  ledc_update_duty(LEDC_MODE, channel);
}

bool setupChannel(ledc_channel_t channel, uint8_t pwmPin) {
  ledc_channel_config_t cfg = {};
  cfg.gpio_num = pwmPin;
  cfg.speed_mode = LEDC_MODE;
  cfg.channel = channel;
  cfg.intr_type = LEDC_INTR_DISABLE;
  cfg.timer_sel = LEDC_TIMER;
  cfg.duty = 0;
  cfg.hpoint = 0;
  return ledc_channel_config(&cfg) == ESP_OK;
}

}  // namespace

bool Motor::begin() {
  // 顺序：先把所有输出钉在「不转」的状态，再配置 PWM，最后仍保持 STBY 低。
  // 任何一步失败都不置 s_ready，setPercent() 就不会写电平。
  pinMode(PIN_LEFT_STBY, OUTPUT);
  digitalWrite(PIN_LEFT_STBY, LOW);
  pinMode(PIN_RIGHT_STBY, OUTPUT);
  digitalWrite(PIN_RIGHT_STBY, LOW);

  pinMode(PIN_LEFT_IN1, OUTPUT);
  pinMode(PIN_LEFT_IN2, OUTPUT);
  digitalWrite(PIN_LEFT_IN1, LOW);
  digitalWrite(PIN_LEFT_IN2, LOW);
  pinMode(PIN_RIGHT_IN1, OUTPUT);
  pinMode(PIN_RIGHT_IN2, OUTPUT);
  digitalWrite(PIN_RIGHT_IN1, LOW);
  digitalWrite(PIN_RIGHT_IN2, LOW);

  ledc_timer_config_t timer = {};
  timer.speed_mode = LEDC_MODE;
  timer.duty_resolution = PWM_RESOLUTION;
  timer.timer_num = LEDC_TIMER;
  timer.freq_hz = PWM_FREQ_HZ;
  timer.clk_cfg = LEDC_AUTO_CLK;
  if (ledc_timer_config(&timer) != ESP_OK) {
    Serial.println("[MOTOR] LEDC timer config FAIL; output stays disabled");
    return false;
  }
  if (!setupChannel(LEFT_CHANNEL, PIN_LEFT_PWM) ||
      !setupChannel(RIGHT_CHANNEL, PIN_RIGHT_PWM)) {
    Serial.println("[MOTOR] LEDC channel config FAIL; output stays disabled");
    return false;
  }
  writeDuty(LEFT_CHANNEL, 0);
  writeDuty(RIGHT_CHANNEL, 0);

  s_ready = true;
  Serial.printf("[MOTOR] TB6612 ready: L(PWM=%u IN=%u/%u STBY=%u) "
                "R(PWM=%u IN=%u/%u STBY=%u) %lu Hz / 10 bit, all low\n",
                PIN_LEFT_PWM, PIN_LEFT_IN1, PIN_LEFT_IN2, PIN_LEFT_STBY,
                PIN_RIGHT_PWM, PIN_RIGHT_IN1, PIN_RIGHT_IN2, PIN_RIGHT_STBY,
                static_cast<unsigned long>(PWM_FREQ_HZ));
  return true;
}

void Motor::writeSide(uint8_t pwmPin, uint8_t in1Pin, uint8_t in2Pin,
                      int percent) {
  if (percent > 100) {
    percent = 100;
  } else if (percent < -100) {
    percent = -100;
  }
  const uint8_t stbyPin = (pwmPin == PIN_LEFT_PWM) ? PIN_LEFT_STBY
                                                   : PIN_RIGHT_STBY;
  const ledc_channel_t channel =
      (pwmPin == PIN_LEFT_PWM) ? LEFT_CHANNEL : RIGHT_CHANNEL;

  if (percent == 0) {
    writeDuty(channel, 0);
    digitalWrite(in1Pin, LOW);
    digitalWrite(in2Pin, LOW);
    digitalWrite(stbyPin, LOW);  // 休眠 = 输出高阻，和「停」语义一致
    return;
  }

  // 方向：正 = IN1 高 / IN2 低，负 = IN1 低 / IN2 高。
  digitalWrite(in1Pin, percent > 0 ? HIGH : LOW);
  digitalWrite(in2Pin, percent > 0 ? LOW : HIGH);
  digitalWrite(stbyPin, HIGH);  // 先使能再调速，顺序不能反
  writeDuty(channel, percent > 0 ? percent : -percent);
}

void Motor::setPercent(int leftPercent, int rightPercent) {
  if (!s_ready) {
    return;  // begin() 未完成：绝不写任何电平
  }
  writeSide(PIN_LEFT_PWM, PIN_LEFT_IN1, PIN_LEFT_IN2, leftPercent);
  writeSide(PIN_RIGHT_PWM, PIN_RIGHT_IN1, PIN_RIGHT_IN2, rightPercent);
}

void Motor::stop() {
  if (!s_ready) {
    return;
  }
  writeDuty(LEFT_CHANNEL, 0);
  writeDuty(RIGHT_CHANNEL, 0);
  digitalWrite(PIN_LEFT_IN1, LOW);
  digitalWrite(PIN_LEFT_IN2, LOW);
  digitalWrite(PIN_RIGHT_IN1, LOW);
  digitalWrite(PIN_RIGHT_IN2, LOW);
  digitalWrite(PIN_LEFT_STBY, LOW);
  digitalWrite(PIN_RIGHT_STBY, LOW);
}

bool Motor::ready() {
  return s_ready;
}

#else  // MOTOR_OUTPUT_ENABLE == 0

bool Motor::begin() {
  Serial.println("[MOTOR] output disabled by MOTOR_OUTPUT_ENABLE=0");
  return true;
}

void Motor::setPercent(int, int) {}

void Motor::stop() {}

bool Motor::ready() {
  return false;
}

#endif  // MOTOR_OUTPUT_ENABLE
