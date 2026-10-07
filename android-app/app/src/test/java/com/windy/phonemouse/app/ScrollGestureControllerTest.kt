package com.windy.phonemouse.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollGestureControllerTest {
    private fun continuous(): ScrollGestureController = ScrollGestureController().apply {
        start(0f, 100f, 1000L, allowContinuous = true)
        assertTrue(tryStartContinuous(1450L))
    }

    @Test fun directDragKeepsDistanceAndDirection() {
        val gesture = ScrollGestureController()
        gesture.start(0f, 100f, 1000L, allowContinuous = true)
        assertEquals(-120.0, gesture.move(0f, 118f), .0001)
        assertEquals(60.0, gesture.move(0f, 109f), .0001)
        assertEquals(0.0, gesture.move(0f, 109f), 0.0)
        assertEquals(0.0, gesture.frame(2000L), 0.0)
        assertFalse(gesture.tryStartContinuous(2000L))
    }

    @Test fun dragCannotRearmAfterReturningToOrigin() {
        val gesture = ScrollGestureController()
        gesture.start(0f, 100f, 1000L, allowContinuous = true)
        gesture.move(0f, 107f)
        gesture.move(0f, 100f)
        assertEquals(ScrollGestureController.Mode.Direct, gesture.mode)
        assertFalse(gesture.tryStartContinuous(2000L))
    }

    @Test fun longPressRequires450msAndAcceptsSmallJitter() {
        val gesture = ScrollGestureController()
        gesture.start(0f, 100f, 1000L, allowContinuous = true)
        gesture.move(6f, 106f)
        assertFalse(gesture.tryStartContinuous(1449L))
        assertTrue(gesture.tryStartContinuous(1450L))
        assertFalse(gesture.tryStartContinuous(1451L))
        assertEquals(0.0, gesture.frame(1500L), 0.0)
    }

    @Test fun horizontalDragAlsoCancelsLongPress() {
        val gesture = ScrollGestureController()
        gesture.start(0f, 100f, 1000L, allowContinuous = true)
        gesture.move(7f, 100f)
        assertFalse(gesture.tryStartContinuous(1450L))
    }

    @Test fun arrowZonesNeverEnterContinuousMode() {
        val gesture = ScrollGestureController()
        gesture.start(0f, 10f, 1000L, allowContinuous = false)
        assertFalse(gesture.tryStartContinuous(2000L))
        assertEquals(-120.0, gesture.move(0f, 28f), .0001)
    }

    @Test fun continuousModeHasEightDpDeadZoneInBothDirections() {
        val gesture = continuous()
        for (y in listOf(100f, 108f, 92f)) {
            assertEquals(0.0, gesture.move(0f, y), 0.0)
            assertEquals(0.0, gesture.frame(1500L), 0.0)
        }
    }

    @Test fun amplitudeControlsSquaredSpeedAndClampsAtMaximum() {
        val gesture = continuous()
        gesture.move(0f, 148f)
        assertEquals(-30.0, gesture.frame(1500L), .0001)
        gesture.move(0f, 188f)
        assertEquals(-120.0, gesture.frame(1550L), .0001)
        gesture.move(0f, 300f)
        assertEquals(-120.0, gesture.frame(1600L), .0001)
    }

    @Test fun heldDisplacementKeepsScrollingAndCanReverseOrPause() {
        val gesture = continuous()
        gesture.move(0f, 188f)
        assertEquals(-120.0, gesture.frame(1500L), .0001)
        assertEquals(-120.0, gesture.frame(1550L), .0001)
        gesture.move(0f, 12f)
        assertEquals(120.0, gesture.frame(1600L), .0001)
        gesture.move(0f, 100f)
        assertEquals(0.0, gesture.frame(1650L), 0.0)
    }

    @Test fun scrollAmountUsesElapsedTimeRatherThanFrameCount() {
        val fastFrames = continuous()
        val slowFrames = continuous()
        fastFrames.move(0f, 188f)
        slowFrames.move(0f, 188f)
        val amount = fastFrames.frame(1466L) + fastFrames.frame(1500L)
        assertEquals(slowFrames.frame(1500L), amount, .0001)
    }

    @Test fun delayedFrameDoesNotCatchUpWithLargeBurst() {
        val gesture = continuous()
        gesture.move(0f, 188f)
        assertEquals(-120.0, gesture.frame(2450L), .0001)
        assertEquals(-38.4, gesture.frame(2466L), .0001)
    }

    @Test fun stopDropsStateAndRestartNeedsNewLongPress() {
        val gesture = continuous()
        gesture.move(0f, 188f)
        gesture.stop()
        assertEquals(ScrollGestureController.Mode.Idle, gesture.mode)
        assertEquals(0f, gesture.displacementDp, 0f)
        assertEquals(0.0, gesture.frame(1500L), 0.0)
        assertEquals(0.0, gesture.move(0f, 200f), 0.0)
        assertFalse(gesture.tryStartContinuous(2000L))
        gesture.start(0f, 100f, 3000L, allowContinuous = true)
        assertFalse(gesture.tryStartContinuous(3449L))
        assertTrue(gesture.tryStartContinuous(3450L))
        assertEquals(0.0, gesture.frame(3500L), 0.0)
    }
}
