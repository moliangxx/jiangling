/*
 * ============================================================
 * m.h —— 全局常量配置
 * ============================================================
 * 【新手提示：配置与代码】
 *   这个文件只保存“板子接线和串口设置”等固定值，不在这里执行硬件操作。
 *   #define 是 C/C++ 共用的预处理器宏：编译前会做文本替换，不是普通变量。
 *
 * 【命名规范】
 *   引脚常量：PIN_<GPIO编号>_<元件>_<功能>，例：PIN_38_TFT_CS
 *   串口波特率：SERIAL_BAUD_RATE
 *
 * 【重要：修改引脚时】
 *   修改 GPIO 编号时，也要同步修改 platformio.ini 的对应 TFT_* 值，
 *   并按新编号重新接线。TFT_* 是 TFT_eSPI 库要求的名称。
 *   屏幕的 SCLK 就是 SPI 时钟线，SDA 是主机→屏幕的数据线（MOSI 方向）。
 *
 * 【协议常量】
 *   帧格式、TYPE 布局、帧长表、CRC8 等协议常量不在这里，
 *   唯一真源是 base/Protocol.h。
 * ============================================================
 */
// 头文件保护：防止同一个头文件被多个文件重复包含。
// 这是 C 和 C++ 都常用的预处理器写法，不会创建变量或运行代码。
#ifndef CONFIG_H
#define CONFIG_H

// ============================================================
// 元件 TFT —— 2.4 寸 ST7789 SPI 彩屏
// ============================================================
// 屏上丝印：SCLK / SDA / DC / RESET / CS / BLK，另加 3V3(电源) 和 GND。
// ⚠️ 3V3 必须接 3.3V，不要接 5V。

// 每个宏把易读名字替换成实际 GPIO 编号；PIN_12_TFT_SCLK 本身不是变量。
//
// 【2026-11 引脚重排：按开发板物理排针分两侧】
//   左侧排针：显示 6 线 + 音频 4 线 + 传感器 2 线，全部集中在板子左侧，线束只往左走；
//   右侧排针：两块 TB6612 的 8 条信号线，全部从右侧引出到电机区。
//   屏幕这 6 个 GPIO 正好是 FSPI(SPI2) 的原生 IO，
//   40 MHz 走 IOMUX 不经过 GPIO 矩阵，是整块板上最快的 SPI 走法。
#define PIN_12_TFT_SCLK 12  // GPIO12（FSPICLK）-> 屏幕 SCLK：SPI 时钟线

#define PIN_11_TFT_MOSI 11  // GPIO11（FSPID）-> 屏幕 SDA：主机到屏幕的数据线（MOSI 方向）

#define PIN_13_TFT_DC   13  // GPIO13（FSPIQ 复用为普通 IO）-> 屏幕 DC：数据 / 命令选择

#define PIN_14_TFT_RST  14  // GPIO14（FSPIWP 复用为普通 IO）-> 屏幕 RESET：屏幕复位

#define PIN_10_TFT_CS   10  // GPIO10（FSPICS0）-> 屏幕 CS：片选

#define PIN_9_TFT_BL    9   // GPIO9（FSPIHD 复用为普通 IO）-> 屏幕 BLK：背光控制

// ============================================================
// 元件 SERIAL —— USB 串口日志
// ============================================================
#define SERIAL_BAUD_RATE 115200 // Serial.begin() 和 platformio.ini monitor_speed 必须一致。

// ============================================================
// WiFi 凭据（编译期默认值，不持久化）
// ============================================================
// 上电默认连手机热点（定稿：默认 STA，AP 仅兜底，与《总览》§2.2/§2.5 一致）：
//   手机开热点 jianglingAPP / 31957172yz（WPA2，≥ 8 位），板子作 STA 连入；
//   连不上按 30 s 退避重连，连续 3 次失败才起兜底 AP（192.168.4.1）。
// WIFI_DEFAULT_STA_*：只读编译期默认值，不是持久化——不写 NVS；
//   App 经 0xC5 下发的凭据只覆盖 RAM，断电即忘，重启仍回到这组常量。
// WIFI_AP_*：兜底配网热点，与默认 STA 同名同密，配完立即回 STA。
// 频段限制：ESP32 只支持 2.4 GHz，手机热点不能开 5 GHz。
#define WIFI_DEFAULT_STA_SSID     "jianglingAPP"
#define WIFI_DEFAULT_STA_PASSWORD "31957172yz"
#define WIFI_AP_SSID        "jianglingAPP"
#define WIFI_AP_PASSWORD    "31957172yz"
#define WIFI_AP_CHANNEL     6
#define WIFI_AP_MAX_CLIENTS 4

#endif // CONFIG_H
