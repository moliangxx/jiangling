package com.example.jiangling.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 语音开关的界面状态。
 *
 * 只有「关」和「开」两态：启动失败不保留 Failure 态，直接复位成 [Off]，
 * 失败原因通过 [VoiceCallManager] 的 onNotice 回调弹一次提示。
 */
sealed interface VoiceState {
    object Off : VoiceState
    object On : VoiceState
}

/**
 * 语音采集链路（首页左上角「语音通话」圆形开关背后的实现）。
 *
 * 【为什么只做本机采集】
 * 协议文档 `BLE通信协议/江灵通信协议/总览.md` 明确：AI 语音识别/播报全部在设备侧完成，
 * App 不推流、不调用 AI 接口；当前 CMD 里没有实时语音上行/下行命令（音频只有
 * `CMD_AUDIO_TEST` / `CMD_VOLUME` / `CMD_RECORD` / `CMD_REQ_RECORD`）。
 * 因此这里实现的是**真实可验证的本机麦克风链路**：16 kHz / 单声道 / PCM16 采集，
 * 开关能真实启停；不伪造「已和板子通话」。
 *
 * 【生命周期】
 * 开启 → 申请 RECORD_AUDIO → 创建 AudioRecord → 进入 RECORDING → 后台持续读缓冲；
 * 关闭 / 出错 → 停止读线程、release AudioRecord、状态复位为 [VoiceState.Off]。
 *
 * @param onNotice 失败/权限提示（接到 MainViewModel 的统一 Toast 通道）
 */
class VoiceCallManager(
    private val context: Context,
    private val onNotice: (String) -> Unit
) {
    companion object {
        /** 与协议规划的上行音频格式一致：PCM16 / 16 kHz / 单声道 */
        const val SAMPLE_RATE = 16_000
    }

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Off)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private var recorder: AudioRecord? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    /** 运行时是否已授予录音权限（权限声明在 AndroidManifest.xml） */
    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * 打开采集链路。
     *
     * @return true = 已进入录音状态；false = 权限/设备/启动失败（状态已复位并提示原因）
     */
    fun start(): Boolean {
        if (running) return true
        if (!hasPermission()) {
            fail("缺少录音权限，无法开启语音")
            return false
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            fail("麦克风采样参数不受支持（minBuffer=$minBuffer）")
            return false
        }

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2
            )
        } catch (t: Throwable) {
            fail("麦克风初始化失败：${t.message ?: t.javaClass.simpleName}")
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            fail("麦克风初始化失败（STATE_UNINITIALIZED）")
            return false
        }

        try {
            record.startRecording()
        } catch (t: Throwable) {
            record.release()
            fail("麦克风启动失败：${t.message ?: t.javaClass.simpleName}")
            return false
        }

        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            fail("麦克风未进入录音状态")
            return false
        }

        recorder = record
        running = true
        _state.value = VoiceState.On

        // 持续读缓冲：只有真的在 read，链路才算"活着"；read 返回负值即判定链路中断
        worker = thread(name = "VoiceCapture", isDaemon = true) {
            val buffer = ShortArray(minBuffer)
            try {
                while (running) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read < 0) {
                        if (running) fail("麦克风采集异常（code=$read），已自动关闭")
                        break
                    }
                }
            } catch (t: Throwable) {
                if (running) fail("麦克风采集中断：${t.message ?: t.javaClass.simpleName}，已自动关闭")
            }
        }
        return true
    }

    /** 关闭采集链路并复位；重复调用安全 */
    fun stop() {
        running = false
        recorder?.let { record ->
            try {
                record.stop()
            } catch (_: Throwable) {
                // 已经停止/未启动，忽略
            }
            record.release()
        }
        recorder = null
        worker = null
        _state.value = VoiceState.Off
    }

    /** 权限被拒：明确提示并保持关闭 */
    fun reportPermissionDenied() {
        fail("缺少录音权限，请在系统设置里允许录音")
    }

    /** Activity 销毁时释放 */
    fun release() = stop()

    /** 失败统一出口：复位 UI + 释放设备 + 提示原因（失败自动复位，不留下"假开启"） */
    private fun fail(reason: String) {
        running = false
        recorder?.let { record ->
            try {
                record.stop()
            } catch (_: Throwable) {
                // 未进入录音状态时 stop 会抛异常，忽略
            }
            record.release()
        }
        recorder = null
        worker = null
        _state.value = VoiceState.Off
        onNotice(reason)
    }
}
