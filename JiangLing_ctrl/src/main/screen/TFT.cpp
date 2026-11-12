/*
 * ============================================================
 * show/TFT.cpp —— AI 助手静态界面（240×320 竖屏）
 * ============================================================
 * 界面分为居中标题、表情区和命令显示区。
 * 中文使用 SPIFFS 中的 16 px GB2312 Smooth Font 字库。
 * 屏幕坐标以左上角为原点：(0, 0) 在左上，x 向右增大、y 向下增大。
 * ============================================================
 */
#include "TFT.h"
#include "../../base/m.h"
#include "../../base/util/log/Log.h"

#include <SPIFFS.h>

using namespace JiangLingProtocol;

// 1 = 上电先整屏红 1 秒、蓝 1 秒做接线自检；确认屏幕正常后可改成 0 关闭。
#define TFT_COLOR_SELF_TEST 1

// 命令区重绘节流（行为取舍开关）：0 = 每条命令立即整块重绘。
#ifndef TFT_CMD_THROTTLE
#define TFT_CMD_THROTTLE 1
#endif
#ifndef TFT_CMD_MIN_INTERVAL_MS
#define TFT_CMD_MIN_INTERVAL_MS 250
#endif

namespace {
// 匿名 namespace 让颜色常量只在本文件可见，避免其他文件同名冲突。
// constexpr 是 C++ 有类型的编译期常量；与 #define 的纯文本替换不同。
// 所有区域统一使用黑底白字；修改颜色可从这里开始。
constexpr uint16_t COLOR_BACKGROUND = TFT_BLACK;
constexpr uint16_t COLOR_TEXT = TFT_WHITE;
}

TftDisplay::TftDisplay() {
  // 构造函数暂时不做硬件操作；全局对象创建时外设可能还没初始化。
  // 硬件初始化因此放在 setup() 明确调用的 init() 中。
}

bool TftDisplay::init() {
  // 初始化 TFT_eSPI 驱动，并设置竖屏方向。
  // 注意：TFT_eSPI::init() 没有返回值，“初始化成功”只能靠后面的自检图案确认。
  TFT_eSPI::init();
  // setRotation 是 TFT_eSPI 提供的方法；0 表示当前使用的竖屏方向。
  setRotation(0);
  fillScreen(TFT_BLACK);

  // 打印驱动实际参数，便于对照代码与硬件，而不是凭经验猜。
  Serial.printf("TFT: driver init, size=%dx%d, rotation=%d, SPI=%d Hz\n",
                width(), height(), static_cast<int>(getRotation()), SPI_FREQUENCY);

  // pinMode 把 GPIO 配成输出，digitalWrite 再将背光引脚拉高点亮。
  pinMode(PIN_9_TFT_BL, OUTPUT);
  digitalWrite(PIN_9_TFT_BL, HIGH);
  // 回读背光脚：输出的高低电平会反映到输入寄存器，可区分“代码写了”和“引脚真为高”。
  Serial.printf("TFT: BLK GPIO%d drive=HIGH, readback=%d\n",
                PIN_9_TFT_BL, digitalRead(PIN_9_TFT_BL));

#if TFT_COLOR_SELF_TEST
  // 最小自检：整屏纯色，和字体、UI、sprite 全部无关。
  // 能看到红/蓝 = SPI、驱动、背光、供电这条硬件链是通的；
  // 红蓝都不出现 = 问题在硬件/接线，不在绘制代码。
  Serial.println("TFT: color test RED 1s");
  fillScreen(TFT_RED);
  delay(1000);
  Serial.println("TFT: color test BLUE 1s");
  fillScreen(TFT_BLUE);
  delay(1000);
  fillScreen(TFT_BLACK);
  Serial.println("TFT: color test done");
#endif

  // 字库只由屏幕使用，因此由屏幕模块负责挂载文件系统并加载它。
  if (!SPIFFS.begin(false)) {
    showFontError("SPIFFS mount failed");
    Serial.println("TFT: SPIFFS mount failed");
    JL_LOGE(LOG_MOD_TFT, TFT_LOG_SPIFFS_FAIL,
              LOG_SERIAL_TEXT("TFT: SPIFFS mount failed"));
    return false;
  }

  if (!SPIFFS.exists("/JiangLingCJK16.vlw")) {
    showFontError("Font file missing; uploadfs needed");
    Serial.println("TFT: /JiangLingCJK16.vlw not found; upload filesystem image");
    JL_LOGE(LOG_MOD_TFT, TFT_LOG_FONT_MISSING,
              LOG_SERIAL_TEXT("TFT: /JiangLingCJK16.vlw not found; upload filesystem image"));
    return false;
  }

  loadFont("JiangLingCJK16", SPIFFS);
  // TFT_eSPI 的 loadFont() 没有返回值；检查库提供的 fontLoaded 状态确认结果。
  if (!fontLoaded) {
    showFontError("Font load failed");
    Serial.println("TFT: Chinese font failed to load");
    JL_LOGE(LOG_MOD_TFT, TFT_LOG_FONT_LOAD_FAIL,
              LOG_SERIAL_TEXT("TFT: Chinese font failed to load"));
    return false;
  }

  Serial.println("TFT: SPIFFS + font ready");
  _ready = true;
  return true;
}

void TftDisplay::showAiHome() {
  if (!_ready) {
    return;
  }
  // 首页重绘时依次绘制标题、表情和对话区域。
  fillScreen(COLOR_BACKGROUND);
  drawTitle();
  drawExpression();
  drawConversation();
}

