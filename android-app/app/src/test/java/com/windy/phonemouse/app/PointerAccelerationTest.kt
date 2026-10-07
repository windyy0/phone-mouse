package com.windy.phonemouse.app

import org.junit.Assert.*
import org.junit.Test

class PointerAccelerationTest {
    private val origin = 1_000_000_000L
    private val speed = PointerAccelerationSettings(PointerAccelerationMode.Speed)
    private val distance = PointerAccelerationSettings(PointerAccelerationMode.Distance)
    private fun ready(kind: PointerInputKind = PointerInputKind.Touchpad) =
        PointerAccelerationController(kind).apply { begin(origin) }

    @Test fun disabledAccelerationPreservesFineAndFastMovement() {
        val settings = speed.copy(mode = PointerAccelerationMode.Off)
        val controller = ready()
        assertEquals(PointerDelta(.02, -.03), controller.move(.02, -.03, origin + 10_000_000, settings))
        assertEquals(PointerDelta(150.0, -200.0), controller.move(150.0, -200.0, origin + 20_000_000, settings))
    }

    @Test fun speedAccelerationMakesTheSameDistanceTravelFurtherWhenFaster() {
        val slow = ready().move(10.0, 0.0, origin + 100_000_000, speed).x
        val fast = ready().move(10.0, 0.0, origin + 10_000_000, speed).x
        assertTrue(slow < 10.0); assertTrue(fast > slow * 2)
    }

    @Test fun speedCurvePreservesDirectionAndHasABoundedMaximum() {
        val settings = speed.copy(strength = PointerAccelerationStrength.Strong)
        val delta = ready().move(300.0, 400.0, origin + 1_000_000, settings)
        assertEquals(3.4, delta.x / 300, .000001)
        assertEquals(delta.x / 300, delta.y / 400, .000001)
    }

    @Test fun steadySpeedDoesNotDependOnSampleRateOrDeliveryBatches() {
        fun total(interval: Long, count: Int): Double {
            val controller = ready()
            return (1..count).sumOf { controller.move(350.0 * interval / 1e9, 0.0, origin + interval * it, speed).x }
        }
        assertEquals(total(4_000_000, 100), total(20_000_000, 20), .000001)
    }

    @Test fun distanceAccelerationIncreasesWithinAStrokeAtTheSameSpeed() {
        val controller = ready()
        val early = controller.move(10.0, 0.0, origin + 10_000_000, distance).x
        var late = early
        for (i in 2..20) late = controller.move(10.0, 0.0, origin + i * 10_000_000L, distance).x
        assertEquals(10.0, early, .000001); assertTrue(late > early * 2)
    }

    @Test fun distanceOutputIsIndependentOfSpeedAndSamplingGranularity() {
        fun total(step: Double, interval: Long, count: Int): Double {
            val controller = ready()
            return (1..count).sumOf { controller.move(step, 0.0, origin + interval * it, distance).x }
        }
        val reference = total(2.0, 4_000_000, 100)
        assertEquals(reference, total(10.0, 20_000_000, 20), .000001)
        assertEquals(reference, total(2.0, 20_000_000, 100), .000001)
    }

    @Test fun liftPauseAndDirectionChangeResetDistanceGain() {
        fun accelerated() = ready().apply { move(160.0, 0.0, origin + 100_000_000, distance) }
        val reversed = accelerated().move(-2.0, 0.0, origin + 110_000_000, distance)
        assertEquals(-2.0, reversed.x, .000001)
        assertEquals(2.0, accelerated().move(2.0, 0.0, origin + 300_000_000, distance).x, .000001)
        val lifted = accelerated().apply { begin(origin + 200_000_000) }
        assertEquals(2.0, lifted.move(2.0, 0.0, origin + 210_000_000, distance).x, .000001)
        assertEquals(2.0, accelerated().move(0.0, 2.0, origin + 110_000_000, distance).y, .000001)
    }

    @Test fun stationarySensorSamplesDoNotKeepAStrokeAlive() {
        val controller = ready(PointerInputKind.Gyroscope)
        controller.move(.5, 0.0, origin + 10_000_000, distance)
        for (i in 2..30) assertEquals(PointerDelta(), controller.move(0.0, 0.0, origin + i * 10_000_000L, distance))
        assertEquals(.002, controller.move(.002, 0.0, origin + 310_000_000, distance).x, .000001)
    }

    @Test fun gyroDistanceUsesAnglesAndKeepsShortCorrectionsUnaccelerated() {
        val controller = ready(PointerInputKind.Gyroscope)
        assertEquals(.02, controller.move(.02, 0.0, origin + 20_000_000, distance).x, .000001)
        controller.move(.4, 0.0, origin + 40_000_000, distance)
        assertEquals(.02 * 2.6, controller.move(.02, 0.0, origin + 60_000_000, distance).x, .000001)
        assertEquals(-.02, controller.move(-.02, 0.0, origin + 80_000_000, distance).x, .000001)
    }

