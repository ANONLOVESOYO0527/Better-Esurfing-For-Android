package dev.echoping.betteresurfing.schedule

import java.time.ZonedDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class StopCompletionPolicyTest {
    @Test fun configurationSavedDuringStopIsEvaluatedOnCompletion() {
        val now = ZonedDateTime.parse("2026-11-02T12:00:00+08:00[Asia/Shanghai]")
        val config = AtomicReference(ScheduleConfig(enabled = true, startMinutes = 13 * 60))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = AtomicReference<RestartRequest?>()
        val worker = Thread {
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            result.set(StopCompletionPolicy.restart(null, 2, config.get().shouldRun(now)))
        }
        worker.start()
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            config.set(ScheduleConfig(enabled = true, startMinutes = 7 * 60))
        } finally { release.countDown(); worker.join(3000) }
        assertEquals(RestartRequest(2, false), result.get())
    }

    @Test fun manualStartWinsEvenOutsideSchedule() {
        assertEquals(RestartRequest(3, true), StopCompletionPolicy.restart(3, 0, false))
    }

    @Test fun noRestartAfterManualStopOrDisabledPlan() {
        assertNull(StopCompletionPolicy.restart(null, 0, false))
    }
}
