package com.example.jiangling.main.control

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/*
 * ======================================================================
 *  轮盘命令的统一节流发送器
 * ======================================================================
 *
 *  触摸回调里**只 submit（更新最新状态）**，真正的下发集中在这里，按固定节拍进行：
 *    - 按下/拖动：第一帧立即发，之后每 [periodMs] 重发一次「最新状态」，
 *      手指按住不动也会重发，避免板子等不到心跳而超时自停；
 *    - 松手：立刻发一次停止，并结束循环（重复调用不会重复发停止）；
 *    - 组合销毁：自动按松手处理，避免退出页面后小车还在跑。
 *
 *  移动轮盘和云台轮盘各持有一个实例，互不影响。
 * ======================================================================
 */

@Stable
class ThrottledSender<T> internal constructor(
    private val scope: CoroutineScope,
    private val periodMs: Long
) {
    private var latest: T? = null
    private var job: Job? = null
    private var onFrame: ((T) -> Unit)? = null
    private var onStop: (() -> Unit)? = null

    internal fun bind(frame: (T) -> Unit, stop: () -> Unit) {
        onFrame = frame
        onStop = stop
    }

    /** 更新最新状态；首次调用会开启节拍循环，之后只替换待发内容 */
    fun submit(value: T) {
        latest = value
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                latest?.let { onFrame?.invoke(it) }
                delay(periodMs)
            }
        }
    }

    /** 松手：立刻发一次停止并停机；当前没有在发的内容时什么也不做 */
    fun release() {
        val hadSomething = latest != null
        job?.cancel()
        job = null
        latest = null
        if (hadSomething) onStop?.invoke()
    }
}

@Composable
fun <T> rememberThrottledSender(
    periodMs: Long = SEND_PERIOD_MS,
    onFrame: (T) -> Unit,
    onStop: () -> Unit
): ThrottledSender<T> {
    val scope = rememberCoroutineScope()
    val sender = remember { ThrottledSender<T>(scope, periodMs) }
    // 用 rememberUpdatedState 保证节拍循环里拿到的是最新闭包（例如挡位被改过）
    val currentFrame by rememberUpdatedState(onFrame)
    val currentStop by rememberUpdatedState(onStop)
    SideEffect {
        sender.bind(frame = { currentFrame(it) }, stop = { currentStop() })
    }
    // 切后台 / 被遮挡：立刻按松手处理（补一帧停止 + 停掉 200ms 节拍），
    // 否则拖拽中按 Home 会让最后一帧在后台一直被重发。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(sender, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // ON_PAUSE 已覆盖绝大多数退后台场景；ON_STOP 再兜一次（release 幂等）
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                sender.release()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            sender.release()
        }
    }
    return sender
}
