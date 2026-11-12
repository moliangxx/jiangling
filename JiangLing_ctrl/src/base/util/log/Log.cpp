/*
 * Log.cpp —— 日志码队列实现
 *
 * 入队与出队都在极短的临界区内完成；本文件不做任何 BLE/网络输出，
 * 但 get() 出口直接产出完整的 LOG 帧（帧头、LEN、SEQ 与 CRC8 都在本类内完成），
 * BLE / WiFi 发送层只把字节写进链路。串口文本是可选本地排障出口，由 LOG_SERIAL_OUT 控制。
 */
#include "Log.h"

#include <cstring>

using namespace JiangLingProtocol;

namespace {

// 严重度排序：枚举数值按线路等级位排（D=0/E=1/W=2/I=3），
// 所以「低于最低等级」不能直接比数值，必须按 D<I<W<E<F 的严重度比较。
constexpr uint8_t severityOf(Logger::Level level) {
  switch (level) {
    case Logger::Level::Debug:
      return 0;
    case Logger::Level::Info:
      return 1;
    case Logger::Level::Warning:
      return 2;
    case Logger::Level::Error:
      return 3;
    case Logger::Level::Fatal:
      return 4;
  }
  return 0;
}

// 一条记录 = [TYPE][LEN=1][param]；记录自身的 LEN 目前恒为 1。
void writeRecord(uint8_t* records, uint8_t index, uint8_t type, uint8_t param) {
  uint8_t* record = records + index * LOG_RECORD_BYTES;
  record[0] = type;
  record[1] = LOG_RECORD_PARAM_BYTES;
  record[2] = param;
}

}  // namespace

Logger::Logger()
    : _head(0),
      _tail(0),
      _count(0),
      _dropped(0),
      _fullPending(false),
      _offline{},
      _offlineHead(0),
      _offlineTail(0),
      _offlineCount(0),
      _frameSeq(0),
      _minLevel(static_cast<Level>(JL_LOG_MIN_LEVEL)),
      _channels(static_cast<uint8_t>(LOG_DEFAULT_CHANNELS)) {
}

void Logger::begin(uint32_t baudRate) {
#if LOG_SERIAL_OUT
  // 串口只是可选兜底出口；通道掩码里没有串口就完全不初始化，避免占用 UART0。
  if (channelEnabled(LOG_CHANNEL_SERIAL)) {
    Serial.begin(baudRate);
  }
#else
  (void)baudRate;
#endif
}

void Logger::setMinLevel(Level level) {
  _minLevel = level;
}

Logger::Level Logger::minLevel() const {
  return _minLevel;
}

void Logger::setChannelMask(uint8_t mask) {
  _channels = mask;
}

uint8_t Logger::channelMask() const {
  return _channels;
}

bool Logger::channelEnabled(uint8_t channel) const {
  return (_channels & channel) != 0;
}

uint8_t Logger::typeOf(Level level, uint8_t module) {
  // 枚举前四级与线路等级位一一对应（D=0/E=1/W=2/I=3），只有 Fatal 并入 E。
  const uint8_t zone = level == Level::Fatal
                           ? LOG_ZONE_ERROR
                           : static_cast<uint8_t>(level);
  return makeLogType(zone, module);
}

void Logger::log(Level level, uint8_t module, uint8_t code, const char* text,
                 uint8_t param) {
  // 运行时等级过滤：按严重度比较（枚举数值是线路等级位，不能直接比大小）。
  if (severityOf(level) < severityOf(_minLevel)) {
    return;
  }
  const uint8_t type = typeOf(level, module);

#if LOG_SERIAL_OUT
  // 串口只给人看：等级、模块、事件码 + 调用点提供的文本。
  // 这是阻塞写（115200 下每字符约 87 µs），调用方必须保证自己在主循环上下文；
  // BLE 回调 / 音频任务 / ISR 请改用 enqueueOnly()。
  if (!channelEnabled(LOG_CHANNEL_SERIAL)) {
    enqueue(type, param != 0 ? param : code);
    return;
  }
  const char* tag = "D";
  switch (level) {
    case Level::Info:
      tag = "I";
      break;
    case Level::Warning:
      tag = "W";
      break;
    case Level::Error:
    case Level::Fatal:
      tag = "E";
      break;
    default:
      break;
  }
  Serial.print('[');
  Serial.print(tag);
  Serial.print("] mod=");
  Serial.print(module);
  Serial.print(" code=0x");
  if (code < 0x10) {
    Serial.print('0');
  }
  Serial.print(code, HEX);
  if (param != 0) {
    Serial.print(" param=");
    Serial.print(param);
  }
  if (text != nullptr) {
    Serial.print(' ');
    Serial.print(text);
  }
  Serial.println();
#else
  (void)text;
#endif

  enqueue(type, param != 0 ? param : code);
}

