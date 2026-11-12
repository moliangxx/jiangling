/*
 * Cmd.h —— 上行 CMD 解析 / 去重 / 分发 / ACK 组包（JiangLing AI，V1.10 帧协议）
 *
 * 职责：
 *   1. 接收已通过 CRC 校验的 CMD 帧：校验 SEQ=0 约定、会话状态、参数范围；
 *   2. 按 App 侧 CMD 的 SEQ 做 64 条去重窗口判定，重复帧只回 DONE，不重复执行；
 *   3. 命令主体通过 ActionHandler 回调交给调用方（WiFi 请求入口），本类不碰音频/屏幕/电机；
 *   4. 用 Protocol.h 的 ACK 语义生成回复（ACK_RESULT / ACK_SESSION_INIT），
 *      在回复入队时就把 HEAD/SEQ/TYPE/LEN/PAYLOAD/CRC8 组成完整帧，调用方用 get() 取出即发。
 *
 * 设计约束：
 *   1. 回复走定长环形队列，与 Log / Dat 采用同一套 add / get / clear 语义；
 *   2. 本类在 get() 出口给出可直接发送的 ACK 帧字节，帧长与 CRC8 一律来自 Protocol.h；
 *   3. 去重表与会话状态只在本类维护，调用方不保留副本；
 *   4. add() 在事件出队后的主循环里调用，回复队列用临界区保护。
 */
#ifndef COMMAND_H
#define COMMAND_H

#include <Arduino.h>
#include <freertos/FreeRTOS.h>

#include "../../Protocol.h"

// 待发回复队列容量（条）。一帧 CMD 只产生一条 ACK，8 条足够。
constexpr uint8_t CMD_REPLY_CAPACITY = 8;
// CMD 去重窗口（条），覆盖最近 64 条已执行命令的 SEQ。
constexpr uint8_t CMD_DEDUPLICATION_SIZE = 64;
// 一条完整 ACK 帧 = HEAD + SEQ + TYPE + LEN + 2 B PAYLOAD + CRC8。
constexpr uint8_t CMD_REPLY_FRAME_BYTES =
    JiangLingProtocol::FRAME_OVERHEAD_BYTES + JiangLingProtocol::ACK_PAYLOAD_LEN;

class Command {
  public:
    // 命令主体回调：必须返回 ACK_ST_* 状态码，本类据此组 ACK。
    // seq 是这一帧 CMD 的 SEQ，原样回传（0xC8 的延后完成回执要用它）；
    // context 为 setActionHandler 注入的上下文，原样回传；
    // payload/payloadLength 已通过 CRC 与 payloadLengthValid() 校验，
    // 长度可能为 0，实现方不得在 payloadLength==0 时读 payload[0]。
    // 该回调在 add() 的调用线程（主循环任务）里同步执行，**不得阻塞**。
    using ActionHandler = uint8_t (*)(void* context,
                                     uint8_t seq,
                                     uint8_t cmdType,
                                     const uint8_t* payload,
                                     uint8_t payloadLength);

    Command();

    // 注入命令主体执行函数（RuntimeCmd 在 begin() 里注册一次）。
    // handler 为 nullptr 时命令一律回 ACK_ST_UNKNOWN；不阻塞，非线程安全，
    // 只能在开始收帧之前调用。
    void setActionHandler(ActionHandler handler, void* context);

    // 处理一帧 CMD（调用方已完成 CRC 校验与 LEN 合法性判定）。
    // seq: App 的帧序号，0 只允许 CMD_SESSION_INIT；
    // cmdType: 整字节 TYPE，未登记类型由 switch 兜底回 ACK_ST_UNKNOWN；
    // payload/payloadLength: 已校验的负载，不得为 nullptr（除长度为 0）。
    // 失败路径：SEQ=0 误用、会话未初始化、参数越界都只回对应 ACK 并写日志，不抛错。
    // 非阻塞，但会同步调用 ActionHandler；内部临界区只保护回复队列，**不可在 ISR 调用**。
    void add(uint8_t seq, uint8_t cmdType, const uint8_t* payload,
             uint8_t payloadLength);

    // 取出一条完整 ACK 帧（非阻塞）。
    // frame: 调用方提供的 CMD_REPLY_FRAME_BYTES 缓冲，写入 7 B 完整帧；
    // infoRound: 输出参数，true 表示这条 ACK 之后要补发一轮 DAT_INFO；
    // 返回：false = 队列为空，frame 内容不变；true = 已取出一条。
    // 内部用临界区保护队列，不可在 ISR 调用；同一时刻只允许一个出口取帧。
    bool get(uint8_t frame[CMD_REPLY_FRAME_BYTES], bool& infoRound);

    // 主动补发一条 ACK_RESULT（不属于某次 add() 的同步回复）。
    // 当前唯一用途：0xC8 提交后新网络就绪/超时，补一条 [0xC8][3] 或 [0xC8][5]。
    // 队列满返回 false，由调用方下一轮重试；非阻塞，不可在 ISR 调用。
    bool pushCompletion(uint8_t seq, uint8_t cmdType, uint8_t status);

    // 清空待发回复并把去重窗口复位（不改会话状态，也不重置 SEQ）。
    // 用于连接建立/断开前的队列清理；非阻塞，不可在 ISR 调用。
    void clear();

    // 连接建立/断开时调用：会话回到「未握手」状态。
    // 只置会话标志与清去重窗口，不清已排队的 ACK；非阻塞，不可在 ISR 调用。
    void resetSession();

    // 会话是否已通过 CMD_SESSION_INIT 握手。只读，可在任意任务调用（单字节读）。
    bool isSessionReady() const;

    // 因回复队列满被丢弃的 ACK 条数（自上次上电/clear 起）。只读，用于诊断。
    uint32_t droppedReplies() const;

  private:
    void handleSessionInit(uint8_t seq, const uint8_t* payload);
    void handleQuerySeq(uint8_t seq, const uint8_t* payload);
    bool isRecent(uint8_t seq) const;
    void remember(uint8_t seq);
    void clearDedup();
    bool pushReply(uint8_t seq, uint8_t replyType, uint8_t cmdType,
                   uint8_t status, uint8_t resetDone, bool infoRound);

    uint8_t _replyFrames[CMD_REPLY_CAPACITY][CMD_REPLY_FRAME_BYTES];
    bool _replyInfoRound[CMD_REPLY_CAPACITY];
    uint16_t _replyHead;
    uint16_t _replyTail;
    uint16_t _replyCount;
    uint32_t _droppedReplies;

    // 最近 64 条已成功处理的 CMD SEQ（只对 CMD 判重）。
    uint8_t _recentSeqs[CMD_DEDUPLICATION_SIZE];
    uint8_t _recentCount;
    uint8_t _recentIndex;

    // 会话状态：isInit 在 setup()/复位后为 true，收到第一条普通 CMD 后置 false。
    bool _isInit;
    bool _isSessionInitialized;

    ActionHandler _handler;
    void* _context;
    mutable portMUX_TYPE _mux = portMUX_INITIALIZER_UNLOCKED;
};

// 全局命令处理器：在 Cmd.cpp 中创建一次，供 WiFi 解析循环调用。
extern Command Cmd;

#endif  // COMMAND_H