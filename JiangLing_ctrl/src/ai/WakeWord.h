/*
 * ============================================================
 * ai/WakeWord.h —— ESP-SR WakeNet 唤醒词（Hi ESP / 英文）
 * ============================================================
 * 【当前限制：默认关闭，且只有英文】
 *   1. 预编译 ESP_SR 库只启用了 CONFIG_SR_WN_WN9_HIESP（唤醒词 "Hi ESP"），
 *      没有中文 WakeNet 模型，所以中文唤醒词在这个 Arduino 预编译包里不可用；
 *      不要在这里声称支持「你好小智」之类的中文词。
 *   2. esp32-hal-sr.c 内部调用 esp_srmodel_init("model")，需要一个标签为
 *      "model" 的 Flash 分区；本项目用 default_16MB.csv，里面没有该分区。
 *      不开这个分区，sr_start() 会在运行时失败（库编译时也会给出
 *      "Compatible partition must be selected for ESP_SR to work" 警告）。
 *   3. 因此 AI_ENABLE_WAKEWORD 默认 0：不初始化 ESP-SR，由按键
 *      触发会话；打开前必须先改分区表并重烧字库（spiffs 偏移会变化）。
 *
 * 【开启步骤（供后续使用）】
 *   a. 自定义 partitions.csv，增加一行：
 *        model, data, undefined, <offset>, 0x100000
 *      同时保证 spiffs 起点后移，需要重新烧入 SPIFFS 字库；
 *   b. platformio.ini 的 board_build.partitions 指向该 csv；
 *   c. 把 AI_ENABLE_WAKEWORD 改成 1；
 *   d. 串口应看到 WakeNet 载入成功，说 "Hi ESP" 后触发回调。
 *
 * 【音频归属】
 *   WakeNet 的 fill_cb 从 Mic 取数，和 AiLink 上行共用同一 I2S RX。
 *   会话开始前必须 WakeWord::pause()，会话结束再 resume()，否则两方抢数据。
 * ============================================================
 */
#ifndef AI_WAKE_WORD_H
#define AI_WAKE_WORD_H

#include <Arduino.h>

namespace WakeWord {

using DetectedCallback = void (*)();

// 初始化唤醒词检测；未启用/分区缺失/模型载入失败都返回 false 并打印原因。
bool begin();

// 暂停/恢复检测。会话期间必须暂停，避免与上行同时读 Mic。
void pause();
void resume();
void end();

bool isActive();

// 注册唤醒回调（在 ESP-SR 的检测任务里调用，回调内不要做重活）。
void setDetectedCallback(DetectedCallback callback);

}  // namespace WakeWord

#endif  // AI_WAKE_WORD_H
