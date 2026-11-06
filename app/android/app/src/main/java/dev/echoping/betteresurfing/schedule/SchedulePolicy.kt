package dev.echoping.betteresurfing.schedule

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

data class ScheduleTransition(val at: ZonedDateTime, val run: Boolean)
data class ScheduleOverride(val untilMillis: Long, val run: Boolean)

data class ScheduleConfig(
    val enabled: Boolean = false,
    val startMinutes: Int = 7 * 60,
    val stopMinutes: Int = 23 * 60,
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
) {
    val validationError: String?
        get() = when {
            startMinutes !in 0..1439 || stopMinutes !in 0..1439 -> "请选择有效时间"
            startMinutes == stopMinutes -> "启动与停止时间不能相同"
            days.isEmpty() -> "请至少选择一个重复星期"
            else -> null
        }

    private fun window(date: LocalDate, zone: ZoneId): Pair<ZonedDateTime, ZonedDateTime>? {
        if (date.dayOfWeek !in days) return null
        val start = date.atTime(LocalTime.of(startMinutes / 60, startMinutes % 60)).atZone(zone)
        val endDate = if (stopMinutes < startMinutes) date.plusDays(1) else date
        val end = endDate.atTime(LocalTime.of(stopMinutes / 60, stopMinutes % 60)).atZone(zone)
        // A spring DST gap can resolve a short interval into an empty/reversed window.
        return if (end.toInstant() > start.toInstant()) start to end else null
    }

    fun shouldRun(now: ZonedDateTime, override: ScheduleOverride? = null): Boolean {
        if (!enabled || validationError != null) return false
        if (override != null && now.toInstant().toEpochMilli() < override.untilMillis) return override.run
        return (0L..1L).any { previous ->
            val bounds = window(now.toLocalDate().minusDays(previous), now.zone) ?: return@any false
            !now.toInstant().isBefore(bounds.first.toInstant()) && now.toInstant().isBefore(bounds.second.toInstant())
        }
    }

    fun nextTransition(now: ZonedDateTime): ScheduleTransition? {
        if (!enabled || validationError != null) return null
        return (-1L..14L).flatMap { offset ->
            val bounds = window(now.toLocalDate().plusDays(offset), now.zone) ?: return@flatMap emptyList()
            listOf(ScheduleTransition(bounds.first, true), ScheduleTransition(bounds.second, false))
        }.filter { it.at.toInstant().isAfter(now.toInstant()) }.minByOrNull { it.at.toInstant() }
    }

    fun allowsAutomaticStart(now: ZonedDateTime, override: ScheduleOverride?, lastRunning: Boolean): Boolean =
        if (enabled) shouldRun(now, override) else lastRunning

    fun manualOverride(now: ZonedDateTime, run: Boolean): ScheduleOverride? =
        nextTransition(now)?.let { ScheduleOverride(it.at.toInstant().toEpochMilli(), run) }
}
