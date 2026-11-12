/*
 * main/runtime/RuntimeCmd.cpp —— 命令执行与 DAT 调度
 *
 * 运行期命令与初始化命令（0xC4~0xCA）共用这一条 WiFi 链路：
 * UDP 8081 收、TCP 8080 回。蓝牙已于 2026-11-11 整体移除，不存在第二条执行路径。
 */
#include "RuntimeCmd.h"

#include <cstring>

#include "../../base/Protocol.h"
#include "../../base/util/cmd/Cmd.h"
#include "../../base/util/data/Data.h"
#include "../../base/util/log/Log.h"
#include "../audio/Mic.h"
#include "../audio/Record.h"
#include "../audio/Speaker.h"
#include "../screen/TFT.h"
#include "../sensor/Sensor.h"
#include "../wife/Voice.h"
#include "../wife/Wifi.h"

// 行为取舍开关（可单独关闭，不写死在逻辑里）：
// 通话期间收到 CMD_RECORD(0xD2) 回 6（忙），不打断正在进行的语音通话。
#ifndef RECORD_BLOCK_DURING_VOICE
#define RECORD_BLOCK_DURING_VOICE 1
#endif

using namespace JiangLingProtocol;

namespace {
String asText(uint8_t value) {
  return String(static_cast<unsigned int>(value));
}

// 字节 → KB 上报；0xFFFE 为“极大值”占位，0xFFFF 保留给“无效”。
uint16_t memoryKb(size_t bytes) {
  const size_t kb = bytes / 1024;
  return kb >= DAT_INVALID_U16 ? static_cast<uint16_t>(DAT_INVALID_U16 - 1)
                               : static_cast<uint16_t>(kb);
}
}  // namespace

void RuntimeCmd::begin() {
  // 命令只有 WiFi 一条入口，Cmd 的回调也只注册这一份。
  Cmd.setActionHandler(&RuntimeCmd::ActionThunk, this);
  // 待办动作必须显式初始化：静态零初始化会让 _pendingAudioAction 等于 0，
  // 而 0 正是 AUDIO_TEST_STARTUP_CHIME，首条 ACK 之后会误播上电提示音。
  _pendingAudioAction = 0xFF;
  _pendingRecordSeconds = 0;
  _infoRoundPending = false;
  _pendingDisplayType = 0;
  _pendingDisplayValue = 0;
  _pendingDisplayValid = false;
  _lastBatAtMs = 0;
  _lastBatEdgeAtMs = 0;
  _lastWifiStatusAtMs = 0;
  _lastWheelAtMs = 0;
  _lastWheelEdgeAtMs = 0;
  _wheelPrimed = false;
  _lastWheelRaw = 0;
  _initCommitQueued = false;
  _initCommitSubmitted = false;
  _initCommitPending = false;
  _initCommitDone = false;
  _initCommitMode = 0;
  _initCommitSeq = 0;
  _initCommitStartMs = 0;
  _initResultPending = false;
  _initResultStatus = ACK_ST_RECEIVED;
  _restartAtMs = 0;
  _motion.begin(millis());
  _gimbal.begin(millis());
}

void RuntimeCmd::update() {
  const uint32_t nowMs = millis();
  // 传感器采样与窗口结算独立于网络：断链期间也要持续刷新，重连后首帧即真实值。
  Sensor::update(nowMs);
  // 超时自停与链路无关：断链时更要保证车停下来。
  if (_motion.update(nowMs)) {
    JL_LOGD(LOG_MOD_MOTOR, MOTOR_LOG_TIMEOUT_AUTO_STOP,
            LOG_SERIAL_TEXT("Motion: no vector frame in time; auto stop."));
  }
  if (_gimbal.update(nowMs)) {
    JL_LOGD(LOG_MOD_MOTOR, MOTOR_LOG_TIMEOUT_AUTO_STOP,
            LOG_SERIAL_TEXT("Gimbal: no vector frame in time; stop rotating."));
  }
  pollInitCommit(nowMs);
  sendDatIfDue(nowMs);
}

