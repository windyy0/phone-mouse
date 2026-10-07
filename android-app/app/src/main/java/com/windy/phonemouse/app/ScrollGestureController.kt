package com.windy.phonemouse.app

import kotlin.math.abs
import kotlin.math.sign

/** Gesture timing and wheel units are independent of Android drawing and callbacks. */
internal class ScrollGestureController {
    enum class Mode { Idle, Pressing, Direct, Continuous }

    var mode = Mode.Idle
        private set
    var displacementDp = 0f
        private set
    private var originX = 0f
    private var originY = 0f
    private var lastY = 0f
    private var startedAt = 0L
    private var lastFrameAt = 0L

    fun start(xDp: Float, yDp: Float, nowMs: Long, allowContinuous: Boolean) {
        originX = xDp
        originY = yDp
        lastY = yDp
        displacementDp = 0f
        startedAt = nowMs
        lastFrameAt = nowMs
        mode = if (allowContinuous) Mode.Pressing else Mode.Direct
    }

    fun move(xDp: Float, yDp: Float): Double {
        if (mode == Mode.Idle) return 0.0
        val deltaY = yDp - lastY
        lastY = yDp
        displacementDp = yDp - originY
        if (mode == Mode.Pressing &&
            (abs(xDp - originX) > HOLD_SLOP_DP || abs(displacementDp) > HOLD_SLOP_DP)) {
            // Once a drag starts, stopping or returning to the origin cannot re-arm it.
            mode = Mode.Direct
        }
        return if (mode == Mode.Continuous) 0.0 else (-deltaY * (120f / 18f)).toDouble()
    }

    fun tryStartContinuous(nowMs: Long): Boolean {
        if (mode != Mode.Pressing || nowMs - startedAt < HOLD_MS) return false
        mode = Mode.Continuous
        lastFrameAt = nowMs
        return true
    }

    fun frame(nowMs: Long): Double {
        if (mode != Mode.Continuous) return 0.0
        val elapsedMs = (nowMs - lastFrameAt).coerceIn(0L, MAX_FRAME_MS)
        lastFrameAt = nowMs
        val amplitude = ((abs(displacementDp) - DEAD_ZONE_DP) / (FULL_SPEED_DP - DEAD_ZONE_DP))
            .coerceIn(0f, 1f).toDouble()
        return -sign(displacementDp.toDouble()) * amplitude * amplitude * MAX_SPEED * elapsedMs / 1000.0
    }

    fun stop() {
        mode = Mode.Idle
        displacementDp = 0f
        startedAt = 0L
        lastFrameAt = 0L
    }

    companion object {
        const val HOLD_MS = 450L
        private const val HOLD_SLOP_DP = 6f
        private const val DEAD_ZONE_DP = 8f
        const val FULL_SPEED_DP = 88f
        private const val MAX_SPEED = 2400.0
        private const val MAX_FRAME_MS = 50L
    }
}
