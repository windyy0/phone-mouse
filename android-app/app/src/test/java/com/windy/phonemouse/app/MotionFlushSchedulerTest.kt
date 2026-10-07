package com.windy.phonemouse.app

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Delayed
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class MotionFlushSchedulerTest {
    private class Task(command: Runnable, val nanos: Long) : FutureTask<Unit>(command, Unit), ScheduledFuture<Unit> {
        override fun getDelay(unit: TimeUnit): Long = unit.convert(nanos, TimeUnit.NANOSECONDS)
        override fun compareTo(other: Delayed): Int = nanos.compareTo(other.getDelay(TimeUnit.NANOSECONDS))
    }
    private class Worker : ScheduledThreadPoolExecutor(1) {
        val tasks = mutableListOf<Task>()
        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
            Task(command, unit.toNanos(delay)).also { tasks += it }
    }
    private class Harness {
        val worker = Worker()
        var time = 0L
        var pending = false
        var flushes = 0
        val scheduler = MotionFlushScheduler(worker, { pending }, { flushes++; pending = false }, { time })
        fun runNext() { val task = worker.tasks.last { !it.isDone }; time += task.nanos; task.run() }
    }

    @Test fun eachRateSchedulesTrueSubMillisecondDelayAndDoesNotWakeWhenIdle() {
        for (rate in MouseReportRate.entries) {
            val h = Harness()
            h.scheduler.setRate(rate); h.scheduler.request()
            assertTrue(h.worker.tasks.isEmpty())
            h.pending = true; h.scheduler.request(); h.scheduler.request()
            assertEquals(1, h.worker.tasks.size)
            assertEquals(1_000_000_000L / rate.hz, h.worker.tasks.single().nanos)
            h.runNext()
            assertEquals(1, h.flushes)
            assertEquals(1, h.worker.tasks.size)
        }
    }

    @Test fun pendingInputSurvivesRateChangeAndOldDeadlineIsCancelled() {
        val h = Harness(); h.pending = true; h.scheduler.request()
        val old = h.worker.tasks.single()
        h.scheduler.setRate(MouseReportRate.Hz4000)
        assertTrue(old.isCancelled)
        assertEquals(250_000L, h.worker.tasks.last().nanos)
        h.runNext(); assertEquals(1, h.flushes)
    }

    @Test fun busyWorkerDoesNotReplayCatchUpTicks() {
        val h = Harness(); h.pending = true; h.scheduler.request()
        h.time += 100_000_000L; h.runNext()
        h.pending = true; h.scheduler.request()
        assertEquals(4_000_000L, h.worker.tasks.last().nanos)
        assertEquals(1, h.flushes)
    }

    @Test fun nextMovementUsesRemainingIntervalAndResetCancelsPendingWork() {
        val h = Harness(); h.pending = true; h.scheduler.request(); h.runNext()
        h.time += 1_000_000L; h.pending = true; h.scheduler.request()
        assertEquals(3_000_000L, h.worker.tasks.last().nanos)
        val old = h.worker.tasks.last(); h.scheduler.reset()
        assertTrue(old.isCancelled)
        h.scheduler.request()
        assertEquals(4_000_000L, h.worker.tasks.last().nanos)
    }
    @Test fun gestureChannelKeepsItsRateLimitEvenWithAHighPointerReportRate() {
        val worker = Worker()
        var pending = true
        val scheduler = MotionFlushScheduler(worker, { pending }, { pending = false }, { 0L }, 16_000_000L)
        scheduler.setRate(MouseReportRate.Hz4000); scheduler.request()
        assertEquals(16_000_000L, worker.tasks.single().nanos)
        worker.tasks.single().run()
        assertFalse(pending)
        assertEquals(1, worker.tasks.size)
    }
}
