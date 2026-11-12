/*
 * ============================================================
 * Speaker.h —— MAX98357A 播放（16 kHz / 16 bit / 单声道 / 非阻塞）
 * ============================================================
 * 【硬件】
 *   实物是“功放+麦克风模块”一体板上的 MAX98357A。背面丝印把
 *   LRCLK/BCLK/SDA 归为 SPK 组，SDA 就是功放数据输入（GPIO6，TX）；
 *   麦克风数据是另一根 DATA（GPIO7，RX）。两根数据脚独立，所以功放只用
 *   TX 方向、与麦克风共用 BCLK/LRCLK。
 *   音量档位由模块上的 GAIN 引脚/电阻决定。7 脚丝印只有
 *   LRCLK/BCLK/SDA/DATA/CLK/VCC/GND，没有引出 GAIN 和 SD_MODE，
 *   因此硬件增益固定；软件侧用 setVolume() 做整体数字增益（0~100%），
 *   100% 只是回到原始采样幅度，真正的响度天花板仍由硬件 GAIN 决定。
 *   若板上另留了 GAIN 焊盘，可按 MAX98357A 手册改电阻/电平选 5/9/12/15 dB；
 *   SD_MODE 决定输出声道与开关，本固件 mono 会把同一份数据复制到两个槽，
 *   所以左/右/(L+R)/2 任一固定档位都能出声。
 *   喇叭通过板上的 JST 座接入，只能从座子接喇叭，不要往功放输出端反接。
 *
 * 【接线】与 AudioBus.h 文件头相同：
 *   LRCLK=GPIO5 / BCLK=GPIO4 / SDA=GPIO6（TX），
 *   VCC 走 DC93512 降压模块的一路 5V（模块支持 3.3~5V），GND 与 ESP32 共地。
 *
 * 【用法示例（本轮不修改 main.cpp）】
 *
 *   void setup() {
 *     Serial.begin(115200);
 *     if (!Speaker::begin()) {          // 失败返回 false，原因已打印到串口
 *       // 由上层决定降级，不要直接重启
 *     }
 *     Speaker::playTestTone(1000, 200);  // 1 kHz、200 ms 的测试音
 *   }
 *
 *   void loop() {
 *     // 流式播放：把一段 PCM16 放进 DMA，返回本次真正排入的采样数。
 *     // 返回 0 表示 DMA 已满，下一次循环再调用即可，不需要 delay 等待。
 *     size_t queued = Speaker::play(pcmBuffer, frames);
 *     (void)queued;
 *   }
 *
 *   // 不再使用时调用 Speaker::end()。
 *
 * 【非阻塞说明】
 *   play() / playTestTone() 内部的 i2s_channel_write 使用 0 tick 超时：
 *   只把当前 DMA 能容纳的部分送进去，立即返回，不会卡住主循环。
 *   I2S TX 还有一把 0 tick 互斥锁：AI 播报与通话同时写时，拿不到锁的一方
 *   本次返回 0（通话优先，由 Voice 下一轮补喂）。
 *   playTestTone() 一次最多排队 200 ms 音频（上限与协议文档一致），
 *   DMA 满时返回 0 并打印一次日志。
 * ============================================================
 */
#ifndef AUDIO_SPEAKER_H
#define AUDIO_SPEAKER_H

#include <Arduino.h>

namespace Speaker {

// 初始化喇叭并复用 AudioBus 总线；失败返回 false 并打印串口日志。
bool begin();

// 喇叭通路是否就绪（I2S TX 已创建）；false 时 play() 一律返回 0。
bool isReady();

// 设置软件音量百分比 0~100：0 静音（仍正常消费数据），100 为原始幅度。
// 超范围值会被夹到 0~100；提示音、测试音、录音回放都走同一套增益。
void setVolume(uint8_t percent);

// 当前软件音量百分比（默认 100）。
uint8_t volume();

// 非阻塞播放一段 PCM16（单声道、16 kHz）。
// 返回本次真正排入 DMA 的采样点数；0 表示 DMA 已满或未初始化。
size_t play(const int16_t* samples, size_t sampleCount);

// 非阻塞播放一段正弦测试音（默认 1 kHz / 200 ms，最长 200 ms；更长的值被夹到 200）。
// 返回本次排入 DMA 的采样点数。
size_t playTestTone(uint16_t frequencyHz = 1000, uint16_t durationMs = 200);

// 释放喇叭对总线的引用。
void end();

}  // namespace Speaker

#endif  // AUDIO_SPEAKER_H
