package com.windy.phonemouse.app

import org.junit.Assert.*
import org.junit.Test

class TwoFingerGestureControllerTest {
    private fun pair(y: Float = 40f) = listOf(TouchContact(3, 40f, y), TouchContact(9, 90f, y))
    private fun gesture(allowTap: Boolean = true) = TwoFingerGestureController().apply { start(pair(), 1000L, allowTap) }

    @Test fun quickTapWithJitterClicksOnlyAfterLastFingerLifts() {
        val g = gesture()
        assertEquals(0.0, g.move(pair(42f)), 0.0)
        assertEquals(0.0, g.lift(pair(41f)), 0.0)
        assertTrue(g.finish(listOf(TouchContact(9, 90f, 41f)), 1200L))
        assertFalse(g.finish(listOf(TouchContact(9, 90f, 41f)), 1210L))
    }

    @Test fun scrollingPreservesBufferedDistanceAndSuppressesRightClick() {
        val g = gesture()
        assertEquals(0.0, g.move(pair(42f)), 0.0)
        assertEquals(-120.0, g.move(pair(58f)), .0001)
        assertEquals(-60.0, g.move(pair(67f)), .0001)
        g.lift(pair(67f))
        assertFalse(g.finish(listOf(TouchContact(3, 40f, 67f)), 1200L))
    }

    @Test fun stationaryFingersPauseAndDirectionCanReverse() {
        val g = gesture()
        assertEquals(120.0, g.move(pair(22f)), .0001)
        assertEquals(0.0, g.move(pair(22f)), 0.0)
        assertEquals(-60.0, g.move(pair(31f)), .0001)
    }

    @Test fun contactOrderDoesNotChangeScrollOrTapRecognition() {
        val g = gesture()
        assertEquals(-120.0, g.move(pair(58f).reversed()), .0001)
        assertEquals(0.0, g.move(pair(58f)), 0.0)
        val tap = gesture()
        tap.lift(pair().reversed())
        assertTrue(tap.finish(listOf(TouchContact(3, 40f, 40f)), 1200L))
    }

    @Test fun opposingFingerMotionCannotProduceAnAccidentalRightClick() {
        val g = gesture()
        val spread = listOf(TouchContact(3, 40f, 50f), TouchContact(9, 90f, 30f))
        assertEquals(0.0, g.move(spread), 0.0)
        g.lift(spread)
        assertFalse(g.finish(listOf(spread[0]), 1200L))
    }

    @Test fun liftingOneFingerFlushesItsFinalDeltaAndStopsFurtherScroll() {
        val g = gesture()
        assertEquals(-120.0, g.lift(pair(58f)), .0001)
        assertEquals(0.0, g.move(listOf(TouchContact(3, 40f, 90f))), 0.0)
        assertFalse(g.finish(listOf(TouchContact(3, 40f, 90f)), 1200L))
    }

    @Test fun remainingFingerMovementCancelsATapInsteadOfMovingMouse() {
        val g = gesture()
        g.lift(pair())
        assertEquals(0.0, g.move(listOf(TouchContact(9, 100f, 40f))), 0.0)
        assertFalse(g.finish(listOf(TouchContact(9, 100f, 40f)), 1200L))
    }

    @Test fun thirdFingerOrReplacedContactCancelsTheGestureUntilRestart() {
        for (contacts in listOf(pair() + TouchContact(12, 120f, 40f), listOf(pair()[0], TouchContact(12, 90f, 40f)))) {
            val g = gesture()
            assertEquals(0.0, g.move(contacts), 0.0)
            assertEquals(0.0, g.move(pair(58f)), 0.0)
            g.lift(pair())
            assertFalse(g.finish(listOf(pair()[0]), 1200L))
        }
    }

    @Test fun longHoldAndInvalidTimeDoNotRightClick() {
        for ((time, expected) in listOf(1350L to true, 1351L to false, 999L to false)) {
            val g = gesture()
            g.lift(pair())
            assertEquals(expected, g.finish(listOf(pair()[0]), time))
        }
    }

    @Test fun previousSingleFingerMotionOrDragCanScrollButCannotClickRight() {
        val tap = gesture(allowTap = false)
        tap.lift(pair())
        assertFalse(tap.finish(listOf(pair()[0]), 1200L))
        val scroll = gesture(allowTap = false)
        assertEquals(-120.0, scroll.move(pair(58f)), .0001)
    }