void Logger::enqueueOnly(Level level, uint8_t module, uint8_t code,
                         uint8_t param) {
  if (severityOf(level) < severityOf(_minLevel)) {
    return;
  }
  // 与串口通道无关：只写队列，供 BLE 回调 / 音频任务等不能阻塞的上下文使用。
  // 仍有临界区，不可在 ISR 调用。
  enqueue(typeOf(level, module), param != 0 ? param : code);
}

void Logger::debug(uint8_t module, uint8_t code, const char* text,
                   uint8_t param) {
  log(Level::Debug, module, code, text, param);
}

void Logger::info(uint8_t module, uint8_t code, const char* text,
                  uint8_t param) {
  log(Level::Info, module, code, text, param);
}

void Logger::warning(uint8_t module, uint8_t code, const char* text,
                     uint8_t param) {
  log(Level::Warning, module, code, text, param);
}

void Logger::error(uint8_t module, uint8_t code, const char* text,
                   uint8_t param) {
  log(Level::Error, module, code, text, param);
}

void Logger::fatal(uint8_t module, uint8_t code, const char* text,
                   uint8_t param) {
  log(Level::Fatal, module, code, text, param);
}

void Logger::enqueue(uint8_t type, uint8_t param) {
  portENTER_CRITICAL(&_mux);
  if (_count == LOG_QUEUE_CAPACITY) {
    // 满队列丢弃新到的一条：不腾位置、不覆盖已有条目，直接拒绝本次入队。
    ++_dropped;
    portEXIT_CRITICAL(&_mux);
    return;
  }

  Entry& entry = _entries[_tail];
  entry.type = type;
  entry.param = param;

  _tail = static_cast<uint16_t>((_tail + 1) % LOG_QUEUE_CAPACITY);
  ++_count;
  if (_count == LOG_QUEUE_CAPACITY) {
    // 只在「不满 → 满」这一刻置一次性提示；持续满着时入队被拒，不会重复置位。
    _fullPending = true;
  }
  portEXIT_CRITICAL(&_mux);
}

bool Logger::get(uint8_t frame[LOG_FRAME_MAX_BYTES], uint8_t& length) {
  uint8_t records[LOG_MAX_PAYLOAD_BYTES];
  uint8_t recordCount = 0;
  uint8_t seq = 0;

  portENTER_CRITICAL(&_mux);
  if (_fullPending) {
    // 「已满」提示不占队列：先吐提示、队列原地不动；吐过一次后清标志，
    // 之后队列仍满也走正常出队，避免队列永远无法被消费。
    _fullPending = false;
    writeRecord(records, 0, LOG_FULL_TYPE, LOG_FULL_PARAM);
    recordCount = 1;
  } else {
    while (recordCount < LOG_MAX_RECORDS && _count > 0) {
      const Entry& entry = _entries[_head];
      writeRecord(records, recordCount, entry.type, entry.param);
      _head = static_cast<uint16_t>((_head + 1) % LOG_QUEUE_CAPACITY);
      --_count;
      ++recordCount;
    }
  }
  // 帧序号与记录出队在同一临界区内取，保证多出口取帧时 SEQ 不重号。
  if (recordCount > 0) {
    seq = _frameSeq;
    _frameSeq = static_cast<uint8_t>(_frameSeq + 1);
  }
  portEXIT_CRITICAL(&_mux);

  if (recordCount == 0) {
    return false;
  }

  // 帧长按实际记录条数变化：LEN = 3 × 条数，不再补 0x00 到固定 8 条。
  const uint8_t payloadLen =
      static_cast<uint8_t>(recordCount * LOG_RECORD_BYTES);
  beginFrame(frame, seq, LOG_FRAME_TYPE, payloadLen);
  std::memcpy(frame + FRAME_HEADER_BYTES, records, payloadLen);
  finishFrame(frame, payloadLen);
  length = static_cast<uint8_t>(frameBytesOf(payloadLen));
  return true;
}

