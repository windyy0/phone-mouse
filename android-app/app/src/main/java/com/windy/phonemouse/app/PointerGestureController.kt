package com.windy.phonemouse.app

import kotlin.math.abs
import kotlin.math.hypot

/** Coordinates are dp. A second tap must not move the caret before button-down. */
internal class PointerGestureController(
    private val onMove: (Double, Double) -> Unit,
    private val onTap: () -> Unit,
    private val onButtonDown: () -> Unit,
    private val onButtonUp: () -> Unit,
    private val onTapRecognized: () -> Unit = {},
) {
    private data class PendingTap(val x: Float, val y: Float, val at: Long, val click: Boolean)
    private var active = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downAt = 0L
    private var pendingTap: PendingTap? = null
    private var doubleTapDragEnabled = true
    private var secondTap = false
    private var moved = false
    private var filterTapJitter = true
    private var longPressEligible = false
    var dragging = false
        private set
    val longPressPending get() = active && longPressEligible && !dragging
    val tapEligible get() = active && !dragging && !moved
    val pendingTapDeadline get() = pendingTap?.takeIf { !active }?.at?.plus(DOUBLE_TAP_MS)

    fun down(
        x: Float, y: Float, timeMs: Long, tapToClick: Boolean = true,
        longPressDrag: Boolean = false, doubleTapHoldDrag: Boolean = true,
    ) {
        if (active) cancel()
        val previousTap = pendingTap
        secondTap = doubleTapHoldDrag && previousTap != null && timeMs - previousTap.at in 0L..DOUBLE_TAP_MS &&
            hypot((x - previousTap.x).toDouble(), (y - previousTap.y).toDouble()) <= 24.0
        if (!secondTap) commitPendingTap()
        doubleTapDragEnabled = doubleTapHoldDrag
        downX = x; downY = y; lastX = x; lastY = y; downAt = timeMs
        filterTapJitter = tapToClick || longPressDrag
        longPressEligible = longPressDrag
        active = true; moved = false; dragging = false
    }

    fun tryStartLongPress(timeMs: Long): Boolean {
        if (!longPressPending || timeMs - downAt < LONG_PRESS_MS) return false
        longPressEligible = false; secondTap = false; pendingTap = null
        dragging = true; moved = true
        onButtonDown()
        return true
    }

    fun move(x: Float, y: Float) {
        if (!active) return
        val dx = x - lastX
        val dy = y - lastY
        lastX = x; lastY = y
        if (dragging) {
            if (dx != 0f || dy != 0f) onMove(dx.toDouble(), dy.toDouble())
            return
        }
        val beyondTapSlop = abs(x - downX) > 4f || abs(y - downY) > 4f
        if (beyondTapSlop) longPressEligible = false
        if (secondTap && !dragging) {
            // No preceding click: Windows would otherwise recognize this press
            // as a double-click and text controls could drag whole words.
            val distance = hypot((x - downX).toDouble(), (y - downY).toDouble())
            if (distance >= DRAG_SLOP_DP) {
                dragging = true; moved = true; pendingTap = null
                longPressEligible = false
                onButtonDown()
                // The recognition distance is a dead zone, not a burst to replay.
                // Keep only the overshoot so coarse samples do not lose real motion.
                val fraction = (distance - DRAG_SLOP_DP) / distance
                if (fraction > 0.0) onMove((x - downX) * fraction, (y - downY) * fraction)
            }
            return
        }
        if (!moved && filterTapJitter) {
            if (beyondTapSlop) {
                moved = true
                onMove((x - downX).toDouble(), (y - downY).toDouble())
            }
            return
        }
        if (beyondTapSlop) moved = true
        if (dx != 0f || dy != 0f) onMove(dx.toDouble(), dy.toDouble())
    }

    fun up(x: Float, y: Float, timeMs: Long, tapToClick: Boolean) {
        if (!active) return
        // ACTION_UP can carry a final position not present in ACTION_MOVE.
        move(x, y)
        active = false; longPressEligible = false
        if (dragging) {
            dragging = false
            onButtonUp()
        } else if (!moved && timeMs - downAt in 0L until DOUBLE_TAP_MS) {
            if (tapToClick) onTapRecognized()
            if (secondTap) {
                commitPendingTap()
                if (tapToClick) onTap()
            } else if (doubleTapDragEnabled) pendingTap = PendingTap(downX, downY, timeMs, tapToClick)
            else if (tapToClick) onTap()
        } else commitPendingTap()
        secondTap = false
    }

    fun flushTap(timeMs: Long, force: Boolean = false): Boolean {
        if (active) return false
        val tap = pendingTap ?: return false
        if (!force && timeMs - tap.at < DOUBLE_TAP_MS) return false
        commitPendingTap()
        return true
    }

    private fun commitPendingTap() {
        val tap = pendingTap
        pendingTap = null
        if (tap?.click == true) onTap()
    }

    fun cancel() {
        active = false; secondTap = false; pendingTap = null; longPressEligible = false
        if (dragging) { dragging = false; onButtonUp() }
    }

    companion object {
        const val LONG_PRESS_MS = 800L
        private const val DOUBLE_TAP_MS = 350L
        private const val DRAG_SLOP_DP = 8.0
    }
}
