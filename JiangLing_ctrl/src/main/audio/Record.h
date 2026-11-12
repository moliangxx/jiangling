/*
 * ============================================================
 * audio/Record.h —— 录音 + 录完自动回放（非阻塞状态机）
 * ============================================================
 * 【用途】
 *   CMD_RECORD 到达后录 N 秒（1~10），录满立刻用喇叭把整段播一遍，
 *   不回传音频给 App；ACK 已在命令受理时回过，回放是后续自动动作。
 *
 * 【内存】
 *   16 kHz / 16 bit 单声道 = 32 KB/s，10 s 需约 320 KB，内部 RAM 放不下。
 *   begin() 优先用 PSRAM（ps_malloc）；PSRAM 不可用或分配失败时退回内部
 *   RAM，并按实际拿到的大小收紧 maxSeconds()（通常只有 1~2 s）。
 *   缓冲只分配一次，之后复用；空闲时不释放，避免反复碎片化。
 *
 * 【非阻塞】
 *   update() 每轮只从 Mic 取一小块、向 Speaker 喂一小块；DMA 满就立即
 *   返回，由主循环下一轮继续，全程不 delay。
 *
 * 【互斥】
 *   同一时间只做一件事：录音中再收到录音命令回 ACK_ST_CANNOT；
 *   录音/回放期间命令执行层拒绝 CMD_AUDIO_TEST，避免测试音混进录制或与回放叠音；
 *   互斥判定只由 Record 的 isBusy() 提供，不再有独立的提示音状态机。
 *
 * 【依赖】
 *   Mic（采集）、Speaker（回放，带软件音量）、AudioBus（共用 I2S）。
 *   麦克风失败只影响录音；喇叭失败只影响回放，录制仍可完成。
 * ============================================================
 */
#ifndef AUDIO_RECORD_H
#define AUDIO_RECORD_H

#include <Arduino.h>

namespace Record {

// start() 的返回码；命令执行层据此决定 ACK 状态（0 → ACK_ST_DONE，其余 → ACK_ST_CANNOT）。
enum StartCode : uint8_t {
  START_OK = 0,
  START_BUSY = 1,       // 正在录音或回放
  START_NO_MIC = 2,     // 麦克风不可用
  START_TOO_LONG = 3,   // 秒数超出当前缓冲允许的最大值
  START_NO_BUFFER = 4   // 录音缓冲未分配成功
};

// 分配录音缓冲并记录最大可录秒数；返回缓冲是否就绪。
bool begin();

// 录音缓冲是否已就绪。
bool isReady();

// 当前缓冲实际支持的最大录音秒数（PSRAM 下为 10，内部 RAM 下通常为 1~2）。
uint16_t maxSeconds();

bool isRecording();
bool isPlaying();

// 正在录音或回放；命令执行层用它做互斥判断。
bool isBusy();

// 开始录一段 seconds 秒的音频，录满后自动进入回放。
StartCode start(uint16_t seconds);

// 立即放弃当前录音/回放，回到空闲。
void stop();

// 主循环每轮调用一次：推进采集与回放，内部不等待。
void update();

// 释放缓冲并回到空闲（当前固件不在运行期调用）。
void end();

}  // namespace Record

#endif  // AUDIO_RECORD_H