void RuntimeCmd::stopAll() {
  _motion.stop(millis());
  _gimbal.stop(millis());
  // 清掉 ACK 之后的待办动作，避免断链后还去启动录音/播放。
  _pendingAudioAction = 0xFF;
  _pendingRecordSeconds = 0;
  _infoRoundPending = false;
}

uint8_t RuntimeCmd::ActionThunk(void* context, uint8_t seq, uint8_t cmdType,
                                const uint8_t* payload, uint8_t payloadLength) {
  return static_cast<RuntimeCmd*>(context)->execute(
      seq, cmdType, payload, payloadLength);
}

uint8_t RuntimeCmd::execute(uint8_t seq, uint8_t cmdType,
                            const uint8_t* payload, uint8_t payloadLength) {
  // 协议层校验（SEQ、会话、参数范围、去重）已在 Command::add() 完成，
  // 这里只执行命令主体并记录 ACK 之后要做的动作。
  // 初始化命令先回 ACK 再动作：需要拆网络的部分一律丢给 update()。
  uint8_t status = ACK_ST_DONE;
  uint8_t value = 0;
  uint8_t audioAction = 0xFF;  // 0xFF = 不触发播放
  uint8_t recordSeconds = 0;

  switch (cmdType) {
    case CMD_STOP:
      // 停止最高优先：立即停，清目标与两个流式计时，不等超时。
      _motion.stop(millis());
      _gimbal.stop(millis());
      status = ACK_ST_DONE;
      break;
    case CMD_FORWARD:
    case CMD_BACK:
    case CMD_LEFT:
    case CMD_RIGHT: {
      const uint8_t level = payload[0];
      value = level;
      _motion.discrete(cmdType, level, millis());
      status = level == 0 ? ACK_ST_DONE : ACK_ST_RECEIVED;
      break;
    }
    case CMD_MOVE_VECTOR: {
      // 差速轮盘：drive 正=前进、turn 正=左转；越界由控制类夹取。
      const int drive = static_cast<int8_t>(payload[0]);
      const int turn = static_cast<int8_t>(payload[1]);
      _motion.drive(drive, turn, millis());
      value = static_cast<uint8_t>(drive < 0 ? -drive : drive);
      status = (drive == 0 && turn == 0) ? ACK_ST_DONE : ACK_ST_RECEIVED;
      break;
    }
    case CMD_GIMBAL_VECTOR: {
      const int pan = static_cast<int8_t>(payload[0]);
      const int tilt = static_cast<int8_t>(payload[1]);
      _gimbal.move(pan, tilt, millis());
      value = static_cast<uint8_t>(pan < 0 ? -pan : pan);
      status = _gimbal.isReady()
                   ? ((pan == 0 && tilt == 0) ? ACK_ST_DONE : ACK_ST_RECEIVED)
                   : ACK_ST_CANNOT;
      break;
    }
    case CMD_PITCH:
      // 无云台硬件：按命令.md 口径回 CANNOT，不把角度当成已执行。
      _gimbal.setPitchRaw(payload[0]);
      value = payload[0];
      status = _gimbal.isReady() ? ACK_ST_DONE : ACK_ST_CANNOT;
      break;
    case CMD_YAW:
      value = payload[0];
      _gimbal.setYawRaw(payload[0]);
      status = _gimbal.isReady() ? ACK_ST_DONE : ACK_ST_CANNOT;
      break;
    case CMD_REQ_INFO:
      // DAT_INFO 在 ACK 之后补发一轮，由 applyPendingActions() 触发。
      _infoRoundPending = true;
      status = ACK_ST_DONE;
      break;
    case CMD_AUDIO_TEST:
      value = payload[0];
      audioAction = payload[0];
      if (audioAction == AUDIO_TEST_STOP) {
        // 播放器是“排队写 DMA”模型，没有待播音时停止是空操作；ACK 语义保持不变。
        status = ACK_ST_DONE;
      } else if (!Speaker::isReady()) {
        // 音频初始化失败时明确回 CANNOT，并保持不播放（不静默丢弃）。
        status = ACK_ST_CANNOT;
        audioAction = 0xFF;
        JL_LOGW(LOG_MOD_AUDIO, AUDIO_LOG_TEST_AUDIO_NOT_READY,
                LOG_SERIAL_TEXT("CMD_AUDIO_TEST rejected; audio not initialized."));
      } else if (Record::isBusy()) {
        status = ACK_ST_CANNOT;
        audioAction = 0xFF;
        JL_LOGW(LOG_MOD_AUDIO, AUDIO_LOG_TEST_RECORD_BUSY,
                LOG_SERIAL_TEXT("CMD_AUDIO_TEST rejected; recording/playback running."));
      } else {
        status = ACK_ST_DONE;
      }
      break;
    case CMD_VOLUME:
      value = payload[0];
      if (!Speaker::isReady()) {
        status = ACK_ST_CANNOT;
        JL_LOGW(LOG_MOD_SPEAKER, SPK_LOG_NOT_READY,
                LOG_SERIAL_TEXT("CMD_VOLUME rejected; speaker is not ready."));
      } else {
        Speaker::setVolume(payload[0]);
        status = ACK_ST_DONE;
      }
      break;
    case CMD_RECORD:
      value = payload[0];
      if (!Record::isReady()) {
        status = ACK_ST_CANNOT;
        JL_LOGW(LOG_MOD_RECORD, REC_LOG_NOT_READY,
                LOG_SERIAL_TEXT("CMD_RECORD rejected; recording buffer not ready."));
      } else if (!Mic::isReady()) {
        status = ACK_ST_CANNOT;
        JL_LOGW(LOG_MOD_RECORD, REC_LOG_MIC_NOT_READY,
                LOG_SERIAL_TEXT("CMD_RECORD rejected; microphone is not ready."));
      } else if (Record::isBusy()) {
        status = ACK_ST_CANNOT;
        JL_LOGW(LOG_MOD_RECORD, REC_LOG_BUSY,
                LOG_SERIAL_TEXT("CMD_RECORD rejected; recording/playback running."));
#if RECORD_BLOCK_DURING_VOICE
      } else if (Voice::isActive()) {
        // 通话优先：录音会抢麦克风并打断对讲，直接回忙，不动通话状态。
        status = ACK_ST_CANNOT;
        JL_LOGW(LOG_MOD_RECORD, REC_LOG_BUSY,
                LOG_SERIAL_TEXT("CMD_RECORD rejected; voice call in progress."));
#endif
      } else if (payload[0] > Record::maxSeconds()) {
        status = ACK_ST_CANNOT;
        JL_LOGW(LOG_MOD_RECORD, REC_LOG_TOO_LONG,
                LOG_SERIAL_TEXT("CMD_RECORD seconds exceed buffer capacity."));
      } else {
        status = ACK_ST_DONE;
        recordSeconds = payload[0];
      }
      break;
    case CMD_WIFI_MODE:
      // 只预检模式范围，不立即换网、不校验凭据（凭据可晚于 0xC4 下发）；真正生效在 0xC8。
      status = Wifi.stageMode(static_cast<WifiMode>(payload[0]))
                   ? ACK_ST_DONE
                   : ACK_ST_ERROR;
      break;
    case CMD_WIFI_STA_CONFIG:
      status = Wifi.stageStaCredentials(payload, payloadLength)
                   ? ACK_ST_DONE
                   : ACK_ST_ERROR;
      break;
    case CMD_WIFI_AP_CONFIG:
      status = Wifi.stageApCredentials(payload, payloadLength)
                   ? ACK_ST_DONE
                   : ACK_ST_ERROR;
      break;
    case CMD_WIFI_STATUS:
      status = Wifi.isLanReady() ? ACK_ST_DONE : ACK_ST_ERROR;
      break;
    case CMD_INIT_COMMIT:
      // 执行中重复下发回 6；不在 UDP 解析栈里换网，只置待办，ACK 之后再 applyMode。
      if (payloadLength != 2 || payload[1] != INIT_COMMIT_RESERVED ||
          payload[0] > WIFI_CFG_MODE_MAX) {
        status = ACK_ST_ERROR;
      } else if (_initCommitQueued || _initCommitPending) {
        status = ACK_ST_CANNOT;
      } else if (!Wifi.stageMode(static_cast<WifiMode>(payload[0]))) {
        status = ACK_ST_ERROR;  // 模式越界
      } else {
        _initCommitQueued = true;
        _initCommitMode = payload[0];
        _initCommitSeq = seq;
        _initCommitDone = false;
        _initResultPending = false;
        status = ACK_ST_RECEIVED;
      }
      break;
    case CMD_INIT_STATUS:
      // 3 = 已完成；0 = 已提交但未就绪；4 = 从未提交过 0xC8。
      status = _initCommitDone
                   ? ACK_ST_DONE
                   : (_initCommitSubmitted ? ACK_ST_RECEIVED : ACK_ST_UNKNOWN);
      break;
    case CMD_INIT_FACTORY_RESET:
      if (payloadLength != 1 || payload[0] != INIT_FACTORY_MAGIC) {
        status = ACK_ST_ERROR;
      } else {
        Wifi.factoryReset();
        _restartAtMs = millis() + INIT_RESTART_DELAY_MS;
        if (_restartAtMs == 0) {
          _restartAtMs = 1;  // 0 保留为「不重启」
        }
        status = ACK_ST_DONE;
      }
      break;
    default:
      status = ACK_ST_UNKNOWN;
      break;
  }

  _pendingAudioAction = audioAction;
  _pendingRecordSeconds = recordSeconds;
  _pendingDisplayType = cmdType;
  _pendingDisplayValue = value;
  _pendingDisplayValid = status != ACK_ST_CANNOT;
  return status;
}

