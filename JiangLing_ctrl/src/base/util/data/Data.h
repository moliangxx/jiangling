/*
 * Data.h —— DAT 数据队列（JiangLing AI，V1.10 帧协议）
 *
 * 职责：
 *   板 → App 的所有 DAT 帧先在这里排队：调用方只给「TYPE + PAYLOAD」，
 *   队列内部拼接 SEQ（_nextSeq 自增回绕）、按 LEN 写帧头并算 CRC8，
 *   入队时就已经是一条完整可发送的帧；发送循环（BLE / WiFi）用 get() 取出字节直接写链路。
 *
 * 设计约束：
 *   1. 定长环形队列，满时丢弃新到的一条并累计 droppedCount()，绝不阻塞调用方；
 *      「未满 → 满」的那一刻只发一次「队列已满」提示（走 LOG 通道，不占本队列）；
 *   2. add() 可能来自主循环或其它任务，get() 由发送循环调用，统一用临界区保护；
 *   3. 组帧（HEAD + SEQ + TYPE + LEN + PAYLOAD + CRC8）只在本类做一次，调用方看不到中间态；
 *      帧长 = 5 + LEN，本类不另存长度表，只用 Protocol.h 的 payloadLengthValid() 拒绝非法长度；
 *   4. clear() 清空队列、丢弃计数并把 SEQ 归零（App 侧重连后会复位去重窗口，
 *      复位 SEQ 可以避免与旧序号撞车）。
 */
#ifndef DATA_H
#define DATA_H

#include <Arduino.h>
#include <freertos/FreeRTOS.h>

#include "../../Protocol.h"

// 队列容量（条）。DAT 多为 500 ms / 1 s 周期上报，32 条足够吸收一次卡顿。
constexpr uint16_t DAT_QUEUE_CAPACITY = 32;
// 单条 PAYLOAD 上限：取协议里最长的 DAT 帧（DAT_INFO_VERSION 20 B）。
constexpr uint8_t DAT_ITEM_MAX_PAYLOAD =
    JiangLingProtocol::DAT_INFO_VERSION_LEN;
// 单条完整帧上限 = HEAD + SEQ + TYPE + LEN + 最长 PAYLOAD + CRC8。
constexpr uint8_t DAT_FRAME_MAX_BYTES =
    JiangLingProtocol::FRAME_OVERHEAD_BYTES + DAT_ITEM_MAX_PAYLOAD;

class Data {
  public:
    Data();

    // 入队（非阻塞）。成功时本类立即用 HEAD/SEQ/TYPE/LEN/CRC8 组成完整帧。
    // type: 整字节 DAT TYPE（0x80/0x90/0xA0 等），必须是 payloadLengthValid() 认识的类型；
    // payload/length: 负载与字节数，长度必须等于该 TYPE 的允许值，且 length>0 时 payload 非空。
    // 返回：true = 已入队；false = TYPE 未知、长度不符、队列满或参数非法。
    // 失败时若因队列满，本次丢弃会计入 droppedCount()，并触发一次「队列已满」日志。
    // 内部用临界区保护，**不可在 ISR 调用**；可在任意任务调用，但应避免高频刷入。
    bool add(uint8_t type, const uint8_t* payload, uint8_t length);

    // 取出一条完整帧（非阻塞）。
    // frame: 调用方提供的 DAT_FRAME_MAX_BYTES 缓冲；length: 输出帧总长（5 + LEN）。
    // 返回：false = 队列空，frame/length 不变；true = 已写入一条可直接发送的帧。
    // 内部用临界区保护，**不可在 ISR 调用**；同一时刻只允许一个出口调用。
    bool get(uint8_t frame[DAT_FRAME_MAX_BYTES], uint8_t& length);

    // 清空队列与丢弃计数，并把 SEQ 归零。
    // 用于连接重置/模式切换；归零后 App 的去重窗口不会与旧序号撞车。
    // 内部用临界区，**不可在 ISR 调用**。
    void clear();

    // 当前待发送条数。只读快照，可在任意任务调用（临界区内读，结果可能瞬时过期）。
    uint16_t pendingCount() const;

    // 因队列满而丢弃的条数（统计自上次上电/clear）。只读，用于诊断。
    uint32_t droppedCount() const;

  private:
    uint8_t _frames[DAT_QUEUE_CAPACITY][DAT_FRAME_MAX_BYTES];
    uint8_t _lengths[DAT_QUEUE_CAPACITY];
    uint16_t _head;
    uint16_t _tail;
    uint16_t _count;
    uint32_t _dropped;
    uint8_t _nextSeq;
    mutable portMUX_TYPE _mux = portMUX_INITIALIZER_UNLOCKED;
};

// 全局数据队列：在 Data.cpp 中创建一次，供 BLE / WiFi 发送循环共用。
extern Data Dat;

#endif  // DATA_H
