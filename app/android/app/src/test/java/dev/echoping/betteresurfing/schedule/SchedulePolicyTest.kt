package dev.echoping.betteresurfing.schedule

import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class SchedulePolicyTest {
    private val weekdays = DayOfWeek.entries.take(5).toSet()
    private val daily = ScheduleConfig(enabled = true, startMinutes = 7 * 60, stopMinutes = 23 * 60)
    private fun at(value: String) = ZonedDateTime.parse(value + "+08:00[Asia/Shanghai]")

    @Test fun startInclusiveStopExclusive() {
        assertFalse(daily.shouldRun(at("2026-11-02T06:59:59")))
        assertTrue(daily.shouldRun(at("2026-11-02T07:00:00")))
        assertTrue(daily.shouldRun(at("2026-11-02T22:59:59")))
        assertFalse(daily.shouldRun(at("2026-11-02T23:00:00")))
    }

    @Test fun unselectedWeekendStaysStopped() {
        val config = daily.copy(days = weekdays)
        assertFalse(config.shouldRun(at("2026-11-07T10:00:00")))
        val next = config.nextTransition(at("2026-11-06T23:00:00"))!!
        assertEquals(at("2026-11-09T07:00:00"), next.at)
        assertTrue(next.run)
    }

    @Test fun overnightWindowBelongsToItsStartDay() {
        val config = daily.copy(startMinutes = 23 * 60, stopMinutes = 7 * 60, days = setOf(DayOfWeek.FRIDAY))
        assertTrue(config.shouldRun(at("2026-11-06T23:00:00")))
        assertTrue(config.shouldRun(at("2026-11-07T06:59:59")))
        assertFalse(config.shouldRun(at("2026-11-07T07:00:00")))
        assertFalse(config.shouldRun(at("2026-11-07T23:00:00")))
        assertEquals(at("2026-11-07T07:00:00"), config.nextTransition(at("2026-11-07T00:00:00"))!!.at)
    }

    @Test fun nextTransitionIsStrictlyFutureAtBothBoundaries() {
        val start = daily.nextTransition(at("2026-11-02T07:00:00"))!!
        assertEquals(at("2026-11-02T23:00:00"), start.at)
        assertFalse(start.run)
        val stop = daily.nextTransition(at("2026-11-02T23:00:00"))!!
        assertEquals(at("2026-11-03T07:00:00"), stop.at)
        assertTrue(stop.run)
    }

    @Test fun disabledOrInvalidScheduleNeverSchedules() {
        for (config in listOf(daily.copy(enabled = false), daily.copy(days = emptySet()),
            daily.copy(startMinutes = 23 * 60), daily.copy(startMinutes = -1), daily.copy(stopMinutes = 1440))) {
            assertFalse(config.shouldRun(at("2026-11-02T12:00:00")))
            assertNull(config.nextTransition(at("2026-11-02T12:00:00")))
        }
    }

    @Test fun manualStartAtNightExpiresAtNextBoundary() {
        val now = at("2026-11-02T23:30:00")
        val override = daily.manualOverride(now, run = true)!!
        assertTrue(daily.shouldRun(now, override))
        assertEquals(at("2026-11-03T07:00:00").toInstant().toEpochMilli(), override.untilMillis)
        assertTrue(daily.shouldRun(at("2026-11-03T07:00:00"), override))
        assertFalse(daily.shouldRun(at("2026-11-03T23:00:00"), override))
    }

    @Test fun manualStopInDaySurvivesRecoveryButNotNextMorning() {
        val override = daily.manualOverride(at("2026-11-02T12:00:00"), run = false)!!
        assertFalse(daily.shouldRun(at("2026-11-02T15:00:00"), override))
        assertFalse(daily.shouldRun(at("2026-11-02T23:00:00"), override))
        assertTrue(daily.shouldRun(at("2026-11-03T07:00:00"), override))
    }

    @Test fun deviceTimezoneDeterminesWallClockSchedule() {
        val shanghai = at("2026-11-02T07:30:00")
        assertTrue(daily.shouldRun(shanghai))
        assertFalse(daily.shouldRun(shanghai.withZoneSameInstant(ZoneId.of("UTC"))))
    }

    @Test fun dstGapAndOverlapHaveConsistentWindowBoundaries() {
        val spring = daily.copy(startMinutes = 150, stopMinutes = 240)
        val now = ZonedDateTime.parse("2026-03-08T01:00:00-05:00[America/New_York]")
        val start = spring.nextTransition(now)!!
        assertEquals(ZonedDateTime.parse("2026-03-08T03:30:00-04:00[America/New_York]"), start.at)
        assertTrue(spring.shouldRun(start.at))
        val fall = daily.copy(startMinutes = 90, stopMinutes = 150)
        assertTrue(fall.shouldRun(ZonedDateTime.parse("2026-11-01T01:45:00-05:00[America/New_York]")))
        assertFalse(fall.shouldRun(ZonedDateTime.parse("2026-11-01T02:30:00-05:00[America/New_York]")))
    }

    @Test fun scheduledMorningRestoresEvenAfterNightStopClearedRunningMemory() {
        assertTrue(daily.allowsAutomaticStart(at("2026-11-03T07:00:00"), null, lastRunning = false))
        assertFalse(daily.allowsAutomaticStart(at("2026-11-03T01:00:00"), null, lastRunning = true))
    }

    @Test fun disabledSchedulePreservesLegacyRunningMemory() {
        val off = daily.copy(enabled = false)
        assertTrue(off.allowsAutomaticStart(at("2026-11-03T01:00:00"), null, lastRunning = true))
        assertFalse(off.allowsAutomaticStart(at("2026-11-03T12:00:00"), null, lastRunning = false))
    }

    @Test fun manualStopBlocksWorkerAndStickyRestorationDuringWindow() {
        val override = daily.manualOverride(at("2026-11-03T10:00:00"), run = false)
        assertFalse(daily.allowsAutomaticStart(at("2026-11-03T11:00:00"), override, lastRunning = true))
    }

    @Test fun missedAlarmUsesCurrentWindowRatherThanReplayingOldStart() {
        assertFalse(daily.allowsAutomaticStart(at("2026-11-03T23:15:00"), null, lastRunning = true))
    }

    @Test fun dstGapCannotCreateAReversedWindow() {
        val config = daily.copy(startMinutes = 150, stopMinutes = 180)
        val now = ZonedDateTime.parse("2026-03-08T03:15:00-04:00[America/New_York]")
        assertFalse(config.shouldRun(now))
        assertEquals("2026-03-09", config.nextTransition(now)!!.at.toLocalDate().toString())
    }
}
