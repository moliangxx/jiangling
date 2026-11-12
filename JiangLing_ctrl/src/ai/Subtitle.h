/*
 * ============================================================
 * ai/Subtitle.h —— 字幕/文本暂存
 * ============================================================
 * 【来源】
 *   服务端 JSON 里的三类文本：
 *     type=stt                —— 云端识别出的「用户说的话」；
 *     type=tts 且 state=sentence_start —— 助手这一句的文本（与 TTS 音频同源）；
 *     type=llm                —— 情绪/表情标记，可能没有文本，有就一起存。
 *
 * 【为什么只暂存】
 *   当前没有接入屏幕渲染，先把最近几条文本放进环形缓冲；
 *   后续 TFT 只需要调用 latest() 取最新一条，不需要改协议解析。
 *
 * 【线程】
 *   push() 在 AiLink 任务里调用，latest() 可能在 loop() 里调用，
 *   内部用临界区保护，读到的永远是完整的一条。缓冲区固定，不做动态分配。
 * ============================================================
 */
#ifndef AI_SUBTITLE_H
#define AI_SUBTITLE_H

#include <Arduino.h>

namespace Subtitle {

// 文本最大长度（含结束符）；超长会被截断。
constexpr size_t MAX_TEXT_BYTES = 200;
// 环形缓冲条数。
constexpr size_t SLOTS = 4;

// 清空所有暂存。
void clear();

// 写入一条；source 建议用 "stt" / "tts" / "llm"，用于区分说话人。
// 空文本直接忽略。
void push(const char* source, const char* text);

// 取最新一条：成功返回 true，并填 text / source。
// 没有内容时返回 false，不改动输出参数。
bool latest(char* text, size_t textCapacity,
            char* source = nullptr, size_t sourceCapacity = 0);

// 每次 push 都会自增；屏幕可以用它判断「有没有新字幕」。
uint32_t revision();

}  // namespace Subtitle

#endif  // AI_SUBTITLE_H