    @Test fun cancellationAndRestartDoNotCarryMovementOrRightClickState() {
        val g = gesture()
        g.cancel()
        assertEquals(0.0, g.move(pair(58f)), 0.0)
        g.lift(pair())
        assertFalse(g.finish(listOf(pair()[0]), 1200L))
        g.start(pair(100f), 2000L, true)
        g.lift(pair(100f))
        assertTrue(g.finish(listOf(pair(100f)[0]), 2100L))
    }

    @Test fun historicalSamplesAndFinalSampleProduceTheSameTotalDistance() {
        for (samples in listOf(listOf(58f), listOf(41f, 42f, 45f, 50f, 58f))) {
            val g = gesture()
            val total = samples.sumOf { g.move(pair(it)) } + g.lift(pair(60f))
            assertEquals(-20.0 * 120.0 / 18.0, total, .0001)
        }
    }

    @Test fun horizontalPanPreservesDistanceAndCannotBecomeRightClickOrZoom() {
        val horizontal = mutableListOf<Double>(); val zoom = mutableListOf<Double>()
        val g = TwoFingerGestureController(horizontal::add, zoom::add).apply { start(pair(), 1000, true) }
        assertEquals(0.0, g.move(pair().map { it.copy(x = it.x + 2) }), 0.0)
        assertEquals(0.0, g.move(pair().map { it.copy(x = it.x + 18) }), 0.0)
        assertEquals(120.0, horizontal.sum(), .0001)
        g.lift(pair().map { it.copy(x = it.x + 27) })
        assertEquals(180.0, horizontal.sum(), .0001)
        assertTrue(zoom.isEmpty())
        assertFalse(g.finish(listOf(TouchContact(3, 67f, 40f)), 1200))
    }

    @Test fun pinchUsesDistanceRatioAndNeverScrollsEvenWhenItsCenterMoves() {
        val horizontal = mutableListOf<Double>(); val zoom = mutableListOf<Double>()
        val g = TwoFingerGestureController(horizontal::add, zoom::add).apply { start(pair(), 1000, true) }
        val spread = listOf(TouchContact(3, 20f, 40f), TouchContact(9, 110f, 40f))
        assertEquals(0.0, g.move(spread.reversed()), 0.0)
        assertTrue(zoom.sum() > 0)
        val before = zoom.sum()
        assertEquals(0.0, g.move(spread.map { it.copy(x = it.x + 25, y = it.y + 25) }), 0.0)
        assertEquals(before, zoom.sum(), .0001)
        g.lift(pair())
        assertEquals(0.0, zoom.sum(), .0001)
        assertTrue(horizontal.isEmpty())
        assertFalse(g.finish(pair().take(1), 1200))
    }

    @Test fun tinySpanJitterDoesNotZoomAndScrollingLocksOutLaterPinches() {
        val zoom = mutableListOf<Double>()
        val g = TwoFingerGestureController(onZoom = zoom::add).apply { start(pair(), 1000, true) }
        assertEquals(0.0, g.move(listOf(TouchContact(3, 38f, 40f), TouchContact(9, 92f, 40f))), 0.0)
        assertTrue(zoom.isEmpty())
        assertEquals(-120.0, g.move(pair(58f)), .0001)
        g.move(listOf(TouchContact(3, 0f, 58f), TouchContact(9, 130f, 58f)))
        assertTrue(zoom.isEmpty())
    }

    @Test fun scrollAxisAndPinchAreIndependentOfHistoricalBatching() {
        for (batch in listOf(listOf(40f), listOf(2f, 5f, 12f, 25f, 40f))) {
            var x = 0.0; var z = 0.0
            val g = TwoFingerGestureController({ x += it }, { z += it }).apply { start(pair(), 1000, true) }
            val y = batch.sumOf { offset -> g.move(pair().map { it.copy(x = it.x + offset, y = it.y + offset) }) }
            assertEquals(0.0, x, .0001)
            assertEquals(-40 * 120.0 / 18.0, y, .0001); assertEquals(0.0, z, .0001)
            val p = TwoFingerGestureController(onZoom = { z += it }).apply { start(pair(), 1000, true) }
            batch.forEach { offset -> p.move(listOf(TouchContact(3, 40f - offset, 40f), TouchContact(9, 90f + offset, 40f))) }
            assertEquals(kotlin.math.ln(130.0 / 50.0) * 600.0, z, .0001)
        }
    }

