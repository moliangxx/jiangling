/*
 * ============================================================
 * Mic.h —— INMP441 麦克风采集（16 kHz / 单声道 / 非阻塞）
 * ============================================================
 * 【硬件】
 *   实物是“功放+麦克风模块”一体板，7 脚丝印 LRCLK/BCLK/SDA/DATA/CLK/
 *   VCC/GND；背面分组把 DATA 标为 MIC、SDA 标为 SPK（与 BCLK/LRCLK 同组）。
 *   所以麦克风数据是丝印 DATA（不是 SDA），对应 GPIO7 的 RX；功放数据
 *   是丝印 SDA，对应 GPIO6 的 TX。两根独立数据脚 + 共用 BCLK/LRCLK，
 *   全双工方案成立。
 *   INMP441 输出 24 bit 数据，装在 32 bit I2S 槽里（左对齐）。
 *   本模块读取 32 bit 原始值后右移 16 位，得到 -32768~32767 的 PCM16。
 *   丝印没有 L/R/MC，按内部固定左声道处理，与 AudioBus 的 mono/LEFT
 *   配置对应。若时钟、供电、数据脚都正常但 read() 一直返回 0 或全 0，
 *   把 AudioBus::MIC_SLOT_MASK 改成 RIGHT 再试（不要同时改 GPIO）。
 *
 * 【接线】与 AudioBus.h 文件头相同：
 *   LRCLK=GPIO5 / BCLK=GPIO4 / DATA=GPIO7（RX，与 SDA=GPIO6 独立），
 *   VCC=DC93512 降压模块的一路 5V（模块支持 3.3~5V），GND 共地。
 *   CLK 默认悬空；若麦克风完全无数据，再按 AudioBus.h 里的步骤尝试 MCLK。
 *
 * 【用法示例（由主程序或其他模块调用，本轮不修改 main.cpp）】
 *
 *   void setup() {
 *     Serial.begin(115200);
 *     if (!Mic::begin()) {          // 失败返回 false，原因已打印到串口
 *       // 不要直接重启，交给上层决定降级或重试
 *     }
 *   }
 *
 *   void loop() {
 *     static int16_t pcm[256];
 *     size_t n = Mic::read(pcm, 256);      // 非阻塞：没有新数据时返回 0
 *     if (n > 0) {
 *       uint16_t rms = Mic::rms();          // 最近一次数据块的均方根
 *       uint16_t peak = Mic::peak();        // 最近一次数据块的绝对峰值
 *       Serial.printf("samples=%u rms=%u peak=%u\n", n, rms, peak);
 *     }
 *     delay(10);                            // 主循环自己的节拍，read() 内部不等待
 *   }
 *
 *   // 不再使用时调用 Mic::end()；与 Speaker 共用总线，两者都 end() 后才真正关闭。
 *
 * 【注意】
 *   单次 read() 最多输出 512 个采样点（8 KB DMA 读取上限），
 *   需要更长音频时请在主循环里连续调用多次，而不是一次传入超大缓冲区。
 * ============================================================
 */
#ifndef AUDIO_MIC_H
#define AUDIO_MIC_H

#include <Arduino.h>

namespace Mic {

// 初始化麦克风并复用 AudioBus 总线；失败返回 false 并打印串口日志。
bool begin();

// 麦克风通路是否就绪；false 时录音请求应回 ACK_ST_CANNOT。
bool isReady();

// 麦克风占用标志：App 语音通话开始置位、结束清位。
// AI 采集前必须检查；为 true 时任何 AI / 唤醒词都不得读取麦克风。
void setBusy(bool busy);
bool isBusy();

// 非阻塞读取：返回本次真正读到的采样点数（0 表示当前没有新数据）。
// out 必须是 int16_t 数组，maxSamples 建议不超过 512。
size_t read(int16_t* out, size_t maxSamples);

// 最近一次成功读取的数据块 RMS（0~32767），用于判断“有没有声音”。
uint16_t rms();

// 最近一次成功读取的数据块绝对峰值（0~32768）。
uint16_t peak();

// 释放麦克风对总线的引用。
void end();

}  // namespace Mic

#endif  // AUDIO_MIC_H