void RuntimeCmd::applyPendingActions() {
  // 音频自检：先回 ACK，再让播放器直接出声。Speaker::playTestTone 内部用 0 tick
  // 写 DMA，DMA 满时返回 0，不阻塞主循环。
  if (_pendingAudioAction == AUDIO_TEST_STARTUP_CHIME) {
    Speaker::playTestTone(880, 200);
  } else if (_pendingAudioAction == AUDIO_TEST_1KHZ) {
    Speaker::playTestTone(1000, 200);
  }
  _pendingAudioAction = 0xFF;

  // 录音：ACK 已发出，这里才开始采集；Record::update() 在 loop() 里推进，
  // 录满后自动回放，不需要 App 再发命令。
  if (_pendingRecordSeconds > 0) {
    const Record::StartCode startCode =
        Record::start(static_cast<uint16_t>(_pendingRecordSeconds));
    switch (startCode) {
      case Record::START_OK:
        break;
      case Record::START_BUSY:
        JL_LOGW(LOG_MOD_RECORD, REC_LOG_START_BUSY,
                LOG_SERIAL_TEXT("CMD_RECORD start failed; busy."));
        break;
      case Record::START_NO_MIC:
        JL_LOGW(LOG_MOD_RECORD, REC_LOG_START_NO_MIC,
                LOG_SERIAL_TEXT("CMD_RECORD start failed; no microphone."));
        break;
      case Record::START_TOO_LONG:
        JL_LOGW(LOG_MOD_RECORD, REC_LOG_START_TOO_LONG,
                LOG_SERIAL_TEXT("CMD_RECORD start failed; too long."));
        break;
      case Record::START_NO_BUFFER:
        JL_LOGW(LOG_MOD_RECORD, REC_LOG_START_NO_BUFFER,
                LOG_SERIAL_TEXT("CMD_RECORD start failed; no buffer."));
        break;
      default:
        break;
    }
  }
  _pendingRecordSeconds = 0;

  // DAT_INFO 只在握手后和 CMD_REQ_INFO 时各发一轮，不做周期发送。
  if (_infoRoundPending) {
    _infoRoundPending = false;
    sendDeviceInfoRound();
  }

  if (!_pendingDisplayValid) {
    return;
  }
  _pendingDisplayValid = false;

  String display = String("CMD ") + asText(itemOf(_pendingDisplayType));
  // 只有带数值参数的命令才显示 LEVEL/ANGLE；初始化命令与纯无参命令只显示 item。
  const bool hasParam =
      _pendingDisplayType == CMD_FORWARD || _pendingDisplayType == CMD_BACK ||
      _pendingDisplayType == CMD_LEFT || _pendingDisplayType == CMD_RIGHT ||
      _pendingDisplayType == CMD_MOVE_VECTOR ||
      _pendingDisplayType == CMD_GIMBAL_VECTOR ||
      _pendingDisplayType == CMD_PITCH || _pendingDisplayType == CMD_YAW ||
      _pendingDisplayType == CMD_AUDIO_TEST || _pendingDisplayType == CMD_VOLUME ||
      _pendingDisplayType == CMD_RECORD;
  if (hasParam) {
    const bool isGimbal =
        _pendingDisplayType == CMD_PITCH || _pendingDisplayType == CMD_YAW;
    display += isGimbal ? String(" ANGLE=") : String(" LEVEL=");
    display += asText(_pendingDisplayValue);
  }
  TFT.showCommand(display);
}

