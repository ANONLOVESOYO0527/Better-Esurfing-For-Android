package dev.echoping.betteresurfing.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.echoping.betteresurfing.store.Prefs

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(ScheduleManager.ACTION_TICK, Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED,
                "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")) return
        Prefs.init(context)
        if (intent.action == Intent.ACTION_TIME_CHANGED || intent.action == Intent.ACTION_TIMEZONE_CHANGED) {
            Prefs.setScheduleOverride(null)
        }
        ScheduleManager.reconcile(context)
    }
}
