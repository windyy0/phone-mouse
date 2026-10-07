package com.windy.phonemouse.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollWheelVisualTest {
    @Test fun tickLengthsAndAlphaFadeFromCentre() {
        assertEquals(listOf(12f, 16f, 20f, 24f, 30f, 24f, 20f, 16f, 12f), ScrollWheelTickLengthsDp)
        assertEquals(listOf(.12f, .16f, .20f, .24f, .30f, .24f, .20f, .16f, .12f), ScrollWheelTickAlphas)
        assertTrue(ScrollWheelTickLengthsDp.first() < ScrollWheelTickLengthsDp[4])
        assertTrue(ScrollWheelTickAlphas.first() < ScrollWheelTickAlphas[4])
        assertEquals(ScrollWheelTickLengthsDp, ScrollWheelTickLengthsDp.reversed().reversed())
        assertEquals(ScrollWheelTickAlphas, ScrollWheelTickAlphas.reversed().reversed())
    }
}
