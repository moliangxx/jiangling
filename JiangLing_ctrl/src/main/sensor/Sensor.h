/*
 * main/sensor/Sensor.h —— 电池电压检测 + 单侧轮速采样
 *
 * 职责：
 *   1. 电压：Voltage Sensor（R1=3kΩ / R2=750Ω，5:1）输出接 GPIO8（ADC1_CH7），
 *      采样后按分压比还原成电池电压，并做滑动平均滤波；
 *   2. 测速：单路脉冲测速模块输出接 GPIO15，上升沿中断计数，
 *      按固定窗口换算成 r/s（只测左后轮，无方向）。
 *
 * 协作：
 *   main.cpp 调 begin()；RuntimeCmd::update() 每轮调 update() 推进采样与窗口；
 *   RuntimeCmd 用 batteryRaw() 组 DAT_BAT(0x80)、用 wheelRaw() 组 DAT_WHEEL(0x82)。
 *
 * 引脚核对（05-接线文档 §三）：
 *   GPIO8  = ADC1_CH7，WiFi 工作时 ADC2 不可用，所以必须选 ADC1；
 *            它位于左侧排针，与显示/音频线束同侧，远离右侧 8 条电机 PWM；
 *   GPIO15 = 普通 IO，支持边沿中断，非 strapping / USB / JTAG / Flash / PSRAM，
 *            同样在左侧排针，紧邻音频线束。
 */
#ifndef SENSOR_H
#define SENSOR_H

#include <Arduino.h>

// ==================== 编译期总开关 ====================
// 0 = 不接传感器（只跑移动）：begin() 不配置 ADC、不挂中断，update() 空转，
//     GPIO8 / GPIO15 保持复位后的输入态，不读也不中断，浮空不会产生事件。
//     DAT_BAT 固定 6.80 V、DAT_WHEEL 左轮固定 0.00 r/s。
// 1 = 接传感器：按下面的引脚与参数真实采样。
// 也可在 platformio.ini 的 build_flags 里用 -DSENSOR_ENABLE=1 覆盖。
#ifndef SENSOR_ENABLE
#define SENSOR_ENABLE 0
#endif

namespace Sensor {

// 关闭时的固定上报值，与接入传感器前的口径一致。
constexpr uint16_t FALLBACK_BAT_RAW = 680;  // 6.80 V（单位 0.01 V）
constexpr int16_t FALLBACK_WHEEL_RAW = 0;   // 0.00 r/s

// 引脚号改动时同步 05-接线文档与接线图。
constexpr uint8_t PIN_VOLTAGE_ADC = 8;   // Voltage Sensor S → GPIO8（ADC1_CH7）
constexpr uint8_t PIN_SPEED_PULSE = 15;  // 测速模块 OUT → GPIO15

// 电压检测模块分压比：(R1 + R2) / R2 = (3k + 750) / 750 = 5。
// 换算：Vbat(mV) = ADC(mV) × VOLTAGE_DIVIDER_RATIO × VOLTAGE_CAL_GAIN。
constexpr float VOLTAGE_DIVIDER_RATIO = 5.0f;
// 实测校准系数 = 万用表读数 / 本模块读数；未标定前保持 1.0（待实测）。
constexpr float VOLTAGE_CAL_GAIN = 1.0f;
constexpr uint8_t VOLTAGE_FILTER_SAMPLES = 8;       // 滑动平均窗口
constexpr uint32_t VOLTAGE_SAMPLE_PERIOD_MS = 100;  // 采样节拍

// 单侧测速：每转脉冲数取模块码盘实际槽数，当前 20 为默认值（待实测）。
constexpr uint16_t SPEED_PULSES_PER_REV = 20;
constexpr uint32_t SPEED_WINDOW_MS = 1000;  // 固定计数窗口

// 初始化 ADC 与脉冲中断；启动时先采一个电压样本，避免首帧无效。
// SENSOR_ENABLE=0 时只打一行日志并立即返回 false，不碰 ADC / 中断。
bool begin();

// 周期调用：按 VOLTAGE_SAMPLE_PERIOD_MS 采电压，按 SPEED_WINDOW_MS 结算转速。
// SENSOR_ENABLE=0 时为空操作。
void update(uint32_t nowMs);

// 滤波后的电池电压，单位 0.01 V；尚无样本时返回 0xFFFF。
// SENSOR_ENABLE=0 时固定返回 FALLBACK_BAT_RAW（680 → 6.80 V）。
uint16_t batteryRaw();

// 最近一个窗口的转速，单位 0.01 r/s（绝对值，无方向）；
// 首个窗口完成前或脉冲模块未接入时返回 -1（即 DAT_INVALID_I16）。
// SENSOR_ENABLE=0 时固定返回 FALLBACK_WHEEL_RAW（0 → 0.00 r/s）。
int16_t wheelRaw();

// 脉冲中断是否已挂上（用于决定是否上报 DAT_WHEEL）。
// SENSOR_ENABLE=0 时返回 true，仍按固定值上报，保持 App 的帧节奏不变。
bool speedReady();

// 最近一个完整窗口内的脉冲数（诊断用）。
uint32_t windowPulses();

}  // namespace Sensor

#endif  // SENSOR_H
