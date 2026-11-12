/*
 * Log.h —— 日志码队列（JiangLing AI，V1.10 帧协议）
 *
 * 解耦结构：
 *   调用点只把「等级 + 模块 + 事件码 + 参数」非阻塞地入队；
 *   队列条目固定 2 字节（TYPE + param），板端**只存码不存文本**；
 *   出队由 get() 一次取最多 8 条组成一条完整 LOG 帧
 *   （AA | SEQ | 帧 TYPE | LEN | 每条 [TYPE, LEN=1, param] | CRC8，帧头/SEQ/CRC8 在本类内生成），
 *   WiFi 出口拿到即发；断连时含 W 或 E 的整帧进 32 帧补发环，D/I 丢弃。
 *
 * 设计约束：
 *   1. 有界环形队列，满时丢弃新到的一条并累计 droppedCount()，绝不阻塞调用方；
 *      队列由不满变满的那一刻只置一次「已满」标志；get 优先吐出一条提示记录，
 *      吐出后清标志，队列持续满着时不会每次 get 都重复提示；
 *   2. 入队可能来自 AI 任务或主循环，出队只由主循环调用，用临界区保护；
 *   3. 串口文本只在 LOG_SERIAL_OUT=1 时由调用点提供，LOG 帧上永远只有码；
 *   4. 只记录本地 millis() tick，不伪造绝对时间（断连期间无可信时钟）；
 *   5. RAM 缓冲，掉电即失，不做 Flash/NVS 持久化；
 *   6. 断连补发环也在本类内部：出队判别 W/E 后入环，重连后按 30 s 时效补发，
 *      缺失方（WiFi 出口）只负责发送节奏，不再持有一份补发缓冲；
 *   7. 帧序号（LOG 帧的 SEQ）由本类统一取号，断连重连不重置。
 *
 * 【时序红线】串口输出是**阻塞写**：115200 下每字符约 87 µs，一条 60 字符的日志
 *   约 5 ms，足以打乱 I2S DMA 与任何实时控制。
 *   因此，禁止在以下上下文调用会产生串口输出的 JL_LOGx / Log.log / Log.debug 等：
 *     - 音频任务（I2S 播放/录音循环与回调）；
 *     - 任何 ISR（另：队列本身用临界区，ISR 里也不能入队）。
 *   这些上下文只能调 Log.enqueueOnly()（只入队、不写串口），且必须确认不在 ISR 中。
 *   串口文本只允许在 setup() 与 loop() 主任务里输出。
 */
#ifndef LOG_H
#define LOG_H

#include <Arduino.h>
#include <freertos/FreeRTOS.h>

#include "../../Protocol.h"

// ==================== 日志开关（全工程唯一定义处）====================
// 三层开关，任何一处都不要再定义第二个默认值：
//   1) JL_LOG_ENABLE      —— 编译期总开关。0 = 所有调用点由宏整体展开为空，
//                            不产生代码、不评估参数，字符串常量也不会进 Flash。
//   2) JL_LOG_MIN_LEVEL   —— 运行时最低等级初值（Level 枚举 = 线路等级位：
//                            D=0 / E=1 / W=2 / I=3，Fatal=4 并入 E），
//                            可在 setup() 里用 Log.setMinLevel() 调整。
//   3) LOG_DEFAULT_CHANNELS —— 运行时通道位掩码初值（串口 / LOG 帧 / 屏幕），
//                            可在 setup() 里用 Log.setChannelMask() 调整。
//
// 串口默认：开发态（未定义 NDEBUG）开，发布态（定义了 NDEBUG）关；
// 这个默认只在本段出现一次，别在 platformio.ini 或别的头文件里再写一份。
#ifndef JL_LOG_ENABLE
#define JL_LOG_ENABLE 1
#endif
#ifndef JL_LOG_MIN_LEVEL
#define JL_LOG_MIN_LEVEL 0
#endif
// 串口默认在所有通道关闭时保持关闭（总开关关掉时不应再编译串口写）。
#ifndef LOG_SERIAL_OUT
#if defined(NDEBUG) || !JL_LOG_ENABLE
#define LOG_SERIAL_OUT 0
#else
#define LOG_SERIAL_OUT 1
#endif
#endif

