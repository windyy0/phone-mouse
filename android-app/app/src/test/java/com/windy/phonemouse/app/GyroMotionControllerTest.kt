package com.windy.phonemouse.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class GyroMotionControllerTest {
    private val direct = GyroSettings(sensitivity = 1f, smoothing = false,
        acceleration = PointerAccelerationSettings(mode = PointerAccelerationMode.Off))
    private val origin = 1_000_000_000L
    private fun ready() = GyroMotionController().apply { sample(origin, 0.0, 0.0, 0.0, 0, direct) }
    private fun phoneVector(rotation: Int, right: Double, top: Double, normal: Double) = when (rotation) {
        1 -> doubleArrayOf(-top, right, normal)
        2 -> doubleArrayOf(-right, -top, normal)
        3 -> doubleArrayOf(top, -right, normal)
        else -> doubleArrayOf(right, top, normal)
    }

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
    @Test fun flatPhoneSideTiltFollowsScreenTopAndRightAxesInEveryDisplayRotation() {
        val settings = direct.copy(directionMode = GyroDirectionMode.Twist)
        val rollAxes = arrayOf(doubleArrayOf(0.0, 1.0), doubleArrayOf(-1.0, 0.0),
            doubleArrayOf(0.0, -1.0), doubleArrayOf(1.0, 0.0))
        val pitchAxes = arrayOf(doubleArrayOf(1.0, 0.0), doubleArrayOf(0.0, 1.0),
            doubleArrayOf(-1.0, 0.0), doubleArrayOf(0.0, -1.0))
        for (rotation in 0..3) {
            val roll = rollAxes[rotation]
            val pitch = pitchAxes[rotation]
            val g = ready().apply { gravity(0.0, 0.0, 9.81) }
            val right = g.sample(origin + 10_000_000, roll[0], roll[1], 0.0, rotation, settings)
            assertEquals(11.88, right.x, .00001); assertEquals(0.0, right.y, .00001)
            val left = g.sample(origin + 20_000_000, -roll[0], -roll[1], 0.0, rotation, settings)
            assertEquals(-11.88, left.x, .00001); assertEquals(0.0, left.y, .00001)
            val up = g.sample(origin + 30_000_000, pitch[0], pitch[1], 0.0, rotation, settings)
            assertEquals(0.0, up.x, .00001); assertEquals(-11.88, up.y, .00001)
            val down = g.sample(origin + 40_000_000, -pitch[0], -pitch[1], 0.0, rotation, settings)
            assertEquals(0.0, down.x, .00001); assertEquals(11.88, down.y, .00001)
        }
    }
    @Test fun uprightPhoneLeaningLeftAndRightMovesInTheSameDirection() {
        val settings = direct.copy(directionMode = GyroDirectionMode.Twist)
        for (rotation in 0..3) {
            val posture = phoneVector(rotation, 0.0, 9.81, 0.0)
            val g = ready().apply { gravity(posture[0], posture[1], posture[2]) }
            // Counterclockwise around the screen normal: the top leans left.
            val left = g.sample(origin + 10_000_000, 0.0, 0.0, 1.0, rotation, settings)
            assertEquals(-11.88, left.x, .00001); assertEquals(0.0, left.y, .00001)
            val right = g.sample(origin + 20_000_000, 0.0, 0.0, -1.0, rotation, settings)
            assertEquals(11.88, right.x, .00001); assertEquals(0.0, right.y, .00001)
        }
    }
    @Test fun sideTiltAdaptsToHoldingAngleAndKeepsPitchIndependentAfterBanking() {
        val settings = direct.copy(directionMode = GyroDirectionMode.Twist)
        for (holdingDegrees in listOf(0, 20, 45, 80, 90, 135, 180))
            for (bankDegrees in listOf(-60, -30, 0, 30, 60)) for (rotation in 0..3) {
            val holding = holdingDegrees * PI / 180.0
            val bank = bankDegrees * PI / 180.0
            // A leaned phone's screen-right axis gains an elevation of bankDegrees.
            val posture = phoneVector(rotation, sin(bank), cos(bank) * sin(holding), cos(bank) * cos(holding))
            val leftLean = phoneVector(rotation, 0.0, -cos(holding), sin(holding))
            val upPitch = phoneVector(rotation, cos(bank), -sin(bank) * sin(holding), -sin(bank) * cos(holding))
            val g = ready().apply { gravity(posture[0], posture[1], posture[2]) }
            val left = g.sample(origin + 10_000_000, leftLean[0], leftLean[1], leftLean[2], rotation, settings)
            assertEquals(-11.88, left.x, .00001); assertEquals(0.0, left.y, .00001)
            val up = g.sample(origin + 20_000_000, upPitch[0], upPitch[1], upPitch[2], rotation, settings)
            assertEquals(0.0, up.x, .00001); assertEquals(-11.88, up.y, .00001)
            val movement = DoubleArray(3) { upPitch[it] - .5 * leftLean[it] }
            val delta = g.sample(origin + 30_000_000, movement[0], movement[1], movement[2], rotation, settings)
            assertEquals(5.88, delta.x, .00001); assertEquals(-11.88, delta.y, .00001)
            val reversed = g.sample(origin + 40_000_000, movement[0], movement[1], movement[2], rotation,
                settings.copy(sensitivity = 2f, reverseX = true, reverseY = true))
            assertEquals(-2 * delta.x, reversed.x, .00001); assertEquals(-2 * delta.y, reversed.y, .00001)
        }
    }
    @Test fun sideTiltIgnoresHeadingAroundGravityForFlatUprightAndBankedPhones() {
        for (posture in listOf(doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(.6, .48, .64))) for (rotation in 0..3) for (direction in listOf(-1.0, 1.0)) {
            val g = ready().apply { gravity(posture[0], posture[1], posture[2]) }
            val movement = DoubleArray(3) { posture[it] * direction }
            assertEquals(GyroDelta(), g.sample(origin + 10_000_000, movement[0], movement[1], movement[2], rotation,
                direct.copy(directionMode = GyroDirectionMode.Twist)))
            assertEquals(-direction * 11.88,
                g.sample(origin + 20_000_000, movement[0], movement[1], movement[2], rotation, direct).x, .00001)
        }
    }
    @Test fun sideTiltIntegratesChangingGravityAndStopsWhenPhoneIsHeldStill() {
        val settings = direct.copy(directionMode = GyroDirectionMode.Twist)
        for (holding in listOf(0.0, PI / 4, PI / 2)) for (rotation in 0..3) for (direction in listOf(-1.0, 1.0)) {
            fun total(interval: Long, count: Int): Double {
                val g = ready()
                var distance = 0.0
                val movement = phoneVector(rotation, 0.0, direction * .6 * cos(holding), -direction * .6 * sin(holding))
                for (i in 1..count) {
                    val bank = -direction * .6 * i * interval / 1_000_000_000.0
                    val posture = phoneVector(rotation, sin(bank), cos(bank) * sin(holding), cos(bank) * cos(holding))
                    g.gravity(posture[0], posture[1], posture[2])
                    distance += g.sample(origin + i * interval, movement[0], movement[1], movement[2], rotation, settings).x
                }
                assertEquals(GyroDelta(), g.sample(origin + (count + 1) * interval, 0.0, 0.0, 0.0, rotation, settings))
                return distance
            }
            assertEquals(direction * .59 * 1200 * .4, total(10_000_000, 40), .00001)
            assertEquals(total(10_000_000, 40), total(20_000_000, 20), .00001)
        }
    }
    @Test fun sideTiltCannotJumpWhenTheScreenRightEdgePointsStraightUpOrDown() {
        val settings = direct.copy(directionMode = GyroDirectionMode.Twist)
        for (rotation in 0..3) for (direction in listOf(-1.0, 1.0)) {
            val g = ready()
            for ((i, lean) in listOf(1.0, .999).withIndex()) {
                val posture = phoneVector(rotation, direction * lean, 0.0, sqrt(1.0 - lean * lean))
                g.gravity(posture[0], posture[1], posture[2])
                assertEquals(GyroDelta(), g.sample(origin + (i + 1) * 10_000_000L, 1.0, 1.0, 1.0, rotation, settings))
            }
            g.gravity(0.0, 0.0, 9.81)
            val movement = phoneVector(rotation, 0.0, 1.0, 0.0)
            assertEquals(11.88,
                g.sample(origin + 30_000_000, movement[0], movement[1], movement[2], rotation, settings).x, .00001)
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
        assertEquals(2.5f, GyroSettings(sensitivity = Float.NaN).sanitized().sensitivity)
    }
    @Test fun baseSensitivityRangesClampToPointOneThroughTen() {
        assertEquals(.1f, ControlSettings(sensitivity = .01f).sanitized().sensitivity, 0f)
        assertEquals(10f, ControlSettings(sensitivity = 12f).sanitized().sensitivity, 0f)
        assertEquals(2.5f, ControlSettings(sensitivity = Float.NaN).sanitized().sensitivity, 0f)
        assertEquals(.1f, GyroSettings(sensitivity = .01f).sanitized().sensitivity, 0f)
        assertEquals(10f, GyroSettings(sensitivity = 12f).sanitized().sensitivity, 0f)
        assertEquals(2.5f, GyroSettings(sensitivity = Float.NaN).sanitized().sensitivity, 0f)
        assertEquals(2.5f, ControlSettings().sensitivity, 0f)
        assertEquals(2.5f, GyroSettings().sensitivity, 0f)
    }
    @Test fun fullscreenAndGyroModesHaveSafeDefaultsAndAngles() {
        assertEquals(FullscreenRotationMode.FollowSystem, FullscreenRotationMode.fromCode("unknown"))
        assertEquals(GyroDirectionMode.Tilt, GyroDirectionMode.fromCode("unknown"))
        assertEquals(0, ControlSettings(fullscreenRotation = 45).sanitized().fullscreenRotation)
        assertEquals(270, ControlSettings(fullscreenRotation = 270).sanitized().fullscreenRotation)
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
