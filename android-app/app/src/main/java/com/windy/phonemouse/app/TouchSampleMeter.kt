package com.windy.phonemouse.app

internal data class TouchSamplingResult(
    val sampleHz: Double,
    val deliveryHz: Double,
    val samples: Int,
    val activeNanos: Long,
    val sufficient: Boolean,
    val coarseTimestamps: Boolean,
    val skippedMultipleFingers: Boolean,
) {
    val averageIntervalMs get() = if (sampleHz > 0) 1000.0 / sampleHz else 0.0
    val suggestedRate get() = MouseReportRate.suggestedFor(deliveryHz)
}

/** Measures app-visible moving samples, including historical points in a batch. */
internal class TouchSampleMeter {
    private var sampleAt: Long? = null
    private var deliveryAt: Long? = null
    private var x = 0f
    private var y = 0f
    private var intervals = 0
    private var activeNanos = 0L
    private var deliveries = 0
    private var deliveryNanos = 0L
    private var coarse = false
    var skippedMultipleFingers = false
        private set
    var samples = 0
        private set

    fun beginStroke(timeNanos: Long, px: Float, py: Float) {
        sampleAt = timeNanos; x = px; y = py; deliveryAt = null
    }

    fun endStroke(multipleFingers: Boolean = false) {
        sampleAt = null; deliveryAt = null
        if (multipleFingers) skippedMultipleFingers = true
    }

    fun sample(timeNanos: Long, px: Float, py: Float, coarseTimestamp: Boolean): Boolean {
        if (!px.isFinite() || !py.isFinite()) return false
        val previous = sampleAt ?: return false
        if (timeNanos < previous) return false
        val changed = px != x || py != y
        val dt = timeNanos - previous
        sampleAt = timeNanos; x = px; y = py
        if (!changed) return false
        coarse = coarse || coarseTimestamp
        samples++
        // Equal millisecond timestamps can represent distinct high-rate
        // samples on older Android. Count those samples over the whole span;
        // never divide by an individual zero/quantized interval.
        if (dt in 0..MAX_GAP_NANOS) { intervals++; activeNanos += dt }
        return true
    }

    fun delivered(timeNanos: Long, moving: Boolean) {
        val previous = deliveryAt
        deliveryAt = if (moving) timeNanos else null
        if (moving && previous != null) {
            val dt = timeNanos - previous
            if (dt in 1..MAX_GAP_NANOS) { deliveries++; deliveryNanos += dt }
        }
    }

    fun result() = TouchSamplingResult(
        sampleHz = if (activeNanos > 0) intervals * 1_000_000_000.0 / activeNanos else 0.0,
        deliveryHz = if (deliveryNanos > 0) deliveries * 1_000_000_000.0 / deliveryNanos else 0.0,
        samples = samples,
        activeNanos = activeNanos,
        sufficient = intervals >= 100 && activeNanos >= 2_000_000_000L && deliveryNanos > 0,
        coarseTimestamps = coarse,
        skippedMultipleFingers = skippedMultipleFingers,
    )

    private companion object { const val MAX_GAP_NANOS = 100_000_000L }
}
