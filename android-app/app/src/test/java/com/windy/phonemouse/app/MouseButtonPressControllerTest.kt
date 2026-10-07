package com.windy.phonemouse.app

import org.junit.Assert.*
import org.junit.Test

class MouseButtonPressControllerTest {
    private class Harness {
        val events = mutableListOf<String>()
        val controller = MouseButtonPressController({ events += "down" }, { events += "up" })
    }

    @Test fun downIsImmediateAndHoldHasNoClickOrRepeat() {
        val h = Harness()
        assertTrue(h.controller.press(1))
        assertEquals(listOf("down"), h.events)
        assertFalse(h.controller.press(1))
        assertFalse(h.controller.press(2))
        assertEquals(listOf("down"), h.events)
        h.controller.release(1)
        assertEquals(listOf("down", "up"), h.events)
    }

    @Test fun anotherFingerCannotReleaseTheOwner() {
        val h = Harness(); h.controller.press(11)
        h.controller.release(12)
        assertEquals(11L, h.controller.owner)
        assertEquals(listOf("down"), h.events)
        h.controller.release(11); assertNull(h.controller.owner)
    }

    @Test fun cancellationAndLateUpReleaseExactlyOnce() {
        val h = Harness(); h.controller.press(1)
        h.controller.cancel(); h.controller.cancel(); h.controller.release(1)
        assertEquals(listOf("down", "up"), h.events)
        assertTrue(h.controller.press(2)); h.controller.release(2)
        assertEquals(listOf("down", "up", "down", "up"), h.events)
    }

    @Test fun accessibilityActivationIsOneDownUpAndCannotReleaseAHeldFinger() {
        val h = Harness(); assertTrue(h.controller.activate())
        assertEquals(listOf("down", "up"), h.events)
        h.controller.press(1); assertFalse(h.controller.activate())
        assertEquals(listOf("down", "up", "down"), h.events)
        h.controller.release(1)
    }
}
