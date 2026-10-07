package com.windy.phonemouse.app

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.math.hypot

enum class MouseControlMode(val code: String) {
    Touchpad("touchpad"), Gyroscope("gyroscope");
    companion object {
        fun fromCode(code: String?) = entries.firstOrNull { it.code == code } ?: Touchpad
    }
}

data class GyroSettings(val sensitivity: Float = 1f, val smoothing: Boolean = true,
    val reverseX: Boolean = false, val reverseY: Boolean = false,
    val acceleration: PointerAccelerationSettings = PointerAccelerationSettings()) {
    fun sanitized() = copy(sensitivity = if (sensitivity.isFinite()) sensitivity.coerceIn(.3f, 3f) else 1f,
        acceleration = acceleration.sanitized(gyroscope = true))
}

internal data class GyroDelta(val x: Double = 0.0, val y: Double = 0.0)

internal fun shouldRunGyro(mode: MouseControlMode, paused: Boolean, connected: Boolean,
    foreground: Boolean, blocked: Boolean) =
    mode == MouseControlMode.Gyroscope && !paused && connected && foreground && !blocked

/** Converts angular velocity into relative cursor movement, without accumulating an absolute heading. */
internal class GyroMotionController {
    private var gravityX = 0.0
    private var gravityY = 1.0
    private var gravityZ = 0.0
    private var lastTimestamp = 0L
    private var filteredX = 0.0
    private var filteredY = 0.0
    private var repositioning = false
    private val acceleration = PointerAccelerationController(PointerInputKind.Gyroscope)

    fun gravity(x: Double, y: Double, z: Double): Boolean {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return false
        val length = sqrt(x * x + y * y + z * z)
        if (length < .1 || !length.isFinite()) return false
        gravityX = x / length; gravityY = y / length; gravityZ = z / length
        return true
    }

    fun reset() { lastTimestamp = 0L; filteredX = 0.0; filteredY = 0.0; acceleration.reset() }

    fun setRepositioning(value: Boolean) {
        if (repositioning == value) return
        repositioning = value
        reset()
    }

    fun sample(timestamp: Long, x: Double, y: Double, z: Double, rotation: Int,
        settings: GyroSettings): GyroDelta {
        // Ignore the entire return stroke; release starts with a fresh sample,
        // including filtering and acceleration, rather than replaying the turn.
        if (repositioning) return GyroDelta()
        if (timestamp <= 0L || !x.isFinite() || !y.isFinite() || !z.isFinite()) {
            reset(); return GyroDelta()
        }
        if (lastTimestamp == 0L) { lastTimestamp = timestamp; acceleration.begin(timestamp); return GyroDelta() }
        if (timestamp <= lastTimestamp) return GyroDelta()
        val dt = (timestamp - lastTimestamp) / 1_000_000_000.0
        lastTimestamp = timestamp
        // A pause or a missing batch must never become a large catch-up movement.
        if (dt > .1) { filteredX = 0.0; filteredY = 0.0; acceleration.begin(timestamp); return GyroDelta() }
        val yaw = x * gravityX + y * gravityY + z * gravityZ
        val rightX = when (rotation) { 0 -> 1.0; 2 -> -1.0; else -> 0.0 }
        val rightY = when (rotation) { 1 -> 1.0; 3 -> -1.0; else -> 0.0 }
        val rightGravity = rightX * gravityX + rightY * gravityY
        val rightLength = sqrt((1.0 - rightGravity * rightGravity).coerceAtLeast(0.0))
        val pitch = if (rightLength > .15) (x * rightX + y * rightY - yaw * rightGravity) / rightLength
            else x * rightX + y * rightY
        fun deadZone(value: Double) = sign(value) * (abs(value) - .01).coerceAtLeast(0.0)
        val rawX = deadZone(-yaw)
        val rawY = deadZone(-pitch)
        val speed = hypot(rawX, rawY)
        val smoothingTime = .035 - .028 * smoothStep(speed / 1.4)
        val alpha = if (settings.smoothing) 1.0 - exp(-dt / smoothingTime) else 1.0
        // Do not carry the previous direction into a fine correction.
        if (filteredX * rawX < 0.0) filteredX = 0.0
        if (filteredY * rawY < 0.0) filteredY = 0.0
        // Clear the smoothing tail as soon as the phone stops, rather than letting the cursor drift.
        filteredX = if (rawX == 0.0) 0.0 else filteredX + alpha * (rawX - filteredX)
        filteredY = if (rawY == 0.0) 0.0 else filteredY + alpha * (rawY - filteredY)
        val accelerated = acceleration.move(rawX * dt, rawY * dt, timestamp, settings.acceleration)
        val multiplier = if (speed > 0.0) hypot(accelerated.x, accelerated.y) / (speed * dt) else 1.0
        val gain = 1200.0 * settings.sanitized().sensitivity * dt * multiplier
        // Bound speed rather than each sample's distance, so changing the sensor
        // frequency does not change the maximum cursor speed or diagonal direction.
        val outX = filteredX * gain; val outY = filteredY * gain
        val scale = (6000.0 * dt / hypot(outX, outY).coerceAtLeast(.000001)).coerceAtMost(1.0)
        return GyroDelta(outX * scale * if (settings.reverseX) -1 else 1,
            outY * scale * if (settings.reverseY) -1 else 1)
    }
}