// 运行时通道位掩码。
constexpr uint8_t LOG_CHANNEL_SERIAL = 0x01;  // 本地串口文本
// LOG 帧出口（V21 LOG 帧经 TCP 8080 发 App）。
constexpr uint8_t LOG_CHANNEL_FRAME = 0x02;
constexpr uint8_t LOG_CHANNEL_SCREEN = 0x04;  // 屏幕叠加显示（当前无消费者）

#ifndef LOG_DEFAULT_CHANNELS
#if LOG_SERIAL_OUT
#define LOG_DEFAULT_CHANNELS (LOG_CHANNEL_SERIAL | LOG_CHANNEL_FRAME)
#else
#define LOG_DEFAULT_CHANNELS (LOG_CHANNEL_FRAME)
#endif
#endif

// 断连补发开关：默认 1，仅对 WARN/ERROR 生效。
#ifndef LOG_BACKFILL_ENABLE
#define LOG_BACKFILL_ENABLE 1
#endif

// 串口文本参数：串口通道关闭或总开关关闭时展开为 nullptr，字符串常量不进 Flash。
// 即使串口开着，也只允许在主循环调用（见下方「时序红线」）。
#if LOG_SERIAL_OUT && JL_LOG_ENABLE
#define LOG_SERIAL_TEXT(text) (text)
#else
#define LOG_SERIAL_TEXT(text) nullptr
#endif

// 调用点宏：用宏把整条调用包掉，关闭时参数完全不评估（不是运行时 if）。
// 参数顺序与 Logger::log 一致：module, code, text[, param]。
#if JL_LOG_ENABLE
#define JL_LOGD(module, code, ...) Log.debug(module, code, ##__VA_ARGS__)
#define JL_LOGI(module, code, ...) Log.info(module, code, ##__VA_ARGS__)
#define JL_LOGW(module, code, ...) Log.warning(module, code, ##__VA_ARGS__)
#define JL_LOGE(module, code, ...) Log.error(module, code, ##__VA_ARGS__)
#define JL_LOGF(module, code, ...) Log.fatal(module, code, ##__VA_ARGS__)
#else
#define JL_LOGD(module, code, ...) ((void)0)
#define JL_LOGI(module, code, ...) ((void)0)
#define JL_LOGW(module, code, ...) ((void)0)
#define JL_LOGE(module, code, ...) ((void)0)
#define JL_LOGF(module, code, ...) ((void)0)
#endif

// 队列容量（条）。每条 2 字节，64 条约可缓冲 1.2 s 突发。
constexpr uint16_t LOG_QUEUE_CAPACITY = 64;
// 断连补发缓冲容量（条）。
constexpr uint16_t LOG_BACKFILL_CAPACITY = 32;
// 补发时效窗口（ms）：超过该时长未补发的条目直接丢弃。
constexpr uint32_t LOG_BACKFILL_WINDOW_MS = 30000;

// 「日志队列已满」一次性提示记录：不占队列，由 pop 直接吐出。
// TYPE = KIND_LOG | LOG_ZONE_WARN | LOG_MOD_LOG = 0x62；
// LOG_MOD_LOG（模块 2）在 Protocol.h 中只作为日志模块保留，目前没有任何事件码，
// 因此 param 0x01 不会占用其它事件码。
constexpr uint8_t LOG_FULL_TYPE =
    JiangLingProtocol::makeLogType(JiangLingProtocol::LOG_ZONE_WARN,
                                   JiangLingProtocol::LOG_MOD_LOG);
constexpr uint8_t LOG_FULL_PARAM = 0x01;
static_assert(LOG_FULL_TYPE == 0x62, "queue-full TYPE must be 0x62");

class Logger {
  public:
    Logger();