    @Test fun profilesAndStrengthsAreIndependentAndSettingsChangesResetStrokeState() {
        val touch = ready(); val gyro = ready(PointerInputKind.Gyroscope)
        touch.move(160.0, 0.0, origin + 10_000_000, distance)
        assertEquals(.02, gyro.move(.02, 0.0, origin + 10_000_000, distance).x, .000001)
        assertEquals(2.0, touch.move(2.0, 0.0, origin + 20_000_000,
            distance.copy(strength = PointerAccelerationStrength.Light)).x, .000001)
        val strengths = PointerAccelerationStrength.entries.filter { it != PointerAccelerationStrength.Custom }.map {
            ready().move(100.0, 0.0, origin + 10_000_000, speed.copy(strength = it)).x
        }
        assertTrue(strengths.zipWithNext().all { (a, b) -> a < b })
    }

    @Test fun invalidAndDuplicateTimestampsNeverProduceAJumpOrLoseCoarseSamples() {
        val controller = ready()
        assertEquals(PointerDelta(), controller.move(Double.NaN, 0.0, origin + 10_000_000, speed))
        controller.begin(origin)
        val first = controller.move(4.0, 0.0, origin + 10_000_000, speed)
        assertEquals(first, controller.move(4.0, 0.0, origin + 10_000_000, speed))
        assertEquals(PointerDelta(), controller.move(100.0, 0.0, origin, speed))
        assertEquals(PointerAccelerationMode.Speed, PointerAccelerationMode.fromCode("unknown"))
        assertEquals(PointerAccelerationStrength.Medium, PointerAccelerationStrength.fromCode(null))
    }

    @Test fun customSpeedMinimumMaximumAndOnsetAreIndependent() {
        val curve = PointerAccelerationCurve(.6, 2.25, 200.0, 800.0)
        val settings = speed.copy(strength = PointerAccelerationStrength.Custom, customSpeed = curve)
        assertEquals(.6, ready().move(1.0, 0.0, origin + 10_000_000, settings).x, .000001)
        assertEquals(22.5, ready().move(10.0, 0.0, origin + 10_000_000, settings).x, .000001)
        val original = ready().move(4.0, 0.0, origin + 10_000_000, settings).x
        val delayed = ready().move(4.0, 0.0, origin + 10_000_000,
            settings.copy(customSpeed = curve.copy(start = 450.0))).x
        assertTrue(original > delayed)
        assertEquals(.6, delayed / 4, .000001)
    }

    @Test fun customDistanceRetainsPreciseStartAndIntegrationAcrossSampleRates() {
        val settings = distance.copy(strength = PointerAccelerationStrength.Custom,
            customDistance = PointerAccelerationCurve(.55, 2.25, 20.0, 120.0))
        assertEquals(5.5, ready().move(10.0, 0.0, origin + 10_000_000, settings).x, .000001)
        fun total(step: Double, count: Int): Double {
            val controller = ready()
            return (1..count).sumOf { controller.move(step, 0.0, origin + it * 10_000_000L, settings).x }
        }
        assertEquals(total(2.0, 100), total(10.0, 20), .000001)
        val controller = ready()
        controller.move(130.0, 0.0, origin + 10_000_000, settings)
        assertEquals(22.5, controller.move(10.0, 0.0, origin + 20_000_000, settings).x, .000001)
    }

    @Test fun selectingCustomCopiesThePresetAndRetainsSeparateModeParameters() {
        val strong = speed.copy(strength = PointerAccelerationStrength.Strong)
        val custom = strong.selectStrength(PointerAccelerationStrength.Custom, PointerInputKind.Gyroscope)
        assertEquals(strong.curve(PointerInputKind.Gyroscope), custom.curve(PointerInputKind.Gyroscope))
        val changed = custom.withCurve(custom.customSpeed!!.copy(maximum = 2.25))
        assertEquals(custom.customDistance, changed.customDistance)
        assertEquals(changed, changed.selectStrength(PointerAccelerationStrength.Light, PointerInputKind.Gyroscope)
            .selectStrength(PointerAccelerationStrength.Custom, PointerInputKind.Gyroscope))
    }

    @Test fun customParametersRoundTripAndInvalidCurvesCannotProduceNonFiniteOutput() {
        val valid = PointerAccelerationCurve(.65, 2.25, Math.toRadians(6.5), Math.toRadians(110.25))
        assertEquals(valid, PointerAccelerationCurve.decode(valid.encode()))
        assertNull(PointerAccelerationCurve.decode(".75,NaN,40,700"))
        assertNull(PointerAccelerationCurve.decode(".75,2.6,40"))
        for (kind in PointerInputKind.entries) for (mode in listOf(PointerAccelerationMode.Speed, PointerAccelerationMode.Distance)) {
            val invalid = PointerAccelerationCurve(Double.NaN, -1.0, 9000.0, -3.0)
            val settings = PointerAccelerationSettings(mode, PointerAccelerationStrength.Custom, invalid, invalid)
            val curve = settings.curve(kind)
            assertTrue(curve.end > curve.start && curve.maximum >= curve.minimum)
            val output = ready(kind).move(3.0, 4.0, origin + 10_000_000, settings)
            assertTrue(output.x.isFinite() && output.y.isFinite())
        }
    }
}
