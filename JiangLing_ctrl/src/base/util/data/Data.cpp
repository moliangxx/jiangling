/*
 * Data.cpp —— DAT 数据队列实现
 *
 * 入队与出队都在极短的临界区内完成；本文件不做任何 BLE 输出，
 * 组帧（HEAD/SEQ/TYPE/LEN/CRC8）在 add() 内一次完成，get() 直接给出可发送的完整帧。
 */
#include "Data.h"

#include <cstring>

#include "../log/Log.h"

using namespace JiangLingProtocol;

Data::Data()
    : _frames{},
      _lengths{},
      _head(0),
      _tail(0),
      _count(0),
      _dropped(0),
      _nextSeq(0) {
}

bool Data::add(uint8_t type, const uint8_t* payload, uint8_t length) {
  // 只校验「该 TYPE 允不允许这个 LEN」，帧长本身由 LEN 决定。
  if (!payloadLengthValid(type, length) ||
      length > DAT_ITEM_MAX_PAYLOAD ||
      (length > 0 && payload == nullptr)) {
    return false;
  }

  const uint8_t frameLength = static_cast<uint8_t>(frameBytesOf(length));

  bool accepted = true;
  bool notifyFull = false;
  portENTER_CRITICAL(&_mux);
  if (_count == DAT_QUEUE_CAPACITY) {
    // 满队列丢弃新到的一条：不腾位置、不覆盖已有条目，直接拒绝本次入队。
    ++_dropped;
    accepted = false;
  } else {
    uint8_t* frame = _frames[_tail];
    beginFrame(frame, _nextSeq, type, length);
    if (length > 0) {
      std::memcpy(frame + FRAME_HEADER_BYTES, payload, length);
    }
    finishFrame(frame, length);
    _lengths[_tail] = frameLength;

    _nextSeq = static_cast<uint8_t>(_nextSeq + 1);
    _tail = static_cast<uint16_t>((_tail + 1) % DAT_QUEUE_CAPACITY);
    ++_count;
    if (_count == DAT_QUEUE_CAPACITY) {
      // 只在「不满 → 满」这一刻提示一次；持续满着时入队被拒，不会重复提示。
      notifyFull = true;
    }
  }
  portEXIT_CRITICAL(&_mux);

  if (notifyFull) {
    // 提示走 LOG 通道，不占 DAT 队列位；参数与日志队列满提示同义。
    JL_LOGW(LOG_MOD_LOG, LOG_FULL_PARAM);
  }
  return accepted;
}

bool Data::get(uint8_t frame[DAT_FRAME_MAX_BYTES], uint8_t& length) {
  bool available = false;
  portENTER_CRITICAL(&_mux);
  if (_count > 0) {
    const uint8_t frameLength = _lengths[_head];
    std::memcpy(frame, _frames[_head], frameLength);
    length = frameLength;
    _head = static_cast<uint16_t>((_head + 1) % DAT_QUEUE_CAPACITY);
    --_count;
    available = true;
  }
  portEXIT_CRITICAL(&_mux);
  return available;
}

void Data::clear() {
  portENTER_CRITICAL(&_mux);
  _head = 0;
  _tail = 0;
  _count = 0;
  _dropped = 0;
  _nextSeq = 0;
  portEXIT_CRITICAL(&_mux);
}

uint16_t Data::pendingCount() const {
  uint16_t pending = 0;
  portENTER_CRITICAL(&_mux);
  pending = _count;
  portEXIT_CRITICAL(&_mux);
  return pending;
}

uint32_t Data::droppedCount() const {
  uint32_t dropped = 0;
  portENTER_CRITICAL(&_mux);
  dropped = _dropped;
  portEXIT_CRITICAL(&_mux);
  return dropped;
}

Data Dat;