void RuntimeCmd::pollInitCommit(uint32_t nowMs) {
  // 0xCA：ACK 已随上一轮 TCP 写出，这里再重启，保证 App 能收到回执。
  if (_restartAtMs != 0 && static_cast<int32_t>(nowMs - _restartAtMs) >= 0) {
    ESP.restart();
  }

  // 0xC8：ACK 已经发出，现在才真正换网；换网会拆掉回程 TCP。
  if (_initCommitQueued) {
    _initCommitQueued = false;
    _initCommitSubmitted = true;
    _initCommitPending = true;
    _initCommitStartMs = nowMs;
    if (!Wifi.commitInit(static_cast<WifiMode>(_initCommitMode))) {
      _initCommitPending = false;
      _initResultStatus = ACK_ST_ERROR;
      _initResultPending = true;
    }
  }

  if (_initCommitPending) {
    if (Wifi.isLanReady()) {
      _initCommitPending = false;
      _initCommitDone = true;
      _initResultStatus = ACK_ST_DONE;
      _initResultPending = true;
      JL_LOGI(LOG_MOD_SYSTEM, WIFI_LOG_CONFIG_APPLIED,
              LOG_SERIAL_TEXT("Init: new network ready; init complete."));
    } else if (static_cast<uint32_t>(nowMs - _initCommitStartMs) >=
               INIT_COMMIT_TIMEOUT_MS) {
      _initCommitPending = false;
      _initResultStatus = ACK_ST_ERROR;
      _initResultPending = true;
      JL_LOGW(LOG_MOD_SYSTEM, WIFI_LOG_CONNECT_TIMEOUT,
              LOG_SERIAL_TEXT("Init: no IP in 20 s; init failed, link kept."));
    }
  }

  // 换网后 App 会重新连 TCP；等回程重建再补发，避免被 Cmd.clear() 冲掉。
  if (_initResultPending && Wifi.hasTcpClient()) {
    if (Cmd.pushCompletion(_initCommitSeq, CMD_INIT_COMMIT, _initResultStatus)) {
      _initResultPending = false;
    }
  }
}

