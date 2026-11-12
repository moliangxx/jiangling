/*
 * AudioBus.cpp —— 全双工 I2S 总线的创建、启停与释放。
 *
 * 硬件前提：麦克风与 MAX98357A 是同一块一体模块，但对外提供
 * DATA(MIC) 与 SDA 两组独立数据脚，因此 TX/RX 可以共用 BCLK/LRCLK
 * 组成全双工；若换成只有一组数据脚的分时复用模块，需要改半双工。
 *
 * 帧格式（同一控制器、共用 BCLK/LRCLK）：
 *   - 采样率 16 kHz，每声道 32 bit 槽，BCLK = 16000 × 2 × 32 = 1.024 MHz；
 *   - RX：32 bit 数据宽度，读取 INMP441 的 24 bit（左对齐装在 32 bit 槽），
 *         由 Mic 右移 16 位得到高 16 bit；
 *   - TX：16 bit 数据宽度、32 bit 槽宽（左对齐），直接送 MAX98357A；
 *   - 两路都是 mono / 左槽；mono 模式下 TX 会把同一份数据复制到两个槽。
 *
 * 非阻塞原则：
 *   - begin() 只做一次性配置，失败时返回 false 并打印日志，不重启；
 *   - 读写函数由 Mic / Speaker 传入 0 tick 超时，不会在 DMA 上长时间等待。
 */
#include "AudioBus.h"

#include <Arduino.h>
#include "esp_err.h"

