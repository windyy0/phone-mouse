package com.windy.phonemouse.app

import kotlin.math.abs

internal data class TouchGesturePacket(val horizontal: Int, val zoom: Int)

/** Zoom is emitted as complete wheel notches; fractions stay within one gesture. */
internal class TouchGestureAccumulator {
    private var horizontal = 0.0
    private var zoom = 0.0
    fun add(dx: Double, scale: Double) {
        if (dx.isFinite()) horizontal += dx
        if (scale.isFinite()) zoom += scale
    }
    fun pending() = abs(horizontal) >= 1.0 || abs(zoom) >= 120.0
    fun drain(): TouchGesturePacket {
        val x = horizontal.coerceIn(-2400.0, 2400.0).toInt()
        val z = (zoom.coerceIn(-2400.0, 2400.0) / 120.0).toInt() * 120
        horizontal -= x; zoom -= z
        return TouchGesturePacket(x, z)
    }
    fun clear() { horizontal = 0.0; zoom = 0.0 }
}
