package com.windy.phonemouse.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticSettingsTest {
    @Test fun masterSwitchSuppressesEveryAction() {
        val settings = HapticSettings(enabled = false)
        HapticAction.entries.forEach { action ->
            assertNull(hapticSequence(settings, action, amplitudeControl = true))
            assertNull(hapticSequence(settings, action, amplitudeControl = false))
        }
    }

    @Test fun mutingOneActionLeavesTheOthersEnabled() {
        val settings = HapticSettings(rightClick = HapticPattern.Off)
        assertNull(hapticSequence(settings, HapticAction.RightClick, false))
        for (action in listOf(HapticAction.LeftClick, HapticAction.ScrollHold, HapticAction.Keyboard)) {
            assertNotNull(hapticSequence(settings, action, false))
        }
    }

    @Test fun strengthPreviewWorksWhenEveryOperationIsMuted() {
        val settings = HapticSettings(strength = HapticStrength.Custom, customPulseMs = 28,
            customAmplitudePercent = 40, leftClick = HapticPattern.Off, rightClick = HapticPattern.Off,
            scroll = HapticPattern.Off, scrollHold = HapticPattern.Off, keyboard = HapticPattern.Off,
            backspace = HapticPattern.Off)
        HapticAction.entries.filter { it != HapticAction.StrengthPreview }.forEach {
            assertNull(hapticSequence(settings, it, false))
        }
        val basic = hapticSequence(settings, HapticAction.StrengthPreview, false)!!
        val capable = hapticSequence(settings, HapticAction.StrengthPreview, true)!!
        assertArrayEquals(longArrayOf(0, 28), basic.timings)
        assertArrayEquals(longArrayOf(0, 15), capable.timings)
        assertEquals(46, capable.amplitudes[1])
        assertNull(hapticSequence(settings.copy(enabled = false), HapticAction.StrengthPreview, false))
    }

    @Test fun wheelFeedbackAndHoldConfirmationHaveSeparateSettings() {
        val mutedWheel = HapticSettings(scroll = HapticPattern.Off, scrollHold = HapticPattern.Double)
        assertNull(hapticSequence(mutedWheel, HapticAction.Scroll, false))
        assertArrayEquals(longArrayOf(0, 20, 40, 20),
            hapticSequence(mutedWheel, HapticAction.ScrollHold, false)!!.timings)
        val mutedHold = mutedWheel.copy(scroll = HapticPattern.Light, scrollHold = HapticPattern.Off)
        assertArrayEquals(longArrayOf(0, 15), hapticSequence(mutedHold, HapticAction.Scroll, false)!!.timings)
        assertNull(hapticSequence(mutedHold, HapticAction.ScrollHold, false))
    }

    @Test fun backspaceFeedbackIsIndependentFromOtherKeyboardKeys() {
        val settings = HapticSettings(keyboard = HapticPattern.Off, backspace = HapticPattern.Double)
        assertNull(hapticSequence(settings, HapticAction.Keyboard, false))
        assertArrayEquals(longArrayOf(0, 20, 40, 20),
            hapticSequence(settings, HapticAction.Backspace, false)!!.timings)
        assertNull(hapticSequence(settings.copy(backspace = HapticPattern.Off), HapticAction.Backspace, false))
    }

    @Test fun wheelCooldownSuppressesFrameUpdatesAndLetsLongEffectsFinish() {
        val gate = ScrollHapticGate()
        assertTrue(gate.allows(1000))
        gate.record(1000, 15)
        for (time in listOf(1001L, 1016L, 1100L, 1159L)) assertTrue(!gate.allows(time))
        assertTrue(gate.allows(1160))
        // A long double pulse or hold confirmation must not be interrupted by a wheel tick.
        gate.record(1160, 200)
        assertTrue(!gate.allows(1399))
        assertTrue(gate.allows(1400))
        gate.record(1400, 15)
        gate.reset()
        assertTrue(gate.allows(1401))
    }

    @Test fun wheelTicksRequireOneAccumulatedNotchInsteadOfElapsedTime() {
        val gate = ScrollHapticGate()
        assertTrue(!gate.shouldPlayScroll(60.0, 1000))
        assertTrue(!gate.shouldPlayScroll(59.0, 2000))
        assertTrue(gate.shouldPlayScroll(1.0, 3000))
        gate.record(3000, 15)
        assertTrue(!gate.shouldPlayScroll(0.0, 4000))
        assertTrue(!gate.shouldPlayScroll(119.0, 5000))
        assertTrue(gate.shouldPlayScroll(1.0, 6000))
    }

    @Test fun changingWheelDirectionStartsANewPartialNotch() {
        val gate = ScrollHapticGate()
        assertTrue(!gate.shouldPlayScroll(60.0, 1000))
        assertTrue(!gate.shouldPlayScroll(-60.0, 1100))
        assertTrue(gate.shouldPlayScroll(-60.0, 1200))
        gate.record(1200, 15)
        assertTrue(gate.shouldPlayScroll(120.0, 1400))
    }

    @Test fun fastWheelCrossingsAreCoalescedWithoutQueuedTicks() {
        val gate = ScrollHapticGate()
        assertTrue(gate.shouldPlayScroll(600.0, 1000))
        gate.record(1000, 15)
        assertTrue(!gate.shouldPlayScroll(120.0, 1016))
        // Five fast notches and the suppressed notch are consumed; a later tiny move is silent.
        assertTrue(!gate.shouldPlayScroll(1.0, 1160))
        assertTrue(gate.shouldPlayScroll(119.0, 1161))
    }

    @Test fun wheelProgressIgnoresInvalidInputAndResetsWithTheMotor() {
        val gate = ScrollHapticGate()
        assertTrue(!gate.shouldPlayScroll(60.0, 1000))
        for (delta in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0)) {
            assertTrue(!gate.shouldPlayScroll(delta, 1100))
        }
        assertTrue(gate.shouldPlayScroll(60.0, 1200))
        gate.record(1200, 15)
        assertTrue(!gate.shouldPlayScroll(60.0, 1210))
        gate.reset()
        assertTrue(!gate.shouldPlayScroll(60.0, 1220))
        assertTrue(gate.shouldPlayScroll(60.0, 1230))
    }

    @Test fun defaultFeedbackUsesSinglePulsesAndDoubleRightClick() {
        val settings = HapticSettings()
        val left = hapticSequence(settings, HapticAction.LeftClick, false)!!
        val right = hapticSequence(settings, HapticAction.RightClick, false)!!
        val hold = hapticSequence(settings, HapticAction.ScrollHold, false)!!
        assertEquals(2, left.timings.size)
        assertEquals(2, hold.timings.size)
        assertArrayEquals(left.timings, hold.timings)
        assertEquals(4, right.timings.size)
        assertTrue(right.timings[2] > 0L)
        assertEquals(right.timings[1], right.timings[3])
        assertArrayEquals(intArrayOf(0, -1, 0, -1), right.amplitudes)
    }

    @Test fun capableMotorsChangeAmplitudeWithoutStretchingThePulse() {
        val pulses = HapticStrength.entries.filter { it != HapticStrength.Custom }.map { strength ->
            hapticSequence(HapticSettings(strength = strength), HapticAction.ScrollHold, true)!!
        }
        assertTrue(pulses.zipWithNext().all { (first, second) ->
            first.amplitudes[1] < second.amplitudes[1]
        })
        pulses.forEach { assertArrayEquals(pulses.first().timings, it.timings) }
    }

    @Test fun basicMotorsUseDurationInsteadOfUnsupportedAmplitudes() {
        val pulses = HapticStrength.entries.filter { it != HapticStrength.Custom }.map { strength ->
            hapticSequence(HapticSettings(strength = strength), HapticAction.ScrollHold, false)!!
        }
        assertTrue(pulses.zipWithNext().all { (first, second) ->
            first.timings[1] < second.timings[1]
        })
        pulses.forEach { assertArrayEquals(intArrayOf(0, -1), it.amplitudes) }
    }

    @Test fun everyFallbackIsValidAndShortEvenAtMaximumStrength() {
        for (pattern in HapticPattern.entries.filter { it != HapticPattern.Off }) {
            for (strength in HapticStrength.entries) {
                for (amplitudeControl in listOf(false, true)) {
                    val sequence = hapticSequence(HapticSettings(strength = strength, leftClick = pattern),
                        HapticAction.LeftClick, amplitudeControl)!!
                    assertEquals(sequence.timings.size, sequence.amplitudes.size)
                    assertTrue(sequence.timings.all { it >= 0L })
                    assertTrue(sequence.timings.sum() in 1L..200L)
                    assertTrue(sequence.amplitudes.filterIndexed { index, _ -> index % 2 == 1 }
                        .all { if (amplitudeControl) it in 1..255 else it == -1 })
                    assertTrue(sequence.amplitudes.filterIndexed { index, _ -> index % 2 == 0 }.all { it == 0 })
                }
            }
        }
    }

    @Test fun missingOrUnknownPreferencesKeepSafeDefaults() {
        assertEquals(HapticStrength.Normal, HapticStrength.fromCode(null))
        assertEquals(HapticStrength.Normal, HapticStrength.fromCode("future-strength"))
        assertEquals(HapticPattern.Light, HapticPattern.fromCode(null, HapticPattern.Light))
        assertEquals(HapticPattern.Double, HapticPattern.fromCode("future-pattern", HapticPattern.Double))
        assertEquals(HapticPattern.Off, HapticPattern.fromCode("off", HapticPattern.Light))
    }

    @Test fun legacySinglePulsePatternsMigrateWithoutMutingFeedback() {
        for (legacy in listOf("click", "confirm")) {
            assertEquals(HapticPattern.Light, HapticPattern.fromCode(legacy, HapticPattern.Off))
        }
        assertEquals(listOf("off", "light", "double"), HapticPattern.entries.map { it.code })
        assertEquals(HapticStrength.Custom, HapticStrength.fromCode("custom"))
    }

    @Test fun customBasicMotorDurationIsExactForEachPulse() {
        for (duration in listOf(1, 15, 80)) {
            val settings = HapticSettings(strength = HapticStrength.Custom, customPulseMs = duration)
            val single = hapticSequence(settings, HapticAction.LeftClick, false)!!
            val double = hapticSequence(settings, HapticAction.RightClick, false)!!
            assertArrayEquals(longArrayOf(0, duration.toLong()), single.timings)
            assertArrayEquals(longArrayOf(0, duration.toLong(), 40, duration.toLong()), double.timings)
            assertArrayEquals(intArrayOf(0, -1, 0, -1), double.amplitudes)
            assertTrue(double.timings.sum() <= 200)
        }
    }

    @Test fun customAmplitudeChangesStrengthWithoutChangingPulseTiming() {
        val pulses = listOf(1, 40, 100).map { percent ->
            hapticSequence(HapticSettings(strength = HapticStrength.Custom, customAmplitudePercent = percent,
                customPulseMs = 80), HapticAction.LeftClick, true)!!
        }
        assertTrue(pulses.zipWithNext().all { (first, second) -> first.amplitudes[1] < second.amplitudes[1] })
        pulses.forEach { assertArrayEquals(longArrayOf(0, 15), it.timings) }
        val preset = hapticSequence(HapticSettings(strength = HapticStrength.Normal), HapticAction.LeftClick, true)!!
        val custom = hapticSequence(HapticSettings(strength = HapticStrength.Custom, customAmplitudePercent = 75),
            HapticAction.LeftClick, true)!!
        assertArrayEquals(preset.amplitudes, custom.amplitudes)
    }

    @Test fun invalidCustomValuesStayWithinSupportedBounds() {
        val low = HapticSettings(strength = HapticStrength.Custom, customAmplitudePercent = -20, customPulseMs = -10)
        val high = HapticSettings(strength = HapticStrength.Custom, customAmplitudePercent = 500, customPulseMs = 1000)
        assertEquals(1, low.sanitized().customAmplitudePercent)
        assertEquals(1, low.sanitized().customPulseMs)
        assertEquals(100, high.sanitized().customAmplitudePercent)
        assertEquals(80, high.sanitized().customPulseMs)
        assertEquals(1L, hapticSequence(low, HapticAction.LeftClick, false)!!.timings[1])
        assertEquals(80L, hapticSequence(high, HapticAction.LeftClick, false)!!.timings[1])
        assertTrue(hapticSequence(low, HapticAction.LeftClick, true)!!.amplitudes[1] > 0)
        assertNull(hapticSequence(high.copy(enabled = false), HapticAction.LeftClick, false))
        assertNull(hapticSequence(high.copy(leftClick = HapticPattern.Off), HapticAction.LeftClick, true))
    }
}
