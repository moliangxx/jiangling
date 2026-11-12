/*
 * ============================================================
 * AudioBus.h —— 麦克风 / 喇叭共用的 I2S 总线（audio 内部模块）
 * ============================================================
 * 【硬件前提：收放一体模块（已对照实物照片）】
 *   实物丝印是“功放+麦克风模块”，7 脚从左到右：
 *   LRCLK / BCLK / SDA / DATA / CLK / VCC / GND，板面另有 VCC3.3~5。
 *   模块背面把引脚分成三组：LRCLK/BCLK/SDA 标 SPK，DATA 标 MIC，
 *   VCC/GND 标 PWR。因此：
 *     - SDA  = 功放数据输入（MAX98357A DIN）→ GPIO6，ESP32 的 TX
 *     - DATA = 麦克风数据输出（INMP441 SD） → GPIO7，ESP32 的 RX
 *   两根独立数据脚 + 共用 BCLK/LRCLK，所以单口全双工方案成立。
 *   判定依据是背面丝印分组，不是仅凭名字猜。若换批次模块把两根数据脚
 *   对调，实测方法：两脚都先当 RX 读一轮，哪一脚 RMS 随声音起伏就是
 *   麦克风数据脚；另一脚再用 Speaker 播测试音，能听到就是功放输入脚。
 *
 * 【为什么只用一个 I2S 口】
 *   当前 Arduino 核心为 ESP32 Arduino 3.3.x（ESP-IDF 5.x），
 *   driver/i2s_std.h 的 i2s_new_channel() 允许同时传出 TX/RX 两个句柄，
 *   即同一 I2S 控制器的全双工模式：RX 自动作为从机、与 TX 共用 BCLK/WS。
 *   一体模块的 BCLK/LRCLK 本来就是麦和功放共用，因此优先用一个口：
 *     - 省下一个 I2S 控制器给后续需求；
 *     - 麦和喇叭时钟天然同步，不需要额外对齐。
 *   代价：TX/RX 必须使用相同的采样率和槽宽（这里都是 16 kHz、32 bit 槽），
 *         且 TX/RX 需要同时 start/stop，不能单独关闭其中一个。
 *   假设：如果将来换成只引出一组数据脚、靠分时复用的一体模块，全双工
 *         就不再成立，必须改成半双工：录音前关 TX、放音前关 RX，且采样率
 *         和槽宽只能取两者交集。当前代码按本模块的两组独立数据脚实现。
 *
 * 【接线示例（引脚以 index/硬件配置/01-硬件配置文档.md 第八节为准）】
 *   功放+麦克风一体模块：
 *     模块 VCC        -> DC93512 降压模块的一路 5V（不要从 ESP32 板载 5V 取）
 *     模块 GND        -> GND（与 ESP32、降压模块共地）
 *     模块 LRCLK      -> GPIO5    I2S 字时钟（麦/功放共用）
 *     模块 BCLK       -> GPIO4    I2S 位时钟（麦/功放共用）
 *     模块 SDA        -> GPIO6    功放数据输入（TX）
 *     模块 DATA       -> GPIO7    麦克风数据输出（RX）
 *   GPIO4~GPIO7 这 4 条信号在左侧排针相邻，正好紧挨屏幕线束，走线只往左侧。
 *     模块 CLK        -> 默认悬空（可能是 MCLK，排查见下）
 *     模块 JST 喇叭座 -> 小喇叭经 JST 座接入，不要往功放输出端反接
 *   供电：模块标称 3.3~5V。推荐 5V 给功放，输出功率和音量更足；3.3V 更接近
 *   IO 电平、底噪通常更低，但功放输出会明显变小。I2S/时钟始终是 3.3V 电平，
 *   VCC 只决定功放供电。必须从 DC93512 四路降压取电，避免功放瞬态电流
 *   拉低开发板 5V。四路电位器要先万用表调准再接负载。
 *
 *   CLK：丝印有 CLK，而旧文档写“悬空”。它可能是部分数字麦克风需要的
 *   MCLK（256×fs = 4.096 MHz），也可能只是预留/NC。当前默认不输出 MCLK
 *   （PIN_I2S_MCLK = GPIO_NUM_NC），因为引脚表没有空闲 IO。若接上后麦克风
 *   完全无数据：①先确认 DATA/SDA 没接反、VCC/GND 正常；②再把信号脚都当
 *   RX 试读判断哪根是麦克风；③最后才尝试把 CLK 当 MCLK——把
 *   PIN_I2S_MCLK 改成 GPIO43（会占用 UART 日志，需改用网络 LOG 帧调试），
 *   mclk_multiple=256 会自动输出 4.096 MHz。
 *
 *   声道选择：丝印没有 L/R 或 MC 脚，维持“内部固定某一槽位”的假设。
 *   当前按左声道读取（MIC_SLOT_MASK=LEFT）。若时钟、数据脚、供电都正常，
 *   但 read() 一直返回 0 或数据全 0，把 MIC_SLOT_MASK 改成
 *   I2S_STD_SLOT_RIGHT 再试；不要同时改 GPIO，以免引入新变量。
 *
 * 【引脚冲突核对】
 *   - 与 TFT（9~14）、电机（1/2/38~42/47）、PSRAM（35~37）、USB（19/20）、
 *     strapping（0/3/45/46）、UART0（43/44）均不冲突。
 *   - GPIO4~7 本身是 ADC1 引脚与 TOUCH，本车按普通数字 IO 用于 I2S，ADC 功能不用。
 *   - 本方案不再占用 GPIO39~42（JTAG），JTAG 随之可用。
 *
 * 【调用方式（本文件只提供总线，不作为对外接口）】
 *   Mic::begin() / Speaker::begin() 内部会各自调用 AudioBus::begin()，
 *   采用引用计数；两个模块都 end() 之后才真正关闭并删除 I2S 通道。
 * ============================================================
 */