    enum class Level : uint8_t {
      Debug = 0,    // D：线路等级位 0
      Error = 1,    // E：线路等级位 1（Fatal 也按 E 发）
      Warning = 2,  // W：线路等级位 2
      Info = 3,     // I：线路等级位 3
      Fatal = 4     // 仅用于严重度排序，线路 TYPE 仍并入 E
    };
    // 枚举前四级的数值必须与线路等级位一致，杜绝注释与 Protocol.h 出现两套写法。
    static_assert(static_cast<uint8_t>(Level::Debug) ==
                      JiangLingProtocol::LOG_ZONE_DEBUG,
                  "Level::Debug must equal LOG_ZONE_DEBUG (0)");
    static_assert(static_cast<uint8_t>(Level::Error) ==
                      JiangLingProtocol::LOG_ZONE_ERROR,
                  "Level::Error must equal LOG_ZONE_ERROR (1)");
    static_assert(static_cast<uint8_t>(Level::Warning) ==
                      JiangLingProtocol::LOG_ZONE_WARN,
                  "Level::Warning must equal LOG_ZONE_WARN (2)");
    static_assert(static_cast<uint8_t>(Level::Info) ==
                      JiangLingProtocol::LOG_ZONE_INFO,
                  "Level::Info must equal LOG_ZONE_INFO (3)");

    // 初始化串口；仅当 LOG_SERIAL_OUT=1 且通道掩码含串口时才真正占用 UART。
    // baudRate 常用 115200；串口关闭时本调用是空操作，不阻塞。
    void begin(uint32_t baudRate);

    // 运行时最低等级：按严重度 D < I < W < E < F 比较（不能直接比枚举数值），
    // 低于该等级的记录直接丢弃，不入队、不输出。
    // 默认取 JL_LOG_MIN_LEVEL；可在 setup() 里调整，纯赋值，非线程安全但只改 1 B。
    void setMinLevel(Level level);
    Level minLevel() const;

    // 运行时通道位掩码（LOG_CHANNEL_* 组合），默认取 LOG_DEFAULT_CHANNELS。
    // 只影响「往哪条通道输出」：串口文本是否打印、WiFi 是否取走 LOG 帧；
    // 入队本身不受影响，所以关掉 LOG 帧通道后再打开仍可能读到积压的旧记录（队列满即丢新）。
    void setChannelMask(uint8_t mask);
    uint8_t channelMask() const;
    bool channelEnabled(uint8_t channel) const;

    // 只入队、绝不写串口：给 BLE Server 回调、音频任务等不能阻塞的上下文用。
    // 参数语义同 log()，但没有 text；队列满时同样丢新并计入 droppedCount()。
    // 内部仍有临界区，**不可在 ISR 调用**。
    void enqueueOnly(Level level, uint8_t module, uint8_t code,
                     uint8_t param = 0);

    // 等级 + 模块 → 日志记录 TYPE：高 2 位 KIND_LOG、中间 2 位等级（D=0/E=1/W=2/I=3）、低 4 位模块。
    // module 取 LOG_MOD_*（0~15），越界会被截断到低 4 位；纯计算，可在 ISR 调用。
    static uint8_t typeOf(Level level, uint8_t module);

    // 入队（非阻塞）。duration 只取决于是否恰好写满队列，不等待消费者。
    // level: 决定记录 TYPE 的等级位（Debug→D、Info→I、Warning→W、Error/Fatal→E）；
    // module: LOG_MOD_*，决定低 4 位；
    // code: 模块内事件码，写进 param（0x01~0x1F 事件码、0x20~0x7F 数值段）；
    // text: 只在 LOG_SERIAL_OUT=1 时输出到串口，BLE/WiFi 上永远只有码；
    // param: 事件附带数值，0 表示无附加数值。
    // 队列满时丢弃本条并累计 droppedCount()，不阻塞、不丢已有条目。
    // 内部用临界区保护队列，**不可在 ISR 调用**（日志本身也不适合在中断里刷）。
    void log(Level level, uint8_t module, uint8_t code, const char* text = nullptr,
             uint8_t param = 0);
    void debug(uint8_t module, uint8_t code, const char* text = nullptr,
               uint8_t param = 0);
    void info(uint8_t module, uint8_t code, const char* text = nullptr,
              uint8_t param = 0);
    void warning(uint8_t module, uint8_t code, const char* text = nullptr,
                 uint8_t param = 0);
    void error(uint8_t module, uint8_t code, const char* text = nullptr,
               uint8_t param = 0);
    void fatal(uint8_t module, uint8_t code, const char* text = nullptr,
               uint8_t param = 0);