void TftDisplay::showCommand(const String& command) {
  if (!_ready) {
    return;
  }
  // 只记下最新命令，真正重绘交给 update() 节流执行（0xEB 5 Hz 下不阻塞主循环）。
  _pendingCommand = command;
  _commandDirty = true;
}

void TftDisplay::update() {
  if (!_ready || !_commandDirty) {
    return;
  }
#if TFT_CMD_THROTTLE
  const uint32_t nowMs = millis();
  if (_lastDrawMs != 0 &&
      static_cast<uint32_t>(nowMs - _lastDrawMs) < TFT_CMD_MIN_INTERVAL_MS) {
    return;
  }
  _lastDrawMs = nowMs;
#endif
  _commandDirty = false;
  _command = _pendingCommand;
  drawConversation();
}

void TftDisplay::showFontError(const char* message) {
  // 字库加载失败时使用内置英文字体显示错误，避免再次依赖外部中文字库。
  fillScreen(COLOR_BACKGROUND);
  setTextDatum(MC_DATUM);
  setTextColor(COLOR_TEXT, COLOR_BACKGROUND);
  setTextSize(1);
  drawString("FONT ERROR", TFT_WIDTH / 2, 130);
  drawString(message, TFT_WIDTH / 2, 165);
  setTextDatum(TL_DATUM);
}

void TftDisplay::drawTitle() {
  // MC_DATUM 让给定坐标作为文字中心；TL_DATUM 则把坐标作为左上角。
  setTextDatum(MC_DATUM);
  setTextColor(COLOR_TEXT, COLOR_BACKGROUND);
  drawString("灵渊", TFT_WIDTH / 2, 28);
  setTextDatum(TL_DATUM);
}

void TftDisplay::drawExpression() {
  // 以下坐标、半径、宽高均以像素计；绘图函数由 TFT_eSPI 提供。
  // 圆角矩形参数依次是左上 x/y、宽、高、圆角半径和颜色。
  drawRoundRect(20, 52, 200, 126, 16, COLOR_TEXT);

  setTextDatum(MC_DATUM);
  setTextColor(COLOR_TEXT, COLOR_BACKGROUND);
  drawString("表情", TFT_WIDTH / 2, 68);
  setTextDatum(TL_DATUM);

  // 圆形参数是圆心 x/y、半径和颜色；填充圆用作眼睛。
  drawCircle(120, 124, 38, COLOR_TEXT);
  fillCircle(107, 118, 4, COLOR_TEXT);
  fillCircle(133, 118, 4, COLOR_TEXT);

  // 嘴巴由多个线段拼成；每条线的参数是起点 x/y、终点 x/y、颜色。
  drawLine(104, 136, 110, 142, COLOR_TEXT);
  drawLine(110, 142, 117, 145, COLOR_TEXT);
  drawLine(117, 145, 123, 145, COLOR_TEXT);
  drawLine(123, 145, 130, 142, COLOR_TEXT);
  drawLine(130, 142, 136, 136, COLOR_TEXT);
}

void TftDisplay::drawConversation() {
  // 先擦除内容区域，避免新命令覆盖旧文字留下残影。
  // fillRect 参数为左上 x/y、宽、高和颜色；drawRoundRect 画对话框边框。
  fillRect(21, 191, 198, 118, COLOR_BACKGROUND);
  drawRoundRect(20, 190, 200, 120, 16, COLOR_TEXT);
  // 设置文字对齐点和颜色；背景色用于清除绘制文字下面的旧像素。
  setTextDatum(MC_DATUM);
  setTextColor(COLOR_TEXT, COLOR_BACKGROUND);
  drawString("对话", TFT_WIDTH / 2, 210);

  setTextDatum(TL_DATUM);
  drawString("命令:", 32, 250);
  drawFittedText(_command, 80, 250, 126);
  setTextDatum(TL_DATUM);
}

void TftDisplay::drawFittedText(
    const String& text,
    int32_t x,
    int32_t y,
    int32_t maxWidth) {
  String visible;
  size_t offset = 0;

  // UTF-8 字符可能占 1 到 4 字节，逐个字符测量以适配屏幕宽度。
  while (offset < text.length()) {
    // UTF-8 首字节高位模式标识字符长度：
    // 0xxxxxxx=1 字节、110xxxxx=2、1110xxxx=3、11110xxx=4。
    // static_cast 是 C++ 显式类型转换，比 C 风格 (uint8_t) 更清晰。
    const uint8_t firstByte = static_cast<uint8_t>(text[offset]);
    size_t charLength = 1;
    if ((firstByte & 0xE0) == 0xC0) {
      charLength = 2;
    } else if ((firstByte & 0xF0) == 0xE0) {
      charLength = 3;
    } else if ((firstByte & 0xF8) == 0xF0) {
      charLength = 4;
    }
    if (offset + charLength > text.length()) {
      break;
    }

    // substring 的结束位置不包含在内；先试加一个完整字符再测量总宽度。
    String next = visible + text.substring(offset, offset + charLength);
    // 超出可用宽度就追加省略号并停止，不把一个 UTF-8 字符截成乱码。
    if (textWidth(next) > maxWidth) {
      visible += "...";
      break;
    }

    visible = next;
    offset += charLength;
  }

  drawString(visible, x, y);
}

// 全局对象的实际定义；其他文件通过 TFT.h 中的 extern 访问同一个屏幕对象。
TftDisplay TFT;
