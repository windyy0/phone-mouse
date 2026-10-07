package com.windy.phonemouse.app

import org.junit.Assert.*
import org.junit.Test

class GyroMotionControllerTest {
    private val direct = GyroSettings(smoothing = false,
        acceleration = PointerAccelerationSettings(mode = PointerAccelerationMode.Off))
    private val origin = 1_000_000_000L
    private fun ready() = GyroMotionController().apply { sample(origin, 0.0, 0.0, 0.0, 0, direct) }

    @Test fun uprightAndFlatPhonesUseGravityForHorizontalMovement() {
        val upright = ready().apply { gravity(0.0, 9.81, 0.0) }
        val flat = ready().apply { gravity(0.0, 0.0, 9.81) }
        val a = upright.sample(origin + 10_000_000, 0.0, -1.0, 0.0, 0, direct)
        val b = flat.sample(origin + 10_000_000, 0.0, 0.0, -1.0, 0, direct)
        assertEquals(11.88, a.x, .00001); assertEquals(a, b)
    }
    @Test fun displayRotationPreservesPitchDirection() {
        for (rotation in 0..3) {
            val g = ready()
            val gravity = when (rotation) { 1 -> doubleArrayOf(-9.81, 0.0, 0.0)
                2 -> doubleArrayOf(0.0, -9.81, 0.0); 3 -> doubleArrayOf(9.81, 0.0, 0.0)
                else -> doubleArrayOf(0.0, 9.81, 0.0) }
            g.gravity(gravity[0], gravity[1], gravity[2])
            val pitch = when (rotation) { 1 -> doubleArrayOf(0.0, 1.0, 0.0)
                2 -> doubleArrayOf(-1.0, 0.0, 0.0); 3 -> doubleArrayOf(0.0, -1.0, 0.0)
                else -> doubleArrayOf(1.0, 0.0, 0.0) }
            val delta = g.sample(origin + 10_000_000, pitch[0], pitch[1], pitch[2], rotation, direct)
            assertEquals(0.0, delta.x, .00001); assertEquals(-11.88, delta.y, .00001)
        }
    }
    @Test fun rolledPhoneSeparatesYawFromPitch() {
        val g = ready().apply { gravity(1.0, 1.0, 0.0) }
        val delta = g.sample(origin + 10_000_000, 1.0, 1.0, 0.0, 0, direct)
        assertTrue(delta.x < 0); assertEquals(0.0, delta.y, .00001)
    }
    @Test fun integrationUsesElapsedTimeRatherThanEventCount() {
        fun total(interval: Long, count: Int): Double {
            val g = ready()
            return (1..count).sumOf { g.sample(origin + it * interval, 0.0, -.5, 0.0, 0, direct).x }
        }
        assertEquals(total(10_000_000, 10), total(20_000_000, 5), .00001)
    }
    @Test fun smallStationaryNoiseDoesNotDrift() {
        val g = ready()
        for (i in 1..100) assertEquals(GyroDelta(),
            g.sample(origin + i * 10_000_000L, .003, -.005, .002, 0, GyroSettings()))
    }
    @Test fun smoothingReducesJitterAndStopsWithoutATail() {
        val g = ready()
        val first = g.sample(origin + 10_000_000, 0.0, -1.0, 0.0, 0, direct.copy(smoothing = true))
        val second = g.sample(origin + 20_000_000, 0.0, -1.0, 0.0, 0, direct.copy(smoothing = true))
        assertTrue(first.x > 0 && first.x < second.x && second.x < 11.88)
        assertEquals(GyroDelta(), g.sample(origin + 30_000_000, 0.0, 0.0, 0.0, 0, GyroSettings()))
    }
    @Test fun pauseAndLongSamplingGapsCannotJump() {
        val g = ready()
        assertEquals(GyroDelta(), g.sample(origin + 300_000_000, 0.0, -1.0, 0.0, 0, direct))
        assertEquals(11.88, g.sample(origin + 310_000_000, 0.0, -1.0, 0.0, 0, direct).x, .00001)
        g.reset()
        assertEquals(GyroDelta(), g.sample(origin + 900_000_000, 0.0, -1.0, 0.0, 0, direct))
    }
    @Test fun duplicateSamplesAreNotIntegratedTwice() {
        val g = ready()
        val t = origin + 10_000_000
        g.sample(t, 0.0, -1.0, 0.0, 0, direct)
        assertEquals(GyroDelta(), g.sample(t, 0.0, -1.0, 0.0, 0, direct))
        assertEquals(11.88, g.sample(t + 10_000_000, 0.0, -1.0, 0.0, 0, direct).x, .00001)
    }
    @Test fun repositionIgnoresEveryReturnStrokeAndResumesWithoutCatchUp() {
        val g = ready()
        assertTrue(g.sample(origin + 10_000_000, 0.0, -1.0, 0.0, 0, direct).x > 0)
        g.setRepositioning(true)
        for (i in 2..100) assertEquals(GyroDelta(),
            g.sample(origin + i * 10_000_000L, 2.0, 3.0, -4.0, 0, direct))
        // A settings reset while held must not let any return motion through.
        g.reset()
        assertEquals(GyroDelta(), g.sample(origin + 1_010_000_000, 0.0, 5.0, 0.0, 0, direct))
        g.setRepositioning(false)
        assertEquals(GyroDelta(), g.sample(origin + 1_020_000_000, 0.0, -1.0, 0.0, 0, direct))
        assertEquals(11.88, g.sample(origin + 1_030_000_000, 0.0, -1.0, 0.0, 0, direct).x, .00001)
    }
    @Test fun quickRepositionClearsFilterAndDistanceAccelerationBeforeNextTurn() {
        val settings = GyroSettings(acceleration = PointerAccelerationSettings(PointerAccelerationMode.Distance))
        val g = ready()
        for (i in 1..80) g.sample(origin + i * 10_000_000L, 0.0, -1.5, 0.0, 0, settings)
        g.setRepositioning(true)
        g.sample(origin + 810_000_000, 0.0, 5.0, 0.0, 0, settings)
        g.setRepositioning(false)
        val fresh = GyroMotionController()
        for (i in 82..85) {
            val at = origin + i * 10_000_000L
            assertEquals(fresh.sample(at, 0.0, .08, 0.0, 0, settings),
                g.sample(at, 0.0, .08, 0.0, 0, settings))
        }
    }
    @Test fun repositionKeepsGyroModeAndBlackScreenInsteadOfEnablingTouchpadMotion() {
        val state = UiState(settings = ControlSettings(mouseControlMode = MouseControlMode.Gyroscope),
            connection = ConnectionState(phase = "connected"), gyroPaused = false,
            gyroActive = true, gyroBlocked = false, gyroRepositioning = true, gyroBlackScreen = true)
        assertTrue(state.gyroRepositionEnabled)
        assertTrue(state.canEnterBlackScreen)
        assertFalse(state.touchpadMotionEnabled)
        assertFalse(state.copy(gyroPaused = true).gyroRepositionEnabled)
        assertFalse(state.copy(gyroActive = false).gyroRepositionEnabled)
        assertFalse(state.copy(gyroBlocked = true).gyroRepositionEnabled)
        assertFalse(state.copy(connection = ConnectionState()).gyroRepositionEnabled)
        assertFalse(state.copy(settings = state.settings.copy(mouseControlMode = MouseControlMode.Touchpad)).gyroRepositionEnabled)
    }
    @Test fun invalidSensorsDoNotContaminateLaterMovement() {
        val g = ready()
        assertFalse(g.gravity(Double.NaN, 1.0, 0.0)); assertFalse(g.gravity(0.0, 0.0, 0.0))
        assertEquals(GyroDelta(), g.sample(origin + 10_000_000, Double.NaN, -1.0, 0.0, 0, direct))
        assertEquals(GyroDelta(), g.sample(origin + 20_000_000, 0.0, -1.0, 0.0, 0, direct))
        assertEquals(11.88, g.sample(origin + 30_000_000, 0.0, -1.0, 0.0, 0, direct).x, .00001)
    }
    @Test fun sensitivityAndAxisReversalAreIndependent() {
        val a = ready().sample(origin + 10_000_000, 1.0, -1.0, 0.0, 0, direct)
        val b = ready().sample(origin + 10_000_000, 1.0, -1.0, 0.0, 0,
            direct.copy(sensitivity = 2f, reverseX = true, reverseY = true))
        assertEquals(-2 * a.x, b.x, .00001); assertEquals(-2 * a.y, b.y, .00001)
        assertEquals(1f, GyroSettings(sensitivity = Float.NaN).sanitized().sensitivity)
    }
    @Test fun extremeRatesRemainBounded() {
        val delta = ready().sample(origin + 100_000_000, 1000.0, -1000.0, 0.0, 0, direct)
        assertEquals(600.0, kotlin.math.hypot(delta.x, delta.y), .00001)
        assertEquals(delta.x, -delta.y, .00001)
        val fast = ready().sample(origin + 10_000_000, 1000.0, -1000.0, 0.0, 0, direct)
        assertEquals(10 * fast.x, delta.x, .00001)
    }
    @Test fun dynamicSmoothingRespondsFasterToFastTurnsAndNeverContinuesInTheOldDirection() {
        val settings = direct.copy(smoothing = true)
        val slow = ready().sample(origin + 10_000_000, 0.0, -.06, 0.0, 0, settings).x / (.05 * 12)
        val fastController = ready()
        val fast = fastController.sample(origin + 10_000_000, 0.0, -1.5, 0.0, 0, settings).x / (1.49 * 12)
        assertTrue(fast > slow * 2)
        assertTrue(fastController.sample(origin + 20_000_000, 0.0, .06, 0.0, 0, settings).x < 0)
    }
    @Test fun bothAccelerationModesIntegrateEquallyAcrossSensorSampleRates() {
        for (mode in listOf(PointerAccelerationMode.Speed, PointerAccelerationMode.Distance)) {
            val settings = direct.copy(acceleration = PointerAccelerationSettings(mode))
            fun total(interval: Long, count: Int): Double {
                val g = ready()
                return (1..count).sumOf { g.sample(origin + it * interval, 0.0, -.8, 0.0, 0, settings).x }
            }
            assertEquals(total(10_000_000, 100), total(20_000_000, 50), .00001)
        }
    }
    @Test fun disconnectedBackgroundBlockedOrPausedStatesNeverRun() {
        assertTrue(shouldRunGyro(MouseControlMode.Gyroscope, false, true, true, false))
        assertFalse(shouldRunGyro(MouseControlMode.Touchpad, false, true, true, false))
        assertFalse(shouldRunGyro(MouseControlMode.Gyroscope, true, true, true, false))
        assertFalse(shouldRunGyro(MouseControlMode.Gyroscope, false, false, true, false))
        assertFalse(shouldRunGyro(MouseControlMode.Gyroscope, false, true, false, false))
        assertFalse(shouldRunGyro(MouseControlMode.Gyroscope, false, true, true, true))
        assertEquals(MouseControlMode.Touchpad, MouseControlMode.fromCode("unknown"))
    }
}
