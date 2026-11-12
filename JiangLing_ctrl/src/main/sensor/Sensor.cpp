/*
 * main/sensor/Sensor.cpp —— 电压采样与单侧测速实现
 *
 * 电压：analogReadMilliVolts() 已带 eFuse 校准，返回的是引脚毫伏；
 *   再乘分压比 5 得到电池毫伏，除以 10 得到 DAT_BAT 的 0.01 V 单位。
 *   例：引脚 1480 mV → 7400 mV → 740 → 上报 7.40 V。
 *
 * 测速：ISR 只做自增，主循环在窗口结束时一次性取走计数并清零；
 *   窗口用实际经过的毫秒数（而不是固定 1000）参与计算，避免 loop 抖动带来偏差。
 *   raw(0.01 r/s) = pulses × 100 × 1000 / (每转脉冲数 × 窗口毫秒)。
 *   例：20 脉冲/转、1000 ms 窗口、100 个脉冲 → 500 → 5.00 r/s。
 */
#include "Sensor.h"

#if !SENSOR_ENABLE

// 传感器关闭：不配置 ADC、不挂中断、不读浮空引脚，只保留固定值上报。
bool Sensor::begin() {
  Serial.println("[SENSOR] disabled (SENSOR_ENABLE=0): no ADC, no pulse IRQ");
  return false;
}

void Sensor::update(uint32_t) {}

uint16_t Sensor::batteryRaw() {
  return FALLBACK_BAT_RAW;
}

int16_t Sensor::wheelRaw() {
  return FALLBACK_WHEEL_RAW;
}

bool Sensor::speedReady() {
  return true;
}

uint32_t Sensor::windowPulses() {
  return 0;
}

#else

namespace {

constexpr uint16_t RAW_INVALID_U16 = 0xFFFF;
constexpr int16_t RAW_INVALID_I16 = -1;

volatile uint32_t s_pulseCount = 0;  // ISR 自增，窗口结束时取走
uint32_t s_windowPulses = 0;         // 最近一个完整窗口的计数
uint32_t s_windowStartMs = 0;
int16_t s_lastWheelRaw = RAW_INVALID_I16;
bool s_speedReady = false;

uint16_t s_mvSamples[Sensor::VOLTAGE_FILTER_SAMPLES] = {0};
uint8_t s_mvCount = 0;
uint8_t s_mvNext = 0;
uint32_t s_lastVoltageSampleMs = 0;
bool s_voltagePrimed = false;

void IRAM_ATTR onSpeedPulse() {
  ++s_pulseCount;
}

void sampleVoltage(uint32_t nowMs) {
  uint32_t mv = analogReadMilliVolts(Sensor::PIN_VOLTAGE_ADC);
  if (mv > RAW_INVALID_U16) {
    mv = RAW_INVALID_U16;
  }
  s_mvSamples[s_mvNext] = static_cast<uint16_t>(mv);
  s_mvNext = static_cast<uint8_t>((s_mvNext + 1) % Sensor::VOLTAGE_FILTER_SAMPLES);
  if (s_mvCount < Sensor::VOLTAGE_FILTER_SAMPLES) {
    ++s_mvCount;
  }
  s_lastVoltageSampleMs = nowMs;
  s_voltagePrimed = true;
}

int16_t convertWheelRaw(uint32_t pulses, uint32_t windowMs) {
  if (windowMs == 0 || Sensor::SPEED_PULSES_PER_REV == 0) {
    return RAW_INVALID_I16;
  }
  // pulses × 100（0.01 r/s）× 1000 ms ÷（每转脉冲数 × 窗口 ms）。
  const uint32_t raw = pulses * 100UL * 1000UL /
                       (static_cast<uint32_t>(Sensor::SPEED_PULSES_PER_REV) * windowMs);
  return raw > 32767UL ? 32767 : static_cast<int16_t>(raw);
}

}  // namespace

bool Sensor::begin() {
  // ADC 满量程约 3.1 V（11 dB 衰减）；8.4 V 电池经 5:1 分压后最高 1.68 V，余量充足。
  analogSetPinAttenuation(PIN_VOLTAGE_ADC, ADC_11db);

  // 模块输出为推挽时内部上拉无影响；VCC 必须接 3.3V，OUT 才不会超过 ADC/GPIO 量程。
  pinMode(PIN_SPEED_PULSE, INPUT_PULLUP);

  const uint32_t nowMs = millis();
  sampleVoltage(nowMs);

  attachInterrupt(digitalPinToInterrupt(PIN_SPEED_PULSE), onSpeedPulse, RISING);
  s_pulseCount = 0;
  s_windowPulses = 0;
  s_windowStartMs = nowMs;
  s_lastWheelRaw = RAW_INVALID_I16;
  s_speedReady = true;

  Serial.printf("[SENSOR] voltage ADC=GPIO%u (1:%.0f)  speed pulse=GPIO%u "
                "ppr=%u window=%lu ms\n",
                static_cast<unsigned int>(PIN_VOLTAGE_ADC),
                static_cast<double>(VOLTAGE_DIVIDER_RATIO),
                static_cast<unsigned int>(PIN_SPEED_PULSE),
                static_cast<unsigned int>(SPEED_PULSES_PER_REV),
                static_cast<unsigned long>(SPEED_WINDOW_MS));
  return true;
}

void Sensor::update(uint32_t nowMs) {
  if (!s_voltagePrimed ||
      static_cast<uint32_t>(nowMs - s_lastVoltageSampleMs) >= VOLTAGE_SAMPLE_PERIOD_MS) {
    sampleVoltage(nowMs);
  }

  const uint32_t elapsed = static_cast<uint32_t>(nowMs - s_windowStartMs);
  if (elapsed >= SPEED_WINDOW_MS) {
    // 取走并清零计数时要屏蔽中断，避免丢掉「取走之后、清零之前」到达的脉冲。
    noInterrupts();
    const uint32_t pulses = s_pulseCount;
    s_pulseCount = 0;
    interrupts();

    s_windowPulses = pulses;
    s_lastWheelRaw = convertWheelRaw(pulses, elapsed);
    s_windowStartMs = nowMs;
  }
}

uint16_t Sensor::batteryRaw() {
  if (s_mvCount == 0) {
    return RAW_INVALID_U16;
  }
  uint32_t sum = 0;
  for (uint8_t i = 0; i < s_mvCount; ++i) {
    sum += s_mvSamples[i];
  }
  const uint32_t avgMv = sum / s_mvCount;
  const uint32_t vbatMv =
      static_cast<uint32_t>(static_cast<float>(avgMv) * VOLTAGE_DIVIDER_RATIO *
                                VOLTAGE_CAL_GAIN +
                            0.5f);
  // 0.01 V 单位；0xFFFF 保留给「无效」。
  const uint32_t raw = (vbatMv + 5) / 10;
  return raw >= RAW_INVALID_U16 ? static_cast<uint16_t>(RAW_INVALID_U16 - 1)
                                : static_cast<uint16_t>(raw);
}

int16_t Sensor::wheelRaw() {
  return s_lastWheelRaw;
}

bool Sensor::speedReady() {
  return s_speedReady;
}

uint32_t Sensor::windowPulses() {
  return s_windowPulses;
}

#endif  // SENSOR_ENABLE
