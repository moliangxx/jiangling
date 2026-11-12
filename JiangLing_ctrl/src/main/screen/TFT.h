/*
 * ============================================================
 * show/TFT.h —— 彩屏模块的声明（告诉其他文件能调用哪些方法）
 * ============================================================
 * 【用法（和 Arduino 的 Serial 一样）】
 *   #include "show/TFT.h"
 *   if (!TFT.init()) return; // 初始化屏幕、SPIFFS 和中文字库，失败返回 false
 *   TFT.showAiHome();      // 绘制 AI 助手中文静态示例界面
 *   TFT.showFontError();   // 字体文件不可用时显示错误
 *
 * 【C++ 学习提示】
 *   class、继承、public/private 是 C++ 特性，C 语言没有这些语法。
 *   TftDisplay 继承 TFT_eSPI，因此能直接使用父类提供的绘图方法；
 *   全局对象叫 TFT，整个工程共用这一个。
 *   构造函数不碰硬件，硬件初始化放在 init() 里（在 setup() 中调用）。
 *   本文件只声明方法；TFT.cpp 中的 TftDisplay::方法名 才是方法实现。
 * ============================================================
 */
#ifndef TFT_DISPLAY_H
#define TFT_DISPLAY_H

// Arduino.h 提供 String；TFT_eSPI 提供屏幕驱动和绘图接口。
#include <Arduino.h>
#include <TFT_eSPI.h>

// “: public TFT_eSPI”表示公有继承，可直接调用 TFT_eSPI 的公开方法。
class TftDisplay : public TFT_eSPI {
  public:
    // 构造函数名与类名相同且没有返回类型；实现放在 TFT.cpp。
    TftDisplay();

    // public 接口可从其他模块调用，例如 main.cpp 中的 TFT.init()。
    // 初始化屏幕、SPIFFS 和中文字库；任一步骤失败都会显示错误并返回 false。
    bool init();
    // 屏幕是否可用；init() 失败后所有绘制接口都会安全空转。
    bool isReady() const { return _ready; }
    void showAiHome();                       // 绘制中文标题、表情和对话区
    void showFontError(const char* message); // 显示字体或文件系统错误
    // 请求更新命令显示区域；真正的重绘由 update() 按节流间隔执行。
    void showCommand(const String& command);
    // 主循环调用：按最小间隔把最新命令刷到屏幕，避免每条命令都整块重绘。
    void update();

  private:
    // private 成员只供本类内部使用；这些绘制函数由 showAiHome() 组合调用。
    void drawTitle();
    void drawExpression();
    void drawConversation();
    // 按 UTF-8 字符边界截断文字，避免中文字符被拆成乱码。
    void drawFittedText(const String& text, int32_t x, int32_t y, int32_t maxWidth);

    // 屏幕启动时显示的默认命令提示。
    String _command = "未收到指令";
    // 硬件/字库是否就绪（init() 成功才置 true）。
    bool _ready = false;
    // 待绘制的最新命令与节流状态。
    String _pendingCommand;
    bool _commandDirty = false;
    uint32_t _lastDrawMs = 0;
};

// extern 只声明对象存在；对象在 TFT.cpp 中定义一次，整个工程共用。
extern TftDisplay TFT;

#endif // TFT_DISPLAY_H