    // 取出一帧完整的 LOG 帧（最多 8 条记录，实际条数可少；非阻塞），length 为帧总长。
    // 帧内 SEQ 由本类取号，HEAD/LEN/CRC8 也在这里生成，调用方拿到即可直接发送。
    // 队列刚满时该帧只包含一次性「日志队列已满」记录（TYPE=0x62、LEN=1、param=0x01），
    // 该记录不占队列；提示吐过一次后，队列仍满也按正常出队，避免永久堵死。
    bool get(uint8_t frame[JiangLingProtocol::LOG_FRAME_MAX_BYTES],
             uint8_t& length);

    // 清空队列、丢弃计数、满标志与补发环；帧序号保持不变，保证断连重连后序号连续。
    // 用于模式切换/会话重置；非阻塞，不可在 ISR 调用。
    void clear();

    // 当前待出队条数（补发缓冲不计入）。只读快照，可在任意任务调用。
    uint16_t pendingCount() const;

    // 因队列满而丢弃的条数（统计自上次上电/clear）。只读，用于诊断。
    uint32_t droppedCount() const;

    // 发送出口不可用时把整帧放进补发环：帧内至少含一条 W 或 E 才保留，
    // D/I 帧直接丢弃；帧字节原样保存，重连后不再重新组帧。
    // frame/length: 必须是 get() 刚取出的完整帧与帧总长，越界长度会被拒绝。
    // 环满时丢弃最旧一条；非阻塞，不可在 ISR 调用（有临界区与淘汰逻辑）。
    void parkOffline(const uint8_t frame[JiangLingProtocol::LOG_FRAME_MAX_BYTES],
                     uint8_t length);

    // 取一帧补发日志（非阻塞）；超过时效窗口的条目先丢弃，nowMs 为当前 millis()。
    // 返回：false = 补发环为空（或全部过期），frame/length 不变；
    //       true = 已取出一帧完整帧，可直接发送。
    // nowMs 必须由调用方传入实时 millis()，用于 30 s 时效判断；不可在 ISR 调用。
    bool takeOffline(uint8_t frame[JiangLingProtocol::LOG_FRAME_MAX_BYTES],
                     uint8_t& length, uint32_t nowMs);

    // 补发环中待发送条数。只读快照，可在任意任务调用。
    uint16_t offlineCount() const;

  private:
    struct Entry {
      uint8_t type;
      uint8_t param;
    };

    struct OfflineEntry {
      uint32_t createdAtMs;  // 进入补发环时的本地 tick，用于时效判断
      uint8_t length;        // 帧总长（5 + LEN），补发时按实际长度发送
      uint8_t frame[JiangLingProtocol::LOG_FRAME_MAX_BYTES];
    };

    void enqueue(uint8_t type, uint8_t param);
    void dropOfflineHead();

    Entry _entries[LOG_QUEUE_CAPACITY];
    uint16_t _head;
    uint16_t _tail;
    uint16_t _count;
    uint32_t _dropped;
    // 「队列已满」提示的一次性标志：只在未满→满时置位，吐出一条后清零。
    bool _fullPending;

    // 断连补发环：只缓存 W/E，满时丢最旧。
    OfflineEntry _offline[LOG_BACKFILL_CAPACITY];
    uint16_t _offlineHead;
    uint16_t _offlineTail;
    uint16_t _offlineCount;

    // LOG 帧序号：每发一帧 +1 回绕，断连重连不重置。
    uint8_t _frameSeq;

    // 运行时开关：最低等级与通道位掩码，初值来自 Log.h 的编译期默认，只此一份。
    Level _minLevel;
    uint8_t _channels;

    mutable portMUX_TYPE _mux = portMUX_INITIALIZER_UNLOCKED;
};

// 全局日志对象：在 Log.cpp 中创建一次，供整个项目共用。
extern Logger Log;

#endif  // LOG_H
