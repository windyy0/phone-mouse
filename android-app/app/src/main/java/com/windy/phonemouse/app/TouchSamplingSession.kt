package com.windy.phonemouse.app

import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class SamplingPhase { Ready, Armed, Running, Finished, Cancelled }

internal data class TouchSamplingState(
    val phase: SamplingPhase = SamplingPhase.Ready,
    val secondsLeft: Int = 5,
    val result: TouchSamplingResult? = null,
)

/** Local-only test: no RemoteClient calls and no need to connect a computer. */
internal class TouchSamplingSession(private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(TouchSamplingState())
    val state = mutableState.asStateFlow()
    private var meter = TouchSampleMeter()
    private var timer: Job? = null
    private var deadline = 0L
    private var singleFinger = false

    fun arm() {
        timer?.cancel(); meter = TouchSampleMeter(); singleFinger = false
        mutableState.value = TouchSamplingState(phase = SamplingPhase.Armed)
    }

    fun cancel() {
        if (mutableState.value.phase !in listOf(SamplingPhase.Armed, SamplingPhase.Running)) return
        timer?.cancel(); meter.endStroke(); singleFinger = false
        mutableState.value = TouchSamplingState(phase = SamplingPhase.Cancelled)
    }

    private fun start() {
        deadline = SystemClock.uptimeMillis() + 5_000L
        mutableState.value = TouchSamplingState(phase = SamplingPhase.Running)
        timer = scope.launch {
            while (SystemClock.uptimeMillis() < deadline) {
                val remaining = deadline - SystemClock.uptimeMillis()
                mutableState.value = TouchSamplingState(SamplingPhase.Running,
                    ((remaining + 999L) / 1000L).toInt().coerceIn(1, 5), meter.result())
                delay(100)
            }
            finish()
        }
    }

    private fun finish() {
        meter.endStroke(); singleFinger = false
        mutableState.value = TouchSamplingState(SamplingPhase.Finished, 0, meter.result())
    }

    fun onTouch(event: MotionEvent) {
        if (mutableState.value.phase == SamplingPhase.Running && SystemClock.uptimeMillis() >= deadline) {
            timer?.cancel(); finish()
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN && mutableState.value.phase == SamplingPhase.Armed) start()
        if (mutableState.value.phase != SamplingPhase.Running) return
        val precise = Build.VERSION.SDK_INT >= 34
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                singleFinger = event.pointerCount == 1 && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
                if (singleFinger) meter.beginStroke(
                    if (precise) event.eventTimeNanos else event.eventTime * 1_000_000L, event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> if (singleFinger && event.pointerCount == 1) {
                val deliveredAt = System.nanoTime()
                var moving = false
                for (index in 0 until event.historySize) {
                    val time = if (precise) event.getHistoricalEventTimeNanos(index)
                        else event.getHistoricalEventTime(index) * 1_000_000L
                    moving = meter.sample(time, event.getHistoricalX(0, index), event.getHistoricalY(0, index), !precise) || moving
                }
                moving = meter.sample(if (precise) event.eventTimeNanos else event.eventTime * 1_000_000L,
                    event.x, event.y, !precise) || moving
                meter.delivered(deliveredAt, moving)
            }
            MotionEvent.ACTION_POINTER_DOWN -> { singleFinger = false; meter.endStroke(multipleFingers = true) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { singleFinger = false; meter.endStroke() }
        }
    }
}