#ifndef AUDIO_BUS_H
#define AUDIO_BUS_H

#include <Arduino.h>
#include "driver/i2s_std.h"

namespace AudioBus {

// 采样率统一为 16 kHz：麦克风识别与喇叭播放都用这一档，省去重采样。
constexpr uint32_t SAMPLE_RATE_HZ = 16000;

// 引脚编号必须与 index/硬件配置/01-硬件配置文档.md 第八节的音频部分保持一致。
constexpr gpio_num_t PIN_I2S_BCLK = GPIO_NUM_4;    // 麦/喇叭共用位时钟
constexpr gpio_num_t PIN_I2S_LRCLK = GPIO_NUM_5;   // 麦/喇叭共用字时钟
constexpr gpio_num_t PIN_I2S_DIN = GPIO_NUM_7;     // 一体模块 DATA(MIC)
constexpr gpio_num_t PIN_I2S_DOUT = GPIO_NUM_6;    // 一体模块 SDA（功放数据）

// 麦克风声道：丝印没有 L/R/MC，按内部固定左声道处理。
// 若实测左槽全 0、只有右槽有数据，把这里改成 I2S_STD_SLOT_RIGHT。
constexpr i2s_std_slot_mask_t MIC_SLOT_MASK = I2S_STD_SLOT_LEFT;

// CLK 脚可选 MCLK：默认 NC = 不输出（CLK 悬空）。
// 若确认模块的 CLK 是 MCLK 输入，改成实际 GPIO；临时排查可用 GPIO43
// （代价是失去 UART 日志）。频率 = 16 kHz × 256 = 4.096 MHz。
constexpr gpio_num_t PIN_I2S_MCLK = GPIO_NUM_NC;
constexpr uint32_t MCLK_FREQ_HZ = SAMPLE_RATE_HZ * 256;

// 创建（或复用）全双工 I2S 通道；可重复调用，内部按引用计数管理。
bool begin();

// 释放一次引用；计数归零时停止并删除 I2S 通道。
void end();

// 总线是否已经创建并启用。
bool isReady();

// 供 Mic / Speaker 使用的通道句柄；未初始化时返回 nullptr。
i2s_chan_handle_t txHandle();
i2s_chan_handle_t rxHandle();

}  // namespace AudioBus

#endif  // AUDIO_BUS_H
