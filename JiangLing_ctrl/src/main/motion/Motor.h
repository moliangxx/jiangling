/*
 * motion/Motor.h —— 电机输出层（TB6612FNG ×2，左右分侧）
 *
 * 引脚按《05-接线文档.md》§2.4/§2.5，全部落在开发板**右侧排针**，便于向右侧电机区引线：
 *   左轮：PWM = GPIO1，IN1 = GPIO2，IN2 = GPIO42，STBY = GPIO41
 *   右轮：PWM = GPIO40，IN1 = GPIO39，IN2 = GPIO38，STBY = GPIO47
 * 其中 GPIO39~42 复用为 JTAG：占用后只失去**外接 JTAG 探针**，ESP32-S3 内置 USB-JTAG
 * 仍可用，UART0（GPIO43/44）串口日志也保留。
 *
 * 分工：
 *   MotionControl 只算目标（轮速 / PWM 百分比），本类只负责把百分比写进硬件；
 *   不做任何差速运算、不参与 300 ms 自停判定。
 *
 * 上电安全：
 *   begin() 先把两路 PWM 占空比设为 0、方向脚拉低、STBY 拉低（TB6612 整板休眠、输出高阻），
 *   初始化完成后才允许 setPercent() 写电平；stop() 同时把 PWM 与 STBY 归零。
 *
 * 可关：
 *   编译期开关 MOTOR_OUTPUT_ENABLE（默认 1）。不接电机做桌面调试时，
 *   在 platformio.ini 的 build_flags 里加 -DMOTOR_OUTPUT_ENABLE=0，本类全部退化为空操作。
 */
#ifndef MAIN_MOTION_MOTOR_H
#define MAIN_MOTION_MOTOR_H

#include <Arduino.h>

#ifndef MOTOR_OUTPUT_ENABLE
#define MOTOR_OUTPUT_ENABLE 1
#endif

class Motor {
  public:
    // 初始化引脚与 LEDC：完成前不会有任何输出。失败返回 false（不阻塞其它模块）。
    static bool begin();
    // 百分比输出：-100~+100，正数 = 车头方向；0 = 停（PWM 0、方向低、STBY 低）。
    static void setPercent(int leftPercent, int rightPercent);
    // 立即停止：PWM=0、方向脚低、STBY 低。
    static void stop();
    static bool ready();

  private:
    static void writeSide(uint8_t pwmPin, uint8_t in1Pin, uint8_t in2Pin, int percent);
};

#endif  // MAIN_MOTION_MOTOR_H
