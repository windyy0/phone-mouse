package com.windy.phonemouse.app

import org.junit.Assert.*
import org.junit.Test

class TouchGestureAccumulatorTest {
    @Test fun zoomWaitsForAFullNotchAndReversalsCancelBeforeInjection() {
        val g = TouchGestureAccumulator()
        g.add(0.0, 90.0); assertFalse(g.pending())
        g.add(0.0, -30.0); assertFalse(g.pending())
        g.add(0.0, 60.0); assertEquals(TouchGesturePacket(0, 120), g.drain())
        g.add(0.0, -180.0); assertEquals(TouchGesturePacket(0, -120), g.drain())
        assertFalse(g.pending())
    }
    @Test fun fractionsCannotCarryIntoANewGestureOrReconnection() {
        val g = TouchGestureAccumulator()
        g.add(.75, 119.0); g.clear()
        g.add(.5, 1.0); assertFalse(g.pending())
        g.add(.5, 119.0); assertEquals(TouchGesturePacket(1, 120), g.drain())
    }
    @Test fun clampPreservesOverflowAndRejectsNonFiniteValues() {
        val g = TouchGestureAccumulator()
        g.add(4900.5, -4900.0); g.add(Double.NaN, Double.POSITIVE_INFINITY)
        assertEquals(TouchGesturePacket(2400, -2400), g.drain())
        assertEquals(TouchGesturePacket(2400, -2400), g.drain())
        assertEquals(TouchGesturePacket(100, 0), g.drain())
        assertFalse(g.pending())
    }
}
