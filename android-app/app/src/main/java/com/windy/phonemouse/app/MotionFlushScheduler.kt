package com.windy.phonemouse.app

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** All calls run on the input worker, including setting changes and resets. */
internal class MotionFlushScheduler(
    private val worker: ScheduledExecutorService,
    private val pending: () -> Boolean,
    private val flush: () -> Unit,
    private val now: () -> Long = System::nanoTime,
    private val minimumIntervalNanos: Long = 0L,
) {
    private var rate = MouseReportRate.Hz250
    private var task: ScheduledFuture<*>? = null
    private var lastFlush: Long? = null

    fun request() {
        if (task != null || !pending()) return
        val elapsed = lastFlush?.let { now() - it } ?: 0L
        val delay = (maxOf(rate.intervalNanos, minimumIntervalNanos) - elapsed).coerceAtLeast(0L)
        task = worker.schedule({
            task = null
            flush()
            lastFlush = now()
            // Keep draining clamped overflow, but never wake an idle phone or
            // replay fixed-rate catch-up bursts after a delayed network write.
            request()
        }, delay, TimeUnit.NANOSECONDS)
    }

    fun setRate(value: MouseReportRate) {
        if (rate == value) return
        rate = value
        task?.cancel(false)
        task = null
        request()
    }

    fun reset() {
        task?.cancel(false)
        task = null
        lastFlush = null
    }
}
