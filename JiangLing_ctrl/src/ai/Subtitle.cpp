/*
 * ============================================================
 * ai/Subtitle.cpp —— 固定槽位 + 临界区实现
 * ============================================================
 * 不用 String / 动态内存：字幕每句都会来，避免长时间运行产生碎片。
 * 写入时拷贝到固定槽位，读取时再拷到调用方缓冲，临界区都很短。
 * ============================================================
 */
#include "Subtitle.h"

#include <cstring>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>

namespace {

struct Entry {
  char source[12];
  char text[Subtitle::MAX_TEXT_BYTES];
};

Entry g_entries[Subtitle::SLOTS];
volatile size_t g_head = 0;   // 下一次写入的位置
volatile uint32_t g_revision = 0;
portMUX_TYPE g_mux = portMUX_INITIALIZER_UNLOCKED;

void copyCString(char* dst, size_t capacity, const char* src) {
  if (dst == nullptr || capacity == 0) {
    return;
  }
  if (src == nullptr) {
    dst[0] = '\0';
    return;
  }
  size_t i = 0;
  for (; i + 1 < capacity && src[i] != '\0'; ++i) {
    dst[i] = src[i];
  }
  dst[i] = '\0';
}

}  // namespace

namespace Subtitle {

void clear() {
  portENTER_CRITICAL(&g_mux);
  for (size_t i = 0; i < SLOTS; ++i) {
    g_entries[i].source[0] = '\0';
    g_entries[i].text[0] = '\0';
  }
  g_head = 0;
  g_revision = 0;
  portEXIT_CRITICAL(&g_mux);
}

void push(const char* source, const char* text) {
  if (text == nullptr || text[0] == '\0') {
    return;
  }
  portENTER_CRITICAL(&g_mux);
  Entry& slot = g_entries[g_head];
  copyCString(slot.source, sizeof(slot.source), source);
  copyCString(slot.text, sizeof(slot.text), text);
  g_head = (g_head + 1) % SLOTS;
  ++g_revision;
  portEXIT_CRITICAL(&g_mux);
}

bool latest(char* text, size_t textCapacity,
            char* source, size_t sourceCapacity) {
  if (text == nullptr || textCapacity == 0) {
    return false;
  }
  portENTER_CRITICAL(&g_mux);
  const uint32_t revision = g_revision;
  const size_t index = (g_head + SLOTS - 1) % SLOTS;
  if (revision == 0) {
    portEXIT_CRITICAL(&g_mux);
    return false;
  }
  copyCString(text, textCapacity, g_entries[index].text);
  if (source != nullptr && sourceCapacity > 0) {
    copyCString(source, sourceCapacity, g_entries[index].source);
  }
  portEXIT_CRITICAL(&g_mux);
  return true;
}

uint32_t revision() {
  portENTER_CRITICAL(&g_mux);
  const uint32_t value = g_revision;
  portEXIT_CRITICAL(&g_mux);
  return value;
}

}  // namespace Subtitle
