package com.windy.phonemouse.app

import org.junit.Assert.*
import org.junit.Test

class PointerGestureControllerTest {
    private class Harness {
        val events = mutableListOf<String>()
        var x = 0.0
        var y = 0.0
        var anchor: Pair<Double, Double>? = null
        var feedback = 0
        val gesture = PointerGestureController(
            onMove = { dx, dy -> x += dx; y += dy; events += "move" },
            onTap = { events += "click" },
            onButtonDown = { anchor = x to y; events += "down" },
            onButtonUp = { events += "up" },
            onTapRecognized = { feedback++ },
        )
        fun firstTap() {
            gesture.down(100f, 100f, 1000L)
            gesture.up(100f, 100f, 1050L, true)
        }
    }

    @Test fun firstTapJitterDoesNotShiftDesktopCaret() {
        val h = Harness()
        h.gesture.down(100f, 100f, 1000L)
        h.gesture.move(102f, 101f)
        h.gesture.up(103f, 100f, 1050L, true)
        assertTrue(h.events.isEmpty())
        assertEquals(1, h.feedback)
        h.gesture.flushTap(1400L)
        assertEquals(listOf("click"), h.events)
        assertEquals(0.0, h.x, 0.0)
    }

    @Test fun secondTapJitterKeepsAnchorAndRecognitionDistanceDoesNotBurst() {
        val h = Harness().apply { firstTap() }
        h.gesture.down(101f, 101f, 1150L)
        for (x in listOf(103f, 106f, 108f)) h.gesture.move(x, 101f)
        assertTrue(h.events.isEmpty())
        h.gesture.move(110f, 101f)
        assertEquals(0.0 to 0.0, h.anchor)
        assertEquals(listOf("down", "move"), h.events)
        assertEquals(1.0, h.x, 0.0)
        assertTrue(h.gesture.dragging)
    }

    @Test fun finalUpPositionIsSentBeforeReleaseWithoutAnExtraClick() {
        val h = Harness().apply { firstTap() }
        h.gesture.down(100f, 100f, 1150L)
        h.gesture.move(108f, 100f)
        h.gesture.move(112f, 100f)
        h.gesture.up(114f, 100f, 1400L, true)
        assertEquals(listOf("down", "move", "move", "up"), h.events)
        assertEquals(6.0, h.x, 0.0)
        assertFalse(h.gesture.dragging)
    }

    @Test fun ordinaryDoubleTapStillClicksTwiceWithNoHeldButton() {
        val h = Harness().apply { firstTap() }
        h.gesture.down(101f, 100f, 1150L)
        h.gesture.move(106f, 100f)
        h.gesture.up(101f, 100f, 1200L, true)
        assertEquals(listOf("click", "click"), h.events)
        assertEquals(0.0, h.x, 0.0)
    }

    @Test fun normalMovementKeepsCompleteDistanceAndDirectionAfterTapSlop() {
        val h = Harness()
        h.gesture.down(100f, 100f, 1000L)
        h.gesture.move(102f, 100f)
        h.gesture.move(106f, 103f)
        h.gesture.move(110f, 99f)
        h.gesture.up(112f, 98f, 1250L, true)
        assertEquals(listOf("move", "move", "move"), h.events)
        assertEquals(12.0, h.x, 0.0)
        assertEquals(-2.0, h.y, 0.0)
    }

    @Test fun disabledTapClickKeepsSmallPreciseMoves() {
        val h = Harness()
        h.gesture.down(100f, 100f, 1000L, tapToClick = false)
        h.gesture.move(100.5f, 100f)
        h.gesture.up(101f, 100f, 1050L, false)
        assertEquals(listOf("move", "move"), h.events)
        assertEquals(1.0, h.x, 0.0)
    }

    @Test fun cancelledDragReleasesOnceAndCannotContinueOrRearm() {
        val h = Harness().apply { firstTap() }
        h.gesture.down(100f, 100f, 1150L)
        h.gesture.move(108f, 100f)
        h.gesture.cancel(); h.gesture.cancel()
        h.gesture.move(115f, 100f)
        h.gesture.up(120f, 100f, 1300L, true)
        assertEquals(listOf("down", "up"), h.events)
        h.gesture.down(100f, 100f, 1350L)
        h.gesture.move(110f, 100f)
        assertEquals(1, h.events.count { it == "down" })
    }