void Logger::clear() {
  portENTER_CRITICAL(&_mux);
  _head = 0;
  _tail = 0;
  _count = 0;
  _dropped = 0;
  _fullPending = false;
  _offlineHead = 0;
  _offlineTail = 0;
  _offlineCount = 0;
  portEXIT_CRITICAL(&_mux);
}

void Logger::parkOffline(const uint8_t frame[LOG_FRAME_MAX_BYTES],
                         uint8_t length) {
  // 整帧按字节原样保存，重连后不再重新组帧；只有帧内至少含一条 WARN/ERROR
  // 记录才值得补发，纯 INFO/DEBUG 的帧断连即丢，不占内存。
  if (length < FRAME_OVERHEAD_BYTES) {
    return;
  }
  const uint8_t payloadLen = frame[3];
  if (frameBytesOf(payloadLen) != length || payloadLen > LOG_MAX_PAYLOAD_BYTES) {
    return;
  }
  bool worthBackfill = false;
  for (uint8_t offset = 0; offset + LOG_RECORD_BYTES <= payloadLen;
       offset += LOG_RECORD_BYTES) {
    const uint8_t levelZone =
        zoneOf(frame[FRAME_HEADER_BYTES + offset]);
    if (levelZone == LOG_ZONE_WARN || levelZone == LOG_ZONE_ERROR) {
      worthBackfill = true;
      break;
    }
  }
  if (!worthBackfill) {
    return;
  }

  portENTER_CRITICAL(&_mux);
  if (_offlineCount == LOG_BACKFILL_CAPACITY) {
    // 只补最近 32 帧：满时丢最旧，给新故障让位。
    dropOfflineHead();
  }
  OfflineEntry& entry = _offline[_offlineTail];
  // 入环时记录本地 tick，出环时用它判断 30 s 时效。
  entry.createdAtMs = millis();
  entry.length = length;
  std::memcpy(entry.frame, frame, length);

  _offlineTail = static_cast<uint16_t>(
      (_offlineTail + 1) % LOG_BACKFILL_CAPACITY);
  ++_offlineCount;
  portEXIT_CRITICAL(&_mux);
}

bool Logger::takeOffline(uint8_t frame[LOG_FRAME_MAX_BYTES], uint8_t& length,
                         uint32_t nowMs) {
  bool available = false;
  portENTER_CRITICAL(&_mux);
  // 超期直接丢弃，避免重连后堆积旧日志。
  while (_offlineCount > 0 &&
         static_cast<uint32_t>(nowMs - _offline[_offlineHead].createdAtMs) >
             LOG_BACKFILL_WINDOW_MS) {
    dropOfflineHead();
  }
  if (_offlineCount > 0) {
    length = _offline[_offlineHead].length;
    std::memcpy(frame, _offline[_offlineHead].frame, length);
    dropOfflineHead();
    available = true;
  }
  portEXIT_CRITICAL(&_mux);
  return available;
}

uint16_t Logger::offlineCount() const {
  uint16_t pending = 0;
  portENTER_CRITICAL(&_mux);
  pending = _offlineCount;
  portEXIT_CRITICAL(&_mux);
  return pending;
}

void Logger::dropOfflineHead() {
  if (_offlineCount == 0) {
    return;
  }
  _offlineHead = static_cast<uint16_t>(
      (_offlineHead + 1) % LOG_BACKFILL_CAPACITY);
  --_offlineCount;
}

uint16_t Logger::pendingCount() const {
  uint16_t pending = 0;
  portENTER_CRITICAL(&_mux);
  pending = _count;
  portEXIT_CRITICAL(&_mux);
  return pending;
}

uint32_t Logger::droppedCount() const {
  uint32_t dropped = 0;
  portENTER_CRITICAL(&_mux);
  dropped = _dropped;
  portEXIT_CRITICAL(&_mux);
  return dropped;
}

Logger Log;
