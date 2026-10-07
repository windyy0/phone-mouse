package com.windy.phonemouse.app

import kotlin.math.exp
import kotlin.math.hypot

enum class PointerAccelerationMode(val code: String, val label: String) {
    Off("off", "关闭"), Speed("speed", "速度加速"), Distance("distance", "距离加速");
    companion object {
        fun fromCode(code: String?) = entries.firstOrNull { it.code == code } ?: Speed
    }
}

enum class PointerAccelerationStrength(val code: String, val label: String, internal val maximum: Double,
    internal val rangeScale: Double) {
    Light("light", "轻", 1.8, 1.35), Medium("medium", "中", 2.6, 1.0), Strong("strong", "强", 3.4, .75),
    Custom("custom", "自定义", 2.6, 1.0);
    companion object {
        fun fromCode(code: String?) = entries.firstOrNull { it.code == code } ?: Medium
    }
}

/** Thresholds use dp (touchpad) or radians (gyroscope), never screen pixels. */
data class PointerAccelerationCurve(val minimum: Double, val maximum: Double, val start: Double, val end: Double) {
    internal fun sanitized(kind: PointerInputKind, mode: PointerAccelerationMode): PointerAccelerationCurve {
        val fallback = presetCurve(kind, mode, PointerAccelerationStrength.Medium)
        val limits = curveLimits(kind, mode)
        val low = (if (minimum.isFinite()) minimum else fallback.minimum).coerceIn(.25, 1.5)
        val high = (if (maximum.isFinite()) maximum else fallback.maximum).coerceIn(maxOf(1.0, low), 6.0)
        val onset = (if (start.isFinite()) start else fallback.start).coerceIn(0.0, limits.maximumStart)
        return copy(minimum = low, maximum = high, start = onset,
            end = (if (end.isFinite()) end else fallback.end).coerceIn(onset + limits.gap, limits.maximumEnd))
    }

    internal fun encode() = listOf(minimum, maximum, start, end).joinToString(",")
    companion object {
        internal fun decode(value: String?): PointerAccelerationCurve? {
            val parts = value?.split(',')?.map { it.toDoubleOrNull() ?: return null } ?: return null
            if (parts.size != 4 || parts.any { !it.isFinite() }) return null
            return PointerAccelerationCurve(parts[0], parts[1], parts[2], parts[3])
        }
    }
}

data class PointerAccelerationSettings(val mode: PointerAccelerationMode = PointerAccelerationMode.Speed,
    val strength: PointerAccelerationStrength = PointerAccelerationStrength.Medium,
    val customSpeed: PointerAccelerationCurve? = null, val customDistance: PointerAccelerationCurve? = null) {
    fun sanitized(gyroscope: Boolean = false): PointerAccelerationSettings {
        val kind = if (gyroscope) PointerInputKind.Gyroscope else PointerInputKind.Touchpad
        return copy(customSpeed = customSpeed?.sanitized(kind, PointerAccelerationMode.Speed),
            customDistance = customDistance?.sanitized(kind, PointerAccelerationMode.Distance))
    }

    internal fun curve(kind: PointerInputKind, mode: PointerAccelerationMode = this.mode): PointerAccelerationCurve {
        if (strength != PointerAccelerationStrength.Custom) return presetCurve(kind, mode, strength)
        val custom = if (mode == PointerAccelerationMode.Distance) customDistance else customSpeed
        return (custom ?: presetCurve(kind, mode, PointerAccelerationStrength.Medium)).sanitized(kind, mode)
    }

    internal fun selectStrength(value: PointerAccelerationStrength, kind: PointerInputKind): PointerAccelerationSettings =
        if (value == PointerAccelerationStrength.Custom) copy(strength = value,
            customSpeed = customSpeed ?: curve(kind, PointerAccelerationMode.Speed),
            customDistance = customDistance ?: curve(kind, PointerAccelerationMode.Distance))
        else copy(strength = value)

    internal fun withCurve(value: PointerAccelerationCurve) = if (mode == PointerAccelerationMode.Distance)
        copy(customDistance = value) else copy(customSpeed = value)
}

/** Touchpad units are dp; gyroscope units are radians, before sensitivity and network batching. */
internal enum class PointerInputKind(val speedStart: Double, val speedEnd: Double,
    val distanceStart: Double, val distanceEnd: Double) {
    Touchpad(40.0, 700.0, 18.0, 140.0), Gyroscope(.08, 1.6, .06, .38)
}

internal data class PointerCurveLimits(val maximumStart: Double, val maximumEnd: Double, val gap: Double)

internal fun curveLimits(kind: PointerInputKind, mode: PointerAccelerationMode): PointerCurveLimits = when {
    kind == PointerInputKind.Touchpad && mode == PointerAccelerationMode.Distance -> PointerCurveLimits(200.0, 600.0, 1.0)
    kind == PointerInputKind.Touchpad -> PointerCurveLimits(1000.0, 3000.0, 10.0)
    mode == PointerAccelerationMode.Distance -> PointerCurveLimits(Math.toRadians(45.0), Math.toRadians(120.0), Math.toRadians(.1))
    else -> PointerCurveLimits(Math.toRadians(120.0), Math.toRadians(360.0), Math.toRadians(1.0))
}