    @Test fun delayedOrFarSecondTouchUsesOrdinaryMovement() {
        for ((x, time) in listOf(100f to 1451L, 130f to 1150L)) {
            val h = Harness().apply { firstTap() }
            h.gesture.down(x, 100f, time)
            h.gesture.move(x + 10f, 100f)
            assertEquals(listOf("click", "move"), h.events)
        }
    }

    @Test fun historyEquivalentSamplesDoNotChangeTheAnchorOrEndPosition() {
        for (samples in listOf(listOf(9f), listOf(2f, 5f, 7f, 9f))) {
            val h = Harness().apply { firstTap() }
            h.gesture.down(100f, 100f, 1150L)
            samples.forEach { h.gesture.move(100f + it, 100f) }
            h.gesture.up(111f, 100f, 1300L, true)
            assertEquals(0.0 to 0.0, h.anchor)
            assertEquals(3.0, h.x, 0.0)
            assertEquals(listOf("down", "move", "move", "up"), h.events)
        }
    }

    @Test fun defaultsKeepDoubleTapDragAndDoNotArmLongPress() {
        val settings = ControlSettings()
        assertTrue(settings.doubleTapHoldDrag)
        assertFalse(settings.longPressDrag)
        val h = Harness()
        h.gesture.down(100f, 100f, 1000L)
        assertFalse(h.gesture.tryStartLongPress(2500L))
        assertTrue(h.events.isEmpty())
    }

    @Test fun longPressStartsAtEightHundredMillisecondsAndAllowsSmallMovesUntilRelease() {
        val h = Harness()
        h.gesture.down(100f, 100f, 1000L, longPressDrag = true, doubleTapHoldDrag = false)
        assertFalse(h.gesture.tryStartLongPress(1799L))
        assertTrue(h.events.isEmpty())
        assertTrue(h.gesture.tryStartLongPress(1800L))
        assertFalse(h.gesture.tryStartLongPress(2200L))
        h.gesture.move(102f, 101f)
        h.gesture.up(103f, 102f, 2300L, true)
        assertEquals(listOf("down", "move", "move", "up"), h.events)
        assertEquals(0.0 to 0.0, h.anchor)
        assertEquals(3.0, h.x, 0.0)
        assertEquals(2.0, h.y, 0.0)
        assertFalse(h.gesture.tryStartLongPress(3000L))
    }

    @Test fun movingBeforeLongPressCannotArmAgainWhenFingerStopsOrReturns() {
        val h = Harness()
        h.gesture.down(100f, 100f, 1000L, longPressDrag = true)
        h.gesture.move(105f, 100f)
        h.gesture.move(100f, 100f)
        assertFalse(h.gesture.tryStartLongPress(2500L))
        h.gesture.up(100f, 100f, 2600L, true)
        assertEquals(listOf("move", "move"), h.events)
    }

    @Test fun longPressIgnoresJitterEvenWhenTapClickIsOff() {
        val h = Harness()
        h.gesture.down(100f, 100f, 1000L, tapToClick = false, longPressDrag = true)
        h.gesture.move(103f, 100f)
        assertTrue(h.events.isEmpty())
        assertTrue(h.gesture.tryStartLongPress(2000L))
        assertEquals(0.0 to 0.0, h.anchor)
        h.gesture.move(104f, 100f)
        h.gesture.up(105f, 100f, 2200L, false)
        assertEquals(listOf("down", "move", "move", "up"), h.events)
        assertEquals(2.0, h.x, 0.0)
    }

    @Test fun releasingOrCancellingBeforeTimeoutCannotProduceALatePress() {
        for (cancel in listOf(false, true)) {
            val h = Harness()
            h.gesture.down(100f, 100f, 1000L, longPressDrag = true)
            if (cancel) h.gesture.cancel() else h.gesture.up(100f, 100f, 1600L, true)
            assertFalse(h.gesture.tryStartLongPress(2500L))
            assertTrue(h.events.isEmpty())
        }
    }

    @Test fun cancellingAnActiveLongPressReleasesOnlyOnce() {
        val h = Harness()
        h.gesture.down(100f, 100f, 1000L, longPressDrag = true)
        assertTrue(h.gesture.tryStartLongPress(2000L))
        h.gesture.cancel(); h.gesture.cancel()
        h.gesture.up(105f, 100f, 2200L, true)
        assertFalse(h.gesture.tryStartLongPress(2500L))
        assertEquals(listOf("down", "up"), h.events)
    }

