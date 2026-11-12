
#include "Cmd.h"

#include <cstring>

#include "../log/Log.h"

using namespace JiangLingProtocol;

Command::Command()
    : _replyFrames{},
      _replyInfoRound{},
      _replyHead(0),
      _replyTail(0),
      _replyCount(0),
      _droppedReplies(0),
      _recentSeqs{},
      _recentCount(0),
      _recentIndex(0),
      _isInit(true),
      _isSessionInitialized(false),
      _handler(nullptr),
      _context(nullptr) {
}

void Command::setActionHandler(ActionHandler handler, void* context) {
  _handler = handler;
  _context = context;
}

void Command::add(uint8_t seq, uint8_t cmdType, const uint8_t* payload,
                  uint8_t payloadLength) {
  if (cmdType == CMD_SESSION_INIT) {
    handleSessionInit(seq, payload);
    return;
  }

  if (seq == SEQ_INIT) {
    // SEQ=0 只属于 CMD_SESSION_INIT；B 类日志已随蓝牙移除停用，这里只回 ACK，不再记日志。
    pushReply(seq, ACK_RESULT, cmdType, ACK_ST_ERROR, 0, false);
    return;
  }

  if (cmdType == CMD_QUERY_SEQ) {
    handleQuerySeq(seq, payload);
    return;
  }

  switch (cmdType) {
    case CMD_STOP:
    case CMD_FORWARD:
    case CMD_BACK:
    case CMD_LEFT:
    case CMD_RIGHT:
    case CMD_PITCH:
    case CMD_YAW:
    case CMD_REQ_INFO:
    case CMD_AUDIO_TEST:
    case CMD_VOLUME:
    case CMD_RECORD:
    // 初始化命令与运行期命令走同一条 WiFi 链路，统一交给 RuntimeCmd 执行。
    case CMD_WIFI_MODE:
    case CMD_WIFI_STA_CONFIG:
    case CMD_WIFI_AP_CONFIG:
    case CMD_WIFI_STATUS:
    case CMD_INIT_COMMIT:
    case CMD_INIT_STATUS:
    case CMD_INIT_FACTORY_RESET:
      break;
    case CMD_BLE_CONTROL:
      // 0xCB 已废弃（2026-11-11 蓝牙整体移除）：编号保留不复用，
      // 任意 LEN/PAYLOAD 一律回 ACK 4，不执行、不写日志。
      pushReply(seq, ACK_RESULT, cmdType, ACK_ST_UNKNOWN, 0, false);
      return;
    case CMD_LEFT_SHIFT:
    case CMD_RIGHT_SHIFT:
      // 本固件按差速轮实现，麦轮横移命令不在支持列表内。
      pushReply(seq, ACK_RESULT, cmdType, ACK_ST_UNKNOWN, 0, false);
      JL_LOGW(LOG_MOD_SYSTEM, WIFI_LOG_LEN_INVALID,
                  LOG_SERIAL_TEXT("CMD: mecanum shift CMD is not implemented."));
      return;
    case CMD_REQ_RECORD:
      // 录音改由 CMD_RECORD 触发；本命令只保留编号，不执行。
      pushReply(seq, ACK_RESULT, cmdType, ACK_ST_UNKNOWN, 0, false);
      JL_LOGW(LOG_MOD_SYSTEM, WIFI_LOG_LEN_INVALID,
                  LOG_SERIAL_TEXT("CMD: CMD_REQ_RECORD is not implemented."));
      return;
    default:
      // 未登记 TYPE 在解析阶段就整帧丢弃，这里只是防御性兜底。
      pushReply(seq, ACK_RESULT, cmdType, ACK_ST_UNKNOWN, 0, false);
      JL_LOGW(LOG_MOD_SYSTEM, WIFI_LOG_LEN_INVALID,
                  LOG_SERIAL_TEXT("CMD: unknown CMD item."));
      return;
  }

  // 音频自检的参数只允许 0/1/2；其他值明确回 ERROR，不能静默丢弃。
  if (cmdType == CMD_AUDIO_TEST && payload[0] > AUDIO_TEST_PARAM_MAX) {
    pushReply(seq, ACK_RESULT, cmdType, ACK_ST_ERROR, 0, false);
    JL_LOGW(LOG_MOD_SYSTEM, WIFI_LOG_CONFIG_INVALID,
                LOG_SERIAL_TEXT("CMD: CMD_AUDIO_TEST param is invalid (0/1/2)."));
    return;
  }

  // 音量只允许 0~100，录音只允许 1~10 秒；越界回 ERROR(5) 并写日志。
  if (cmdType == CMD_VOLUME && payload[0] > VOLUME_PARAM_MAX) {
    pushReply(seq, ACK_RESULT, cmdType, ACK_ST_ERROR, 0, false);
    JL_LOGW(LOG_MOD_SYSTEM, WIFI_LOG_CONFIG_INVALID,
                LOG_SERIAL_TEXT("CMD: CMD_VOLUME param exceeds 100."));
    return;
  }
  if (cmdType == CMD_RECORD &&
      (payload[0] < RECORD_SECONDS_MIN || payload[0] > RECORD_SECONDS_MAX)) {
    pushReply(seq, ACK_RESULT, cmdType, ACK_ST_ERROR, 0, false);
    JL_LOGW(LOG_MOD_SYSTEM, WIFI_LOG_CONFIG_INVALID,
                LOG_SERIAL_TEXT("CMD: CMD_RECORD seconds out of range (1~10)."));
    return;
  }

  // 只有前进/后退/左转/右转带 0~100 的速度参数；STOP、云台与横移不在范围内。
  const bool isMotionCmd = cmdType >= CMD_FORWARD && cmdType <= CMD_RIGHT;
  if (isMotionCmd && payload[0] > MOTION_LEVEL_MAX) {
    pushReply(seq, ACK_RESULT, cmdType, ACK_ST_ERROR, 0, false);
    JL_LOGW(LOG_MOD_SYSTEM, WIFI_LOG_CONFIG_INVALID,
                LOG_SERIAL_TEXT("CMD: motion LEVEL exceeds 100."));
    return;
  }

  if (!_isSessionInitialized && cmdType != CMD_STOP) {
    pushReply(seq, ACK_RESULT, cmdType, ACK_ST_CANNOT, 0, false);
    return;
  }

  // 0xC8~0xCA 是配网提交类命令，不参与去重（按命令.md 的口径）。
  const bool provisioning =
      cmdType >= CMD_INIT_COMMIT && cmdType <= CMD_INIT_FACTORY_RESET;
  if (!provisioning && isRecent(seq)) {
    pushReply(seq, ACK_RESULT, cmdType, ACK_ST_DONE, 0, false);
    return;
  }

  if (!provisioning) {
    remember(seq);
  }
  _isInit = false;

  // 命令主体（音频/屏幕/运动等）由 RuntimeCmd 提供，本类只关心协议状态。
  const uint8_t status =
      _handler != nullptr ? _handler(_context, seq, cmdType, payload, payloadLength)
                          : static_cast<uint8_t>(ACK_ST_DONE);
  pushReply(seq, ACK_RESULT, cmdType, status, 0, false);
}