    @Test fun horizontalScrollIgnoresVerticalDriftEvenWhenItExceedsTheOldSlop() {
        var x = 0.0; var z = 0.0
        val g = TwoFingerGestureController({ x += it }, { z += it }).apply { start(pair(), 1000, true) }
        fun pan(dx: Float, dy: Float) = pair().map { it.copy(x = it.x + dx, y = it.y + dy) }
        for ((dx, dy) in listOf(18f to 7f, 40f to 16f, 36f to 50f, 24f to 5f)) {
            assertEquals(0.0, g.move(pan(dx, dy)), 0.0)
        }
        assertEquals(24 * 120.0 / 18.0, x, .0001)
        assertEquals(0.0, z, 0.0)
        assertEquals(0.0, g.lift(pan(24f, 70f)), 0.0)
        assertFalse(g.finish(pan(24f, 70f).take(1), 1200))
    }

    @Test fun verticalScrollIgnoresHorizontalDriftAndStillReversesNormally() {
        var x = 0.0; var z = 0.0
        val g = TwoFingerGestureController({ x += it }, { z += it }).apply { start(pair(), 1000, true) }
        fun pan(dx: Float, dy: Float) = pair().map { it.copy(x = it.x + dx, y = it.y + dy) }
        val y = listOf(7f to 18f, 16f to 40f, 50f to 36f, 5f to 24f).sumOf { (dx, dy) -> g.move(pan(dx, dy)) }
        assertEquals(-24 * 120.0 / 18.0, y, .0001)
        assertEquals(0.0, g.lift(pan(70f, 24f)), 0.0)
        assertEquals(0.0, x, 0.0); assertEquals(0.0, z, 0.0)
    }

    @Test fun ambiguousStartupWaitsForDirectionAndPreservesOnlyIntendedTravel() {
        for (horizontal in listOf(true, false)) {
            var x = 0.0
            val g = TwoFingerGestureController(onHorizontalScroll = { x += it }).apply { start(pair(), 1000, true) }
            fun pan(dx: Float, dy: Float) = pair().map { it.copy(x = it.x + dx, y = it.y + dy) }
            for ((dx, dy) in listOf(3f to 4f, 5f to 6f)) {
                assertEquals(0.0, g.move(pan(dx, dy)), 0.0)
                assertEquals(0.0, x, 0.0)
            }
            val final = if (horizontal) pan(18f, 7f) else pan(7f, 18f)
            assertEquals(if (horizontal) 0.0 else -120.0, g.move(final), .0001)
            assertEquals(if (horizontal) 120.0 else 0.0, x, .0001)
        }
    }

    @Test fun diagonalStartupChoosesOneAxisAndCancellingOrFinishingResetsIt() {
        var x = 0.0
        val g = TwoFingerGestureController(onHorizontalScroll = { x += it })
        fun pan(dx: Float, dy: Float) = pair().map { it.copy(x = it.x + dx, y = it.y + dy) }
        g.start(pair(), 1000, true)
        assertEquals(-120.0, g.move(pan(18f, 18f)), .0001)
        assertEquals(0.0, x, 0.0)
        g.lift(pan(18f, 18f)); assertFalse(g.finish(pan(18f, 18f).take(1), 1200))
        g.start(pair(), 2000, true)
        assertEquals(0.0, g.move(pan(18f, 0f)), 0.0)
        assertEquals(120.0, x, .0001)
        g.cancel(); g.start(pair(), 3000, true)
        assertEquals(-120.0, g.move(pan(0f, 18f)), .0001)
        assertEquals(120.0, x, .0001)
    }

    @Test fun liftingCancellingOrInvalidContactsCannotProduceMoreZoomOrHorizontalScroll() {
        var x = 0.0; var zoom = 0.0
        val g = TwoFingerGestureController({ x += it }, { zoom += it }).apply { start(pair(), 1000, true) }
        val spread = listOf(TouchContact(3, 20f, 40f), TouchContact(9, 110f, 40f))
        g.lift(spread); val previous = zoom
        g.move(spread); assertEquals(previous, zoom, 0.0)
        g.cancel(); g.move(spread); assertEquals(previous, zoom, 0.0)
        g.start(pair(), 2000, true)
        g.move(listOf(pair()[0], pair()[1].copy(x = Float.NaN)))
        g.lift(pair()); assertFalse(g.finish(pair().take(1), 2100))
        assertEquals(0.0, x, 0.0)
    }
}