void RuntimeCmd::sendDatIfDue(uint32_t nowMs) {
  // 没有 TCP 客户端时不排队：重连后先补一条最新基线。
  if (!Wifi.hasTcpClient()) {
    _batPrimed = false;
    _wifiStatusPrimed = false;
    _wheelPrimed = false;
    return;
  }

  // DAT_WIFI_STATUS（0x86）兼作回程心跳：只要 TCP 客户端在就发，**不要求会话已初始化**，
  // AP 模式同样发。客户端一连上立即一帧，之后每 5 s 一帧，mode/state 跳变立即补帧；
  // 因此任意 9 s 窗口内至少有一帧回程数据（见《数据.md》§2.2 / §5）。
  const uint8_t modeNow = static_cast<uint8_t>(Wifi.mode());
  const uint8_t stateNow = static_cast<uint8_t>(Wifi.state());
  const bool wifiDue =
      !_wifiStatusPrimed ||
      static_cast<uint32_t>(nowMs - _lastWifiStatusAtMs) >= DAT_SLOW_PERIOD_MS;
  const bool wifiChanged = _wifiStatusPrimed &&
                           (modeNow != _lastWifiMode || stateNow != _lastWifiState);
  if (wifiDue || wifiChanged) {
    sendWifiStatus(nowMs);
  }

  // 其余 DAT 仍以会话就绪为前提（0x80 等）。
  if (!Cmd.isSessionReady()) {
    _batPrimed = false;
    _wheelPrimed = false;
    return;
  }

  // DAT_BAT：PAYLOAD = [电压 u16 大端(0.01 V)]；电量百分比由 App 换算。
  // 电压来自 GPIO8 的 Voltage Sensor（5:1 分压 + 滑动平均），未采到样本时为 0xFFFF。
  const uint16_t batRaw = Sensor::batteryRaw();
  const bool batDue =
      !_batPrimed ||
      static_cast<uint32_t>(nowMs - _lastBatAtMs) >= DAT_SLOW_PERIOD_MS;
  const uint16_t batDelta = batRaw > _lastBatRaw ? batRaw - _lastBatRaw
                                                 : _lastBatRaw - batRaw;
  const bool batEdge =
      _batPrimed && batDelta >= DAT_BAT_EDGE_RAW &&
      static_cast<uint32_t>(nowMs - _lastBatEdgeAtMs) >= DAT_EDGE_MIN_INTERVAL_MS;
  if (batDue || batEdge) {
    uint8_t payload[DAT_BAT_LEN];
    writeU16(payload, batRaw);
    Dat.add(DAT_BAT, payload, DAT_BAT_LEN);
    _lastBatAtMs = nowMs;
    _lastBatEdgeAtMs = nowMs;
    _lastBatRaw = batRaw;
    _batPrimed = true;
  }

  // DAT_WHEEL：PAYLOAD = [左轮 int16][右轮 int16]，单位 0.01 r/s。
  // 只接左侧（左后轮）一路测速，右轮恒填 -1（0xFFFF）表示该轮无效。
  if (Sensor::speedReady()) {
    const int16_t wheelRaw = Sensor::wheelRaw();
    const bool wheelDue =
        !_wheelPrimed ||
        static_cast<uint32_t>(nowMs - _lastWheelAtMs) >= DAT_WHEEL_PERIOD_MS;
    int32_t wheelDelta = static_cast<int32_t>(wheelRaw) - _lastWheelRaw;
    if (wheelDelta < 0) {
      wheelDelta = -wheelDelta;
    }
    const bool wheelEdge =
        _wheelPrimed && wheelRaw != DAT_INVALID_I16 &&
        wheelDelta >= DAT_WHEEL_EDGE_RAW &&
        static_cast<uint32_t>(nowMs - _lastWheelEdgeAtMs) >= DAT_EDGE_MIN_INTERVAL_MS;
    if (wheelDue || wheelEdge) {
      uint8_t payload[DAT_WHEEL_LEN];
      writeI16(payload, wheelRaw);
      writeI16(payload + 2, DAT_INVALID_I16);
      Dat.add(DAT_WHEEL, payload, DAT_WHEEL_LEN);
      _lastWheelAtMs = nowMs;
      _lastWheelEdgeAtMs = nowMs;
      _lastWheelRaw = wheelRaw;
      _wheelPrimed = true;
    }
  }
}