namespace {

int s_refCount = 0;
i2s_chan_handle_t s_txHandle = nullptr;
i2s_chan_handle_t s_rxHandle = nullptr;

void logError(const char* step, esp_err_t err) {
  Serial.printf("[AudioBus] %s failed: %s (%d)\n", step, esp_err_to_name(err),
                static_cast<int>(err));
}

// 停止并删除两个通道。未初始化的通道也能安全删除；错误不向上抛出。
void destroyPort() {
  if (s_txHandle != nullptr) {
    i2s_channel_disable(s_txHandle);
    i2s_del_channel(s_txHandle);
    s_txHandle = nullptr;
  }
  if (s_rxHandle != nullptr) {
    i2s_channel_disable(s_rxHandle);
    i2s_del_channel(s_rxHandle);
    s_rxHandle = nullptr;
  }
}

bool createPort() {
  // 两个非空句柄 = 同一控制器全双工；RX 会被驱动自动设为从机。
  i2s_chan_config_t chanConfig = I2S_CHANNEL_DEFAULT_CONFIG(I2S_NUM_AUTO, I2S_ROLE_MASTER);
  chanConfig.dma_desc_num = 4;   // 每方向 4 个 DMA 描述符
  chanConfig.dma_frame_num = 512;  // 每个描述符 512 帧，约 32 ms @16 kHz
  chanConfig.auto_clear = true;  // TX 没有数据时自动发 0，避免喇叭残留杂音

  esp_err_t err = i2s_new_channel(&chanConfig, &s_txHandle, &s_rxHandle);
  if (err != ESP_OK) {
    s_txHandle = nullptr;
    s_rxHandle = nullptr;
    logError("i2s_new_channel", err);
    return false;
  }

  // ---------------- TX（MAX98357A）----------------
  i2s_std_config_t txConfig = {};
  txConfig.clk_cfg = I2S_STD_CLK_DEFAULT_CONFIG(AudioBus::SAMPLE_RATE_HZ);
  txConfig.slot_cfg.data_bit_width = I2S_DATA_BIT_WIDTH_16BIT;
  txConfig.slot_cfg.slot_bit_width = I2S_SLOT_BIT_WIDTH_32BIT;  // 与 RX 共用时钟，槽宽必须一致
  txConfig.slot_cfg.slot_mode = I2S_SLOT_MODE_MONO;
  txConfig.slot_cfg.slot_mask = I2S_STD_SLOT_LEFT;
  txConfig.slot_cfg.ws_width = 32;  // WS 半周期 = 槽宽，不能按 16 bit 计算
  txConfig.slot_cfg.ws_pol = false;
  txConfig.slot_cfg.bit_shift = true;   // Philips 格式的 1 bit 延迟
  txConfig.slot_cfg.left_align = true;  // 16 bit 样本左对齐放进 32 bit 槽
  txConfig.slot_cfg.big_endian = false;
  txConfig.slot_cfg.bit_order_lsb = false;
  // CLK 默认悬空；把 PIN_I2S_MCLK 改成实际 GPIO 即可输出 256×fs = 4.096 MHz。
  txConfig.gpio_cfg.mclk = AudioBus::PIN_I2S_MCLK;
  txConfig.gpio_cfg.bclk = AudioBus::PIN_I2S_BCLK;
  txConfig.gpio_cfg.ws = AudioBus::PIN_I2S_LRCLK;
  txConfig.gpio_cfg.dout = AudioBus::PIN_I2S_DOUT;
  txConfig.gpio_cfg.din = I2S_GPIO_UNUSED;

  // ---------------- RX（INMP441）----------------
  i2s_std_config_t rxConfig = {};
  rxConfig.clk_cfg = I2S_STD_CLK_DEFAULT_CONFIG(AudioBus::SAMPLE_RATE_HZ);
  rxConfig.slot_cfg.data_bit_width = I2S_DATA_BIT_WIDTH_32BIT;  // 24 bit 数据装在 32 bit 槽
  rxConfig.slot_cfg.slot_bit_width = I2S_SLOT_BIT_WIDTH_32BIT;
  rxConfig.slot_cfg.slot_mode = I2S_SLOT_MODE_MONO;
  rxConfig.slot_cfg.slot_mask = AudioBus::MIC_SLOT_MASK;  // 默认左声道，可改 RIGHT
  rxConfig.slot_cfg.ws_width = 32;
  rxConfig.slot_cfg.ws_pol = false;
  rxConfig.slot_cfg.bit_shift = true;
  rxConfig.slot_cfg.left_align = true;
  rxConfig.slot_cfg.big_endian = false;
  rxConfig.slot_cfg.bit_order_lsb = false;
  rxConfig.gpio_cfg.mclk = I2S_GPIO_UNUSED;
  rxConfig.gpio_cfg.bclk = AudioBus::PIN_I2S_BCLK;
  rxConfig.gpio_cfg.ws = AudioBus::PIN_I2S_LRCLK;
  rxConfig.gpio_cfg.dout = I2S_GPIO_UNUSED;
  rxConfig.gpio_cfg.din = AudioBus::PIN_I2S_DIN;

  err = i2s_channel_init_std_mode(s_txHandle, &txConfig);
  if (err != ESP_OK) {
    logError("i2s_channel_init_std_mode(TX)", err);
    destroyPort();
    return false;
  }

  err = i2s_channel_init_std_mode(s_rxHandle, &rxConfig);
  if (err != ESP_OK) {
    logError("i2s_channel_init_std_mode(RX)", err);
    destroyPort();
    return false;
  }

  // 全双工要求 TX/RX 同步启停；先开 TX 让 BCLK/LRCLK 先输出，再开 RX。
  err = i2s_channel_enable(s_txHandle);
  if (err != ESP_OK) {
    logError("i2s_channel_enable(TX)", err);
    destroyPort();
    return false;
  }

  err = i2s_channel_enable(s_rxHandle);
  if (err != ESP_OK) {
    logError("i2s_channel_enable(RX)", err);
    destroyPort();
    return false;
  }

  return true;
}

}  // namespace

namespace AudioBus {

bool begin() {
  // 引用计数大于 0 表示总线已就绪，直接复用。
  if (s_refCount > 0 && s_txHandle != nullptr && s_rxHandle != nullptr) {
    ++s_refCount;
    return true;
  }

  if (!createPort()) {
    s_refCount = 0;
    return false;
  }

  s_refCount = 1;
  Serial.printf("[AudioBus] I2S full-duplex ready: %lu Hz, BCLK=%d, LRCLK=%d, DIN=%d, DOUT=%d\n",
                static_cast<unsigned long>(SAMPLE_RATE_HZ), static_cast<int>(PIN_I2S_BCLK),
                static_cast<int>(PIN_I2S_LRCLK), static_cast<int>(PIN_I2S_DIN),
                static_cast<int>(PIN_I2S_DOUT));
  return true;
}

void end() {
  if (s_refCount == 0) {
    return;
  }
  --s_refCount;
  if (s_refCount > 0) {
    return;
  }
  destroyPort();
  Serial.println("[AudioBus] I2S full-duplex stopped");
}

bool isReady() {
  return s_refCount > 0 && s_txHandle != nullptr && s_rxHandle != nullptr;
}

i2s_chan_handle_t txHandle() {
  return s_txHandle;
}

i2s_chan_handle_t rxHandle() {
  return s_rxHandle;
}

}  // namespace AudioBus
