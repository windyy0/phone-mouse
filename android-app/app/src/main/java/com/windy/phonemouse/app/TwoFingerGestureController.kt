package com.windy.phonemouse.app

import kotlin.math.hypot
import kotlin.math.abs
import kotlin.math.ln

internal data class TouchContact(val id: Int, val x: Float, val y: Float)

/** Tracks each contact, so opposing finger motion cannot become a right-click. */
internal class TwoFingerGestureController(
    private val onHorizontalScroll: (Double) -> Unit = {},
    private val onZoom: (Double) -> Unit = {},
) {
    private enum class Mode { Idle, Tracking, Lifting, Cancelled }
    private enum class Gesture { Undecided, Scroll, Zoom }
    private enum class ScrollAxis { Undecided, Horizontal, Vertical }
    private var mode = Mode.Idle
    private var origins = emptyMap<Int, TouchContact>()
    private var startedAt = 0L
    private var tapEligible = false
    private var gesture = Gesture.Undecided
    private var scrollAxis = ScrollAxis.Undecided
    private var originX = 0.0
    private var originY = 0.0
    private var lastX = 0.0
    private var lastY = 0.0
    private var originSpan = 0.0
    private var lastSpan = 0.0

    fun start(contacts: List<TouchContact>, gestureStartedAt: Long, allowRightClick: Boolean) {
        cancel()
        if (contacts.size != 2 || contacts.map { it.id }.distinct().size != 2 || contacts.any { !it.x.isFinite() || !it.y.isFinite() }) return
        origins = contacts.associateBy { it.id }
        startedAt = gestureStartedAt
        tapEligible = allowRightClick
        originX = contacts.sumOf { it.x.toDouble() } / 2.0
        originY = contacts.sumOf { it.y.toDouble() } / 2.0
        lastX = originX; lastY = originY
        originSpan = span(contacts); lastSpan = originSpan
        mode = Mode.Tracking
    }

    fun move(contacts: List<TouchContact>): Double {
        if (mode == Mode.Lifting) {
            checkRemainingContact(contacts)
            return 0.0
        }
        if (mode != Mode.Tracking) return 0.0
        if (contacts.size != 2 || contacts.map { it.id }.toSet() != origins.keys || contacts.any { !it.x.isFinite() || !it.y.isFinite() }) {
            cancel()
            return 0.0
        }
        checkTapMovement(contacts)
        val x = contacts.sumOf { it.x.toDouble() } / 2.0
        val y = contacts.sumOf { it.y.toDouble() } / 2.0
        val distance = span(contacts)
        if (gesture == Gesture.Undecided) {
            val pan = hypot(x - originX, y - originY)
            val spread = abs(distance - originSpan)
            gesture = when {
                originSpan >= 24.0 && distance >= 12.0 && spread >= maxOf(6.0, originSpan * .08) && spread >= pan * 1.8 -> Gesture.Zoom
                abs(x - originX) >= SCROLL_SLOP_DP || abs(y - originY) >= SCROLL_SLOP_DP -> Gesture.Scroll
                else -> return 0.0
            }
            tapEligible = false
        }
        if (gesture == Gesture.Zoom) {
            if (distance >= 12.0 && lastSpan >= 12.0) onZoom(ln(distance / lastSpan) * 600.0)
            lastSpan = distance.coerceAtLeast(12.0)
            return 0.0
        }
        if (scrollAxis == ScrollAxis.Undecided) {
            val dx = abs(x - originX); val dy = abs(y - originY)
            // A clear direction starts at the normal slop. Ambiguous starts
            // get a little more travel before choosing their dominant axis.
            scrollAxis = when {
                dx >= SCROLL_SLOP_DP && dx >= dy * AXIS_DOMINANCE -> ScrollAxis.Horizontal
                dy >= SCROLL_SLOP_DP && dy >= dx * AXIS_DOMINANCE -> ScrollAxis.Vertical
                maxOf(dx, dy) >= AXIS_DECISION_DP -> if (dx > dy) ScrollAxis.Horizontal else ScrollAxis.Vertical
                else -> return 0.0
            }
        }
        // Keep the chosen axis until lift/cancel, even if the fingers drift or
        // reverse. Preserve buffered travel only along that axis.
        if (scrollAxis == ScrollAxis.Horizontal) onHorizontalScroll((x - lastX) * WHEEL_UNITS_PER_DP)
        val delta = if (scrollAxis == ScrollAxis.Vertical) -(y - lastY) * WHEEL_UNITS_PER_DP else 0.0
        lastX = x; lastY = y
        return delta
    }

    /** POINTER_UP still contains both fingers, including their final positions. */
    fun lift(contacts: List<TouchContact>): Double {
        if (mode != Mode.Tracking) return 0.0
        val delta = move(contacts)
        if (mode == Mode.Tracking) mode = Mode.Lifting
        return delta
    }

    fun finish(contacts: List<TouchContact>, timeMs: Long): Boolean {
        if (mode != Mode.Lifting) { cancel(); return false }
        checkRemainingContact(contacts)
        val click = mode == Mode.Lifting && tapEligible && timeMs - startedAt in 0L..TAP_MS
        cancel()
        return click
    }

    private fun checkRemainingContact(contacts: List<TouchContact>) {
        if (contacts.size != 1 || contacts.single().id !in origins) { cancel(); return }
        checkTapMovement(contacts)
    }

    private fun checkTapMovement(contacts: List<TouchContact>) {
        if (contacts.any { current ->
                val origin = origins.getValue(current.id)
                hypot((current.x - origin.x).toDouble(), (current.y - origin.y).toDouble()) > TAP_SLOP_DP
            }) tapEligible = false
    }

    fun cancel() {
        mode = Mode.Cancelled
        origins = emptyMap()
        tapEligible = false
        gesture = Gesture.Undecided
        scrollAxis = ScrollAxis.Undecided
    }

    private fun span(contacts: List<TouchContact>) = hypot(
        (contacts[0].x - contacts[1].x).toDouble(), (contacts[0].y - contacts[1].y).toDouble())

    private companion object {
        const val TAP_MS = 350L
        const val TAP_SLOP_DP = 6.0
        const val SCROLL_SLOP_DP = 4.0
        const val AXIS_DECISION_DP = 8.0
        const val AXIS_DOMINANCE = 1.5
        const val WHEEL_UNITS_PER_DP = 120.0 / 18.0
    }
}
