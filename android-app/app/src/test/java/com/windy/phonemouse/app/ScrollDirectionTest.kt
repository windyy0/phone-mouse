package com.windy.phonemouse.app

import org.junit.Assert.*
import org.junit.Test

class ScrollDirectionTest {
    @Test fun defaultsReverseOnlyTwoFingerScrolling() {
        val settings = ControlSettings()
        assertEquals(-120.0, settings.scrollDelta(-120.0), 0.0)
        assertEquals(120.0, settings.scrollDelta(-120.0, twoFinger = true), 0.0)
    }

    @Test fun switchingTwoFingerDirectionDoesNotAffectWheel() {
        val original = ControlSettings(scrollSpeed = 1.5f)
        val changed = original.copy(reverseTwoFingerScroll = false)
        assertEquals(original.scrollDelta(-120.0), changed.scrollDelta(-120.0), 0.0)
        assertEquals(-original.scrollDelta(-120.0, true), changed.scrollDelta(-120.0, true), 0.0)
    }

    @Test fun switchingWheelDirectionDoesNotAffectTwoFingers() {
        val original = ControlSettings(scrollSpeed = .5f)
        val changed = original.copy(reverseScroll = true)
        assertEquals(original.scrollDelta(-120.0, true), changed.scrollDelta(-120.0, true), 0.0)
        assertEquals(-original.scrollDelta(-120.0), changed.scrollDelta(-120.0), 0.0)
    }

    @Test fun bothDirectionsShareSpeedAndKeepFractionalPrecision() {
        val settings = ControlSettings(scrollSpeed = 2f, reverseScroll = true, reverseTwoFingerScroll = false)
        assertEquals(1.0, settings.scrollDelta(-.5), 0.0)
        assertEquals(-1.0, settings.scrollDelta(-.5, true), 0.0)
    }
}