void RuntimeCmd::sendWifiStatus(uint32_t nowMs) {
  uint8_t payload[DAT_WIFI_STATUS_LEN];
  payload[0] = static_cast<uint8_t>(Wifi.mode());
  payload[1] = static_cast<uint8_t>(Wifi.state());
  Wifi.localIpBytes(&payload[2]);
  // 未连网络时 RSSI 无意义，用 0xFFFF 表示不可用。
  writeU16(payload + 6,
           Wifi.state() == WifiState::Disconnected
               ? DAT_INVALID_U16
               : static_cast<uint16_t>(static_cast<int16_t>(Wifi.rssi())));
  Dat.add(DAT_WIFI_STATUS, payload, DAT_WIFI_STATUS_LEN);
  _lastWifiStatusAtMs = nowMs;
  _lastWifiMode = payload[0];
  _lastWifiState = payload[1];
  _wifiStatusPrimed = true;
}

void RuntimeCmd::sendDeviceInfoRound() {
  // DAT_INFO 拆成三个定长 TYPE，PAYLOAD 内不再带子类型字节。
  uint8_t version[DAT_INFO_VERSION_LEN];
  std::memset(version, 0, sizeof(version));
  version[0] = FW_MAJOR;
  version[1] = FW_MINOR;
  version[2] = FW_PATCH;
  version[3] = PROTOCOL_VERSION;
  // 型号固定 ASCII 16 B，不足补 0x00，不得含中文。
  std::memcpy(version + 4, MODEL_NAME, sizeof(MODEL_NAME) - 1);
  Dat.add(DAT_INFO_VERSION, version, DAT_INFO_VERSION_LEN);

  uint8_t power[DAT_INFO_POWER_LEN];
  power[0] = BATTERY_CELLS;
  power[1] = BATTERY_CHEMISTRY;
  writeU16(power + 2, WHEEL_DIAMETER_MM);  // 只报 mm，App 自行换算
  power[4] = MAX_GEAR_PERCENT;
  Dat.add(DAT_INFO_POWER, power, DAT_INFO_POWER_LEN);

  const uint32_t flashBytes = ESP.getFlashChipSize();
  const uint32_t psramBytes = ESP.getPsramSize();
  const uint16_t flashMb =
      flashBytes == 0 ? DAT_INVALID_U16
                      : static_cast<uint16_t>(flashBytes / (1024UL * 1024UL));
  const uint16_t psramKb = psramBytes == 0 ? 0 : memoryKb(psramBytes);

  uint8_t storage[DAT_INFO_STORAGE_LEN];
  writeU16(storage, flashMb);
  writeU16(storage + 2, psramKb);
  // 能力位图：本板有屏幕，其余外设未接入。
  writeU16(storage + 4, CAP_DISPLAY);
  Dat.add(DAT_INFO_STORAGE, storage, DAT_INFO_STORAGE_LEN);

  // 一次性数据一轮：内存/日志统计只在这里发出（握手或 CMD_REQ_INFO 补拉）。
  const uint32_t psramSize = ESP.getPsramSize();
  const uint16_t ramFree = memoryKb(ESP.getFreeHeap());
  const uint16_t ramMin = memoryKb(ESP.getMinFreeHeap());
  const uint16_t psramFree =
      psramSize > 0 ? memoryKb(ESP.getFreePsram()) : DAT_INVALID_U16;
  const uint16_t psramMin =
      psramSize > 0 ? memoryKb(ESP.getMinFreePsram()) : DAT_INVALID_U16;
  const uint32_t uptimeSeconds = millis() / 1000;
  const uint32_t logDropped = Log.droppedCount();

  uint8_t memory[DAT_MEM_LEN];
  writeU16(memory, ramFree);
  writeU16(memory + 2, psramFree);
  writeU16(memory + 4, ramMin);
  writeU16(memory + 6, psramMin);
  writeU16(memory + 8, static_cast<uint16_t>(uptimeSeconds >> 16));
  writeU16(memory + 10, static_cast<uint16_t>(uptimeSeconds & 0xFFFFU));
  const uint16_t logPending = Log.pendingCount();
  memory[12] = logPending > 0xFF ? 0xFF : static_cast<uint8_t>(logPending);
  memory[13] = 0xFF;
  writeU16(memory + 14, logDropped > 0xFFFEU ? static_cast<uint16_t>(0xFFFE)
                                             : static_cast<uint16_t>(logDropped));
  Dat.add(DAT_MEM, memory, DAT_MEM_LEN);
}

bool RuntimeCmd::requestRecording() {
  // 板子不能发 CMD；要录音只能发 DAT_REQ（请求码 2），由 App 映射成 CMD_REQ_RECORD。
  if (!Cmd.isSessionReady() || !Wifi.hasTcpClient()) {
    JL_LOGW(LOG_MOD_DAT, DAT_LOG_REQ_REJECTED,
            LOG_SERIAL_TEXT("DAT_REQ requires an initialized connection."));
    return false;
  }
  uint8_t payload[DAT_REQ_LEN];
  payload[0] = DAT_REQ_RECORD;
  payload[1] = 0;
  if (!Dat.add(DAT_REQ, payload, DAT_REQ_LEN)) {
    return false;
  }
  JL_LOGD(LOG_MOD_DAT, DAT_LOG_REQ_SENT, LOG_SERIAL_TEXT("DAT_REQ sent."));
  return true;
}

RuntimeCmd Runtime;