bool Command::pushCompletion(uint8_t seq, uint8_t cmdType, uint8_t status) {
  // 队列满就返回 false，交给调用方下一轮重试；不做阻塞等待。
  return pushReply(seq, ACK_RESULT, cmdType, status, 0, false);
}

bool Command::get(uint8_t frame[CMD_REPLY_FRAME_BYTES], bool& infoRound) {
  bool available = false;
  portENTER_CRITICAL(&_mux);
  if (_replyCount > 0) {
    std::memcpy(frame, _replyFrames[_replyHead], CMD_REPLY_FRAME_BYTES);
    infoRound = _replyInfoRound[_replyHead];
    _replyHead = static_cast<uint16_t>(
        (_replyHead + 1) % CMD_REPLY_CAPACITY);
    --_replyCount;
    available = true;
  }
  portEXIT_CRITICAL(&_mux);
  return available;
}

void Command::clear() {
  portENTER_CRITICAL(&_mux);
  _replyHead = 0;
  _replyTail = 0;
  _replyCount = 0;
  portEXIT_CRITICAL(&_mux);
  clearDedup();
}

void Command::resetSession() {
  _isSessionInitialized = false;
}

bool Command::isSessionReady() const {
  return _isSessionInitialized;
}

uint32_t Command::droppedReplies() const {
  uint32_t dropped = 0;
  portENTER_CRITICAL(&_mux);
  dropped = _droppedReplies;
  portEXIT_CRITICAL(&_mux);
  return dropped;
}

