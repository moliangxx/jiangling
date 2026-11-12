/*
 * main.cpp —— JiangLing AI 程序入口（V1.10 帧协议）
 *
 * 职责：
 *   setup() 只运行一次，完成串口、屏幕、字库、WiFi、音频与录音缓冲初始化；
 *   loop() 每轮先给音频喂一小块数据（非阻塞），再以约 10 ms 间隔轮询
 *   WiFi / RuntimeCmd / 语音 / 屏幕，让 CMD/ACK/LOG 与 DAT 遥测都通过 millis() 调度。
 *
 * 协作：
 *   main/wife/Wifi.h 是唯一通道（UDP 8081 收 CMD、TCP 8080 回 ACK/LOG/DAT）；
 *   main/runtime/RuntimeCmd.h 执行运行期与初始化命令并调度 DAT；
 *   main/Log.h 只负责日志码入队，出队与出口都在 WiFi 模块内完成；
 *   main/screen/TFT.h 只负责把命令码/数值画到屏幕（重绘有节流）；
 *   audio/Speaker.h 只负责把 PCM 送进 I2S 出声（CMD_AUDIO_TEST 也走它），
 *   audio/Mic.h 负责采集，audio/Record.h 负责 CMD_RECORD 的录音与录完自动回放。
 *   上电不主动出声：没有任何开机提示音，需要试听时由 App 发 CMD_AUDIO_TEST。
 */
#include <Arduino.h>
// 双引号通常用于项目自己的头文件；尖括号用于 Arduino/库提供的头文件。
#include "base/m.h"
#include "ai/AiConfig.h"
#include "ai/AiLink.h"
#include "main/audio/Mic.h"
#include "main/audio/Record.h"
#include "main/audio/Speaker.h"
#include "main/runtime/RuntimeCmd.h"
#include "main/sensor/Sensor.h"
#include "main/motion/Motor.h"
#include "main/wife/Wifi.h"
#include "main/wife/Voice.h"
#include "base/util/log/Log.h"
#include "main/screen/TFT.h"

using namespace JiangLingProtocol;

// setup 失败后 Arduino 仍会继续调用 loop；用此标志保持程序在安全空闲状态。
namespace {
bool appReady = false;
}

