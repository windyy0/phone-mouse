package com.windy.phonemouse.app

import org.junit.Assert.*
import org.junit.Test

class TouchSampleMeterTest {
    @Test fun historicalSamplesAreDistinctFromBatchedDelivery() {
        val meter = TouchSampleMeter()
        meter.beginStroke(0, 0f, 0f)
        // 250 samples/s arrive in groups of 4: delivery is only 62.5 events/s.
        for (i in 1..800) {
            meter.sample(i * 4_000_000L, i.toFloat(), 0f, false)
            if (i % 4 == 0) meter.delivered(i * 4_000_000L, true)
        }
        val result = meter.result()
        assertTrue(result.sufficient)
        assertEquals(250.0, result.sampleHz, 0.001)
        assertEquals(62.5, result.deliveryHz, 0.001)
        assertEquals(4.0, result.averageIntervalMs, 0.001)
        assertEquals(MouseReportRate.Hz250, result.suggestedRate)
    }

    @Test fun liftAndLongPauseDoNotDiluteRate() {
        val meter = TouchSampleMeter()
        fun stroke(start: Long) {
            meter.beginStroke(start, 0f, 0f)
            for (i in 1..300) {
                meter.sample(start + i * 4_000_000L, i.toFloat(), 0f, false)
                meter.delivered(start + i * 4_000_000L, true)
            }
            meter.endStroke()
        }
        stroke(0); stroke(20_000_000_000L)
        meter.beginStroke(40_000_000_000L, 0f, 0f)
        meter.sample(45_000_000_000L, 1f, 0f, false)
        assertEquals(250.0, meter.result().sampleHz, 0.001)
        assertEquals(2_400_000_000L, meter.result().activeNanos)
        assertTrue(meter.result().sufficient)
    }

    @Test fun stationaryDuplicateAndOutOfOrderPointsDoNotInflateRate() {
        val meter = TouchSampleMeter()
        meter.beginStroke(0, 0f, 0f)
        assertFalse(meter.sample(1_000_000L, 0f, 0f, false))
        assertTrue(meter.sample(5_000_000L, 1f, 0f, false))
        assertFalse(meter.sample(5_000_000L, 1f, 0f, false))
        assertFalse(meter.sample(4_000_000L, 8f, 0f, false))
        assertFalse(meter.sample(6_000_000L, Float.NaN, 0f, false))
        assertEquals(1, meter.result().samples)
        assertEquals(250.0, meter.result().sampleHz, 0.001)
        assertFalse(meter.result().sufficient)
    }

    @Test fun quantizedTimestampsCanEstimateSubMillisecondSamplesOverWholeSpan() {
        val meter = TouchSampleMeter()
        meter.beginStroke(0, 0f, 0f)
        for (i in 1..12_000) {
            meter.sample((i / 4) * 1_000_000L, i.toFloat(), 0f, true)
            if (i % 40 == 0) meter.delivered((i / 4) * 1_000_000L, true)
        }
        assertEquals(4000.0, meter.result().sampleHz, 0.001)
        assertTrue(meter.result().coarseTimestamps)
        assertTrue(meter.result().sufficient)
    }

    @Test fun shortZeroAndMultipleFingerRunsCannotGiveRecommendationAsValidData() {
        val meter = TouchSampleMeter()
        assertEquals(0.0, meter.result().sampleHz, 0.0)
        meter.beginStroke(0, 0f, 0f)
        meter.sample(4_000_000L, 1f, 0f, false)
        meter.endStroke(multipleFingers = true)
        assertFalse(meter.sample(8_000_000L, 2f, 0f, false))
        assertFalse(meter.result().sufficient)
        assertTrue(meter.result().skippedMultipleFingers)
    }

    @Test fun nominalDeliveryRatesSuggestAvailableTiersWithHeadroom() {
        assertEquals(MouseReportRate.Hz250, MouseReportRate.suggestedFor(240.0))
        assertEquals(MouseReportRate.Hz500, MouseReportRate.suggestedFor(360.0))
        assertEquals(MouseReportRate.Hz500, MouseReportRate.suggestedFor(510.0))
        assertEquals(MouseReportRate.Hz1000, MouseReportRate.suggestedFor(960.0))
        assertEquals(MouseReportRate.Hz2000, MouseReportRate.suggestedFor(1900.0))
        assertEquals(MouseReportRate.Hz4000, MouseReportRate.suggestedFor(3800.0))
        assertEquals(MouseReportRate.Hz250, MouseReportRate.fromHz(null))
        assertEquals(MouseReportRate.Hz250, MouseReportRate.fromHz(8000))
    }
}