    @Test fun bothModesEnabledNeverPressTwiceForTheSameGesture() {
        for (holdFirst in listOf(false, true)) {
            val h = Harness().apply { firstTap() }
            h.gesture.down(100f, 100f, 1150L, longPressDrag = true)
            if (holdFirst) assertTrue(h.gesture.tryStartLongPress(2150L))
            h.gesture.move(109f, 100f)
            assertFalse(h.gesture.tryStartLongPress(2300L))
            h.gesture.up(110f, 100f, 2400L, true)
            assertEquals(1, h.events.count { it == "down" })
            assertEquals(1, h.events.count { it == "up" })
            assertEquals(0.0 to 0.0, h.anchor)
            assertEquals(if (holdFirst) 10.0 else 2.0, h.x, 0.0)
            assertFalse(h.events.contains("click"))
        }
    }

    @Test fun disablingDoubleTapDragKeepsClicksAndOrdinaryMotionWithEitherLongPressSetting() {
        for (longPress in listOf(false, true)) {
            val h = Harness().apply { firstTap() }
            h.gesture.down(100f, 100f, 1150L, longPressDrag = longPress, doubleTapHoldDrag = false)
            h.gesture.up(100f, 100f, 1200L, true)
            assertEquals(listOf("click", "click"), h.events)
            h.gesture.down(100f, 100f, 1250L, longPressDrag = longPress, doubleTapHoldDrag = false)
            h.gesture.move(109f, 100f)
            assertFalse(h.gesture.tryStartLongPress(2500L))
            h.gesture.up(110f, 100f, 2600L, true)
            assertEquals(listOf("click", "click", "move", "move"), h.events)
        }
    }

    @Test fun singleTapWaitsForRecognitionButFeedbackIsImmediate() {
        val h = Harness().apply { firstTap() }
        assertEquals(1, h.feedback)
        assertEquals(1400L, h.gesture.pendingTapDeadline)
        assertFalse(h.gesture.flushTap(1399L))
        assertTrue(h.events.isEmpty())
        assertTrue(h.gesture.flushTap(1400L))
        assertEquals(listOf("click"), h.events)
        assertFalse(h.gesture.flushTap(1500L))
    }

    @Test fun secondHoldFreezesPendingClickAndNeverPrecedesDragWithAClick() {
        val h = Harness().apply { firstTap() }
        h.gesture.down(100f, 100f, 1150L)
        assertNull(h.gesture.pendingTapDeadline)
        assertFalse(h.gesture.flushTap(1600L))
        assertTrue(h.events.isEmpty())
        h.gesture.move(108f, 100f)
        assertEquals(listOf("down"), h.events)
        assertEquals(0.0, h.x, 0.0)
        h.gesture.up(109f, 100f, 1750L, true)
        assertEquals(listOf("down", "move", "up"), h.events)
        assertEquals(1.0, h.x, 0.0)
    }

    @Test fun pendingTapCancellationCannotSendALateClick() {
        val h = Harness().apply { firstTap() }
        h.gesture.cancel()
        assertFalse(h.gesture.flushTap(1500L))
        assertTrue(h.events.isEmpty())
        assertNull(h.gesture.pendingTapDeadline)
    }

    @Test fun stationarySecondHoldReleasedWithoutDraggingCommitsOnlyFirstTap() {
        val h = Harness().apply { firstTap() }
        h.gesture.down(100f, 100f, 1150L)
        h.gesture.up(100f, 100f, 1750L, true)
        assertEquals(listOf("click"), h.events)
        assertNull(h.gesture.pendingTapDeadline)
    }

    @Test fun tapClickOffStillAllowsDoubleTapDragWithoutClickOrTapFeedback() {
        val h = Harness()
        h.gesture.down(100f, 100f, 1000L, tapToClick = false)
        h.gesture.up(100f, 100f, 1050L, false)
        h.gesture.down(100f, 100f, 1150L, tapToClick = false)
        h.gesture.move(109f, 100f)
        h.gesture.up(110f, 100f, 1300L, false)
        assertEquals(listOf("down", "move", "move", "up"), h.events)
        assertEquals(2.0, h.x, 0.0)
        assertEquals(0, h.feedback)
    }

    @Test fun disablingDoubleTapDragMakesOrdinaryClicksImmediate() {
        val h = Harness()
        for (time in listOf(1000L, 1150L)) {
            h.gesture.down(100f, 100f, time, doubleTapHoldDrag = false)
            h.gesture.up(100f, 100f, time + 50L, true)
            assertNull(h.gesture.pendingTapDeadline)
        }
        assertEquals(listOf("click", "click"), h.events)
        assertEquals(2, h.feedback)
    }
}