// Arduino 框架在上电或复位后自动调用 setup() 一次。
void setup() {
  // 启动阶段直接用 UART0 打里程碑日志，烧入后接 COM3 就能看出卡在哪一步。
  // 与 Log 模块的可选串口开关无关，确保黑屏时也有输出。
  Serial.begin(SERIAL_BAUD_RATE);
  Log.begin(SERIAL_BAUD_RATE);
  delay(300);
  Serial.println();
  Serial.println("[BOOT] 1/5 setup begin");
  JL_LOGI(LOG_MOD_SYSTEM, SYS_LOG_BANNER,
           LOG_SERIAL_TEXT("=== JiangLing AI display demo ==="));

  // 屏幕优先：TFT_eSPI 使用 GPIO9~GPIO14（左侧排针相邻 6 条），与音频 GPIO4~7 不重叠。
  Serial.printf("[BOOT] 1/5 TFT pins: SCLK=%d SDA=%d DC=%d RST=%d CS=%d BL=%d\n",
                PIN_12_TFT_SCLK, PIN_11_TFT_MOSI, PIN_13_TFT_DC,
                PIN_14_TFT_RST, PIN_10_TFT_CS, PIN_9_TFT_BL);
  Serial.println("[BOOT] 2/5 TFT init start");
  // 屏幕模块内部会初始化硬件、挂载 SPIFFS 并加载专用中文字库。
  // 行为取舍：屏幕失败只降级为「无显示」，网络照常启动，保证还能连上去看日志。
  if (!TFT.init()) {
    Serial.println("[BOOT] 2/5 TFT init FAIL (SPIFFS/font/pins); continue without screen");
    JL_LOGE(LOG_MOD_SYSTEM, SYS_LOG_BOOT_STOP_TFT,
              LOG_SERIAL_TEXT("TFT init failed; continue without screen, network still starts"));
  } else {
    TFT.showAiHome();
    Serial.println("[BOOT] 2/5 TFT init OK, UI drawn");
  }

  // WiFi 是唯一通道：上电默认作 STA 连手机热点（jianglingAPP，常量凭据、不持久化）；
  // AP 只在连续 3 次退避重连失败后兜底（jianglingAPP / 192.168.4.1）。
  // 凭据不持久化：App 经 UDP 8081 下发的凭据只存 RAM，0xC8 提交后才换网。
  Serial.println("[BOOT] 3/5 WiFi STA start");
  if (!Wifi.begin(nullptr, nullptr)) {
    Serial.println("[BOOT] 3/5 WiFi STA start FAIL; local stop logic still runs");
  }
  // 电机输出层：只把 MotionControl 算好的百分比写到 TB6612（右侧排针的 GPIO1/2/42/41/40/39/38/47）。
  // 放在 WiFi 之后、命令执行器之前：网络没就绪之前不可能有运动命令；
  // 且 begin() 自身把 STBY 拉低、PWM 归零，初始化完成前不会有任何输出。
  Serial.println("[BOOT] 3/5 MOTOR init");
  Motor::begin();

  // 传感器：电压检测（GPIO8 / ADC1_CH7）与左后轮测速（GPIO15 上升沿中断）。
  // 只做采样与上报，不参与运动控制；初始化失败也不阻塞其余功能。
  Serial.println("[BOOT] SENSOR init");
  Sensor::begin();

  // 命令执行器：注册 Cmd 的 ActionHandler，只被 WiFi 通道回调。
  Runtime.begin();
  appReady = true;

  // 音频放在屏幕之后：即使 I2S 初始化失败或卡住，也不会影响已经点亮的 UI。
  // 失败只记录日志并继续运行，不重启、不回退前面的初始化。
  Serial.println("[BOOT] 4/5 AUDIO init start");
  // 喇叭与麦克风共用一个全双工 I2S 总线（AudioBus）：任何一路失败都退掉喇叭引用，
  // 不留半开总线。失败只降级，不重启，也不影响已点亮的 UI 与网络。
  const bool speakerReady = Speaker::begin();
  const bool micReady = speakerReady ? Mic::begin() : false;
  if (!micReady && speakerReady) {
    Speaker::end();
  }
  if (!speakerReady) {
    Serial.println("[BOOT] 4/5 AUDIO init FAIL: code=1 (I2S/功放)");
    JL_LOGE(LOG_MOD_AUDIO, AUDIO_LOG_I2S_FAIL,
              LOG_SERIAL_TEXT("AUDIO init failed: I2S/amplifier"));
  } else if (!micReady) {
    Serial.println("[BOOT] 4/5 AUDIO init FAIL: code=2 (麦克风)");
    JL_LOGE(LOG_MOD_AUDIO, AUDIO_LOG_MIC_FAIL,
              LOG_SERIAL_TEXT("AUDIO init failed: microphone"));
  } else {
    Serial.println("[BOOT] 4/5 AUDIO init OK");
  }

  // 录音缓冲：优先 PSRAM，失败退回内部 RAM 并收紧可录秒数；失败只影响录音。
  Serial.printf("[BOOT] 4/5 PSRAM: size=%u B, free=%u B\n",
                static_cast<unsigned int>(ESP.getPsramSize()),
                static_cast<unsigned int>(ESP.getFreePsram()));
  if (Record::begin()) {
    Serial.printf("[BOOT] 4/5 RECORD buffer OK, max=%u s\n",
                  static_cast<unsigned int>(Record::maxSeconds()));
  } else {
    Serial.println("[BOOT] 4/5 RECORD buffer FAIL (CMD_RECORD will answer CANNOT)");
    JL_LOGE(LOG_MOD_RECORD, REC_LOG_BUFFER_ALLOC_FAIL,
              LOG_SERIAL_TEXT("RECORD buffer allocation failed"));
  }

  // 语音轻量帧通道：独立 UDP 8888，与 ctrl 的 TCP 8080 互不相干。
  // WiFi 尚未就绪时 bind 失败也无妨，Voice::update() 会按 500 ms 重试。
  Serial.println("[BOOT] 5/5 VOICE udp start");
  Voice::begin();

  // 上电刻意不出声：不排队任何提示音/测试音，需要试听由 App 发 CMD_AUDIO_TEST。
  Serial.println("[BOOT] 5/5 ready");
  JL_LOGI(LOG_MOD_SYSTEM, SYS_LOG_READY,
           LOG_SERIAL_TEXT("Display ready. Waiting for WiFi commands."));

  // AI 握手链路：独立任务里连 WiFi → OTA → WSS，不阻塞上面的 TFT/音频。
  // 未填 WiFi 凭据时只打印提示并跳过，其余功能照常运行。
  AiLink::begin();
}

void loop() {
  // 初始化失败后只保持串口可用：
  // 此时 UDP socket、I2S 可能都还没建好，任何 update() 都可能拿到空句柄。
  if (!appReady) {
    delay(50);
    return;
  }

  // Arduino 框架会不断重复调用 loop()，适合处理持续到来的事件。
  // 录音/回放状态机每轮只推进一小块，全程非阻塞。
  Record::update();

  // 命令执行器：运动/云台超时自停 + 0xC8 换网推进 + DAT 调度。
  Runtime.update();

  // WiFi 主通道：先收 UDP 8081 命令，再用 TCP 8080 回 ACK/LOG/DAT。
  Wifi.update();

  // 语音通道：UDP 8888 收包、20 ms 读麦泵、抖动缓冲播放与会话状态机。
  Voice::update();

  // 屏幕：按 250 ms 节流把最新命令刷到命令区，避免每条命令都整块重绘。
  TFT.update();

  // 稍作等待，避免循环无间隔运行而占满 CPU。
  delay(10);
}
