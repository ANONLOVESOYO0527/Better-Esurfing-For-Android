package dev.echoping.betteresurfing.ui

import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.echoping.betteresurfing.schedule.ScheduleManager
import dev.echoping.betteresurfing.store.Prefs
import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleSettings(onChanged: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var draft by remember { mutableStateOf(Prefs.schedule) }
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    var exact by remember { mutableStateOf(ScheduleManager.canScheduleExactly(context)) }
    var result by remember { mutableStateOf("") }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                exact = ScheduleManager.canScheduleExactly(context)
                now = ZonedDateTime.now()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val error = if (draft.enabled) draft.validationError else null
    val next = draft.nextTransition(now)
    val names = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    fun timeLabel(minutes: Int) = String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60)
    fun pickTime(start: Boolean) {
        val minutes = (if (start) draft.startMinutes else draft.stopMinutes).coerceIn(0, 1439)
        TimePickerDialog(context, { _, hour, minute ->
            draft = if (start) draft.copy(startMinutes = hour * 60 + minute)
                else draft.copy(stopMinutes = hour * 60 + minute)
            result = ""
        }, minutes / 60, minutes % 60, android.text.format.DateFormat.is24HourFormat(context)).show()
    }

    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ListItem(
                headlineContent = { Text("定时运行") },
                supportingContent = { Text("自己设置每天的启动、停止时间和重复星期") },
                trailingContent = { Switch(draft.enabled, { draft = draft.copy(enabled = it); result = "" }) },
            )
            OutlinedButton(onClick = { pickTime(true) }, modifier = Modifier.fillMaxWidth()) {
                Text("启动时间　${timeLabel(draft.startMinutes)}")
            }
            OutlinedButton(onClick = { pickTime(false) }, modifier = Modifier.fillMaxWidth()) {
                Text("停止时间　${timeLabel(draft.stopMinutes)}")
            }
            Text("重复星期", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DayOfWeek.entries.forEach { day ->
                    FilterChip(
                        selected = day in draft.days,
                        onClick = {
                            draft = draft.copy(days = if (day in draft.days) draft.days - day else draft.days + day)
                            result = ""
                        },
                        label = { Text(names[day.value - 1]) },
                    )
                }
            }
            Text("星期按启动日期计算，支持跨夜，重启后恢复计划。到点使用当前选中账号，未连 Wi-Fi 时等待连接。",
                style = MaterialTheme.typography.bodySmall)
            Text("手动开关优先，持续到下一次定时切换；保存修改后立即按新计划执行。关闭定时不会改变当前运行状态。",
                style = MaterialTheme.typography.bodySmall)
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            if (next != null) {
                val whenText = next.at.format(DateTimeFormatter.ofPattern("MM月dd日 E HH:mm", Locale.SIMPLIFIED_CHINESE))
                Text("计划预览：$whenText ${if (next.run) "启动" else "停止"}", style = MaterialTheme.typography.bodySmall)
            } else if (!draft.enabled) {
                Text("定时已关闭；修改后点击保存生效", style = MaterialTheme.typography.bodySmall)
            }
            if (Build.VERSION.SDK_INT >= 31 && !exact) {
                Text("未允许准时执行，系统可能延迟定时开关；后台启动受限时可能需要打开软件。",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                            .setData(Uri.parse("package:${context.packageName}")))
                    }.onFailure { result = "无法打开权限页面，请到系统设置中允许本应用的闹钟与提醒权限" }
                }) { Text("允许准时执行") }
            }
            Button(
                enabled = error == null,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    ScheduleManager.save(context, draft)
                    now = ZonedDateTime.now()
                    result = "计划已保存"
                    onChanged()
                },
            ) { Text("保存计划") }
            if (result.isNotEmpty()) Text(result, style = MaterialTheme.typography.bodySmall)
        }
    }
}