private fun presetCurve(kind: PointerInputKind, mode: PointerAccelerationMode, strength: PointerAccelerationStrength) =
    if (mode == PointerAccelerationMode.Distance) PointerAccelerationCurve(1.0, strength.maximum,
        kind.distanceStart, kind.distanceEnd * strength.rangeScale)
    else PointerAccelerationCurve(.75, strength.maximum, kind.speedStart, kind.speedEnd * strength.rangeScale)

internal data class PointerDelta(val x: Double = 0.0, val y: Double = 0.0)

internal fun smoothStep(value: Double): Double = value.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }

/** Each input source owns its own stroke state. No clocks or Android APIs are used here. */
internal class PointerAccelerationController(private val kind: PointerInputKind) {
    private var lastTime = 0L
    private var lastMotion = 0L
    private var filteredSpeed = 0.0
    private var speedReady = false
    private var strokeDistance = 0.0
    private var directionX = 0.0
    private var directionY = 0.0
    private var configured: PointerAccelerationSettings? = null

    fun reset() {
        lastTime = 0L; lastMotion = 0L
        clearStroke()
    }

    fun begin(timestampNanos: Long) { reset(); lastTime = timestampNanos.coerceAtLeast(0L) }

    private fun clearStroke() {
        filteredSpeed = 0.0; speedReady = false; strokeDistance = 0.0
        directionX = 0.0; directionY = 0.0
    }

    fun move(dx: Double, dy: Double, timestampNanos: Long, settings: PointerAccelerationSettings): PointerDelta {
        val length = hypot(dx, dy)
        if (!length.isFinite() || timestampNanos <= 0L) { reset(); return PointerDelta() }
        if (lastTime > timestampNanos) return PointerDelta()
        if (configured != settings) { clearStroke(); configured = settings }
        val elapsed = if (lastTime > 0L) (timestampNanos - lastTime) / 1_000_000_000.0 else 0.0
        lastTime = timestampNanos
        if (lastMotion > 0L && timestampNanos - lastMotion >= STROKE_PAUSE_NANOS) clearStroke()
        if (length == 0.0) return PointerDelta()
        lastMotion = timestampNanos
        if (settings.mode == PointerAccelerationMode.Off) return PointerDelta(dx, dy)
        val curve = settings.curve(kind)

        val unitX = dx / length; val unitY = dy / length
        // A turn of more than 60 degrees starts a new intended movement. A fixed
        // stroke direction also prevents circular jitter from accumulating distance.
        val hasDirection = directionX != 0.0 || directionY != 0.0
        if (hasDirection && unitX * directionX + unitY * directionY < .5) clearStroke()
        if (directionX == 0.0 && directionY == 0.0) { directionX = unitX; directionY = unitY }

        val gain = when (settings.mode) {
            PointerAccelerationMode.Off -> 1.0
            PointerAccelerationMode.Speed -> {
                // A duplicate coarse Android timestamp must never divide by zero
                // or discard real movement. Reuse the previous speed for that point.
                val speed = if (elapsed > 0.0) (length / elapsed).coerceAtMost(maxOf(kind.speedEnd, curve.end) * 100) else filteredSpeed
                filteredSpeed = if (!speedReady) speed else filteredSpeed +
                    (1 - exp(-elapsed / .012)) * (speed - filteredSpeed)
                speedReady = true
                curve.minimum + (curve.maximum - curve.minimum) *
                    smoothStep((filteredSpeed - curve.start) / (curve.end - curve.start))
            }
            PointerAccelerationMode.Distance -> {
                val progress = (dx * directionX + dy * directionY).coerceAtLeast(0.0)
                // Integrate the gain across a segment, rather than using its end
                // gain. The same stroke travels equally far at different sample rates.
                val before = distanceIntegral(strokeDistance, curve)
                strokeDistance += progress
                if (progress > 0.0) (distanceIntegral(strokeDistance, curve) - before) / progress
                else curve.minimum
            }
        }.coerceIn(curve.minimum, curve.maximum)
        return PointerDelta(dx * gain, dy * gain)
    }

    private fun distanceIntegral(distance: Double, curve: PointerAccelerationCurve): Double {
        val span = curve.end - curve.start
        val t = ((distance - curve.start) / span).coerceIn(0.0, 1.0)
        val rampArea = span * (t * t * t - .5 * t * t * t * t)
        return curve.minimum * distance + (curve.maximum - curve.minimum) *
            (rampArea + (distance - curve.end).coerceAtLeast(0.0))
    }

    private companion object { const val STROKE_PAUSE_NANOS = 180_000_000L }
}
