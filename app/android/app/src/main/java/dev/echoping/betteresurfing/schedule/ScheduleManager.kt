package dev.echoping.betteresurfing.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.echoping.betteresurfing.engine.Repo
import dev.echoping.betteresurfing.service.AuthService
import dev.echoping.betteresurfing.store.Prefs
import java.time.ZonedDateTime

/** A single alarm points to the next boundary. Delivery always recalculates current policy. */
object ScheduleManager {
    const val ACTION_TICK = "dev.echoping.be.SCHEDULE_TICK"
    private const val REQUEST_CODE = 7001

    fun canScheduleExactly(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    @Synchronized
    fun automaticAllowed(now: ZonedDateTime = ZonedDateTime.now()): Boolean =
        Prefs.schedule.allowsAutomaticStart(now, Prefs.scheduleOverride, Prefs.lastRunning)

    @Synchronized
    fun manualAction(context: Context, run: Boolean) {
        Prefs.setScheduleOverride(Prefs.schedule.manualOverride(ZonedDateTime.now(), run))
        reschedule(context)
    }

    @Synchronized
    fun save(context: Context, value: ScheduleConfig) {
        Prefs.setSchedule(value)
        reconcile(context)
    }

    @Synchronized
    fun reschedule(context: Context) {
        Prefs.init(context)
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(context, REQUEST_CODE,
            Intent(context, ScheduleReceiver::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarms.cancel(pending)
        val next = Prefs.schedule.nextTransition(ZonedDateTime.now()) ?: return
        val at = next.at.toInstant().toEpochMilli()
        try {
            if (canScheduleExactly(context)) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        } catch (_: SecurityException) {
            // Permission can change between checking and scheduling.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    @Synchronized
    fun reconcile(context: Context) {
        reschedule(context)
        if (!Prefs.schedule.enabled) return
        val allowed = automaticAllowed()
        try {
            if (allowed && !AuthService.isRunning && Prefs.activeAccount() != null) {
                AuthService.restore(context)
                Repo.onLog(1, "定时计划：启动认证服务")
            } else if (!allowed && AuthService.isRunning) {
                AuthService.stopAutomatically(context)
                Repo.onLog(1, "定时计划：停止认证服务")
            }
        } catch (ex: Exception) {
            Repo.onLog(2, "定时执行受系统限制，请检查后台与准时执行权限: ${ex.message}")
        }
    }
}