void Command::handleSessionInit(uint8_t seq, const uint8_t* payload) {
  if (seq != SEQ_INIT) {
    pushReply(seq, ACK_RESULT, CMD_SESSION_INIT, ACK_ST_ERROR, 0, false);
    return;
  }

  if (payload[0] != PROTOCOL_VERSION) {
    _isSessionInitialized = false;
    pushReply(SEQ_INIT, ACK_SESSION_INIT, CMD_SESSION_INIT,
              ACK_ST_ERROR, 0, false);
    JL_LOGW(LOG_MOD_SYSTEM, SYS_LOG_SESSION_VERSION_MISMATCH,
                LOG_SERIAL_TEXT("CMD: protocol version mismatch."));
    return;
  }

  const bool resetDone = _isInit;
  if (resetDone) {
    // 只清 CMD 去重表；LOG/DAT 的 SEQ 计数器不归零，避免撞上 App 端去重窗口。
    clearDedup();
  }
  _isSessionInitialized = true;

  pushReply(SEQ_INIT, ACK_SESSION_INIT, CMD_SESSION_INIT,
            ACK_ST_DONE, resetDone ? 1 : 0, true);
  // 握手 ACK 之后紧接一轮 DAT_INFO（版本→电源→存储），由发送层在发完 ACK 后触发。
  JL_LOGI(LOG_MOD_SYSTEM, SYS_LOG_SESSION_INIT_OK,
           LOG_SERIAL_TEXT("CMD_SESSION_INIT ok."));
}

void Command::handleQuerySeq(uint8_t seq, const uint8_t* payload) {
  if (!_isSessionInitialized) {
    pushReply(seq, ACK_RESULT, CMD_QUERY_SEQ, ACK_ST_CANNOT, 0, false);
    return;
  }

  const uint8_t targetSeq = payload[0];
  const uint8_t status = isRecent(targetSeq) ? ACK_ST_YES : ACK_ST_NO;
  pushReply(seq, ACK_RESULT, CMD_QUERY_SEQ, status, 0, false);

  if (!isRecent(seq)) {
    remember(seq);
    _isInit = false;
  }
  JL_LOGD(LOG_MOD_SYSTEM,
            status == ACK_ST_YES ? SYS_LOG_QUERY_SEQ_HIT : SYS_LOG_QUERY_SEQ_MISS,
            LOG_SERIAL_TEXT("CMD_QUERY_SEQ handled."));
}

bool Command::isRecent(uint8_t seq) const {
  for (uint8_t index = 0; index < _recentCount; ++index) {
    if (_recentSeqs[index] == seq) {
      return true;
    }
  }
  return false;
}

void Command::remember(uint8_t seq) {
  _recentSeqs[_recentIndex] = seq;
  _recentIndex = static_cast<uint8_t>(
      (_recentIndex + 1) % CMD_DEDUPLICATION_SIZE);
  if (_recentCount < CMD_DEDUPLICATION_SIZE) {
    ++_recentCount;
  }
}

void Command::clearDedup() {
  _recentCount = 0;
  _recentIndex = 0;
}

bool Command::pushReply(uint8_t seq, uint8_t replyType, uint8_t cmdType,
                        uint8_t status, uint8_t resetDone, bool infoRound) {
  // ACK_RESULT：PAYLOAD = [被应答 CMD 的 TYPE, 状态码]；
  // ACK_SESSION_INIT：PAYLOAD = [状态码, resetDone]。
  // 帧在这里一次组好，发送层不再拼头、不再算 CRC。
  uint8_t frame[CMD_REPLY_FRAME_BYTES];
  beginFrame(frame, seq, replyType, ACK_PAYLOAD_LEN);
  if (replyType == ACK_SESSION_INIT) {
    frame[4] = status;
    frame[5] = resetDone;
  } else {
    frame[4] = cmdType;
    frame[5] = status;
  }
  finishFrame(frame, ACK_PAYLOAD_LEN);

  portENTER_CRITICAL(&_mux);
  if (_replyCount == CMD_REPLY_CAPACITY) {
    ++_droppedReplies;
    portEXIT_CRITICAL(&_mux);
    return false;
  }
  std::memcpy(_replyFrames[_replyTail], frame, CMD_REPLY_FRAME_BYTES);
  _replyInfoRound[_replyTail] = infoRound;

  _replyTail = static_cast<uint16_t>(
      (_replyTail + 1) % CMD_REPLY_CAPACITY);
  ++_replyCount;
  portEXIT_CRITICAL(&_mux);
  return true;
}

Command Cmd;
