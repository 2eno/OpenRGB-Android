package dev.eno2.openrgbremote.quick

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.eno2.openrgbremote.core.domain.model.Schedule
import dev.eno2.openrgbremote.feature.quick.ScheduleAlarmCanceller
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One inexact alarm per enabled schedule (10 minute window). Inexact alarms need no special
 * permission and are batched by Android, which is why schedules can run a little late.
 */
@Singleton
class ScheduleAlarms @Inject constructor(@ApplicationContext private val context: Context) : ScheduleAlarmCanceller {

    private val alarms = requireNotNull(context.getSystemService<AlarmManager>())
    private var scheduledIds = emptySet<Long>()

    @Synchronized
    fun sync(schedules: List<Schedule>, now: ZonedDateTime = ZonedDateTime.now()) {
        val active = schedules.filter { it.nextTrigger(now) != null }
        (scheduledIds - active.map { it.id }.toSet()).forEach(::cancel)
        active.forEach { schedule(it, now) }
        scheduledIds = active.map { it.id }.toSet()
    }

    fun schedule(schedule: Schedule, now: ZonedDateTime = ZonedDateTime.now()) {
        val next = schedule.nextTrigger(now) ?: return cancel(schedule.id)
        alarms.setWindow(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), WINDOW_MILLIS, pendingIntent(schedule.id))
    }

    override fun cancel(id: Long) = alarms.cancel(pendingIntent(id))

    private fun pendingIntent(id: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        id.toInt(),
        Intent(context, ScheduleReceiver::class.java).putExtra(ScheduleReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        const val WINDOW_MILLIS = 10 * 60 * 1000L
    }
}

/** Runs a due schedule and plans its next occurrence. */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1)
        val entry = QuickEntryPoint.get(context)
        val pending = goAsync()
        entry.scope().launch {
            try {
                entry.schedules().getSchedule(id)?.takeIf { it.enabled }?.let { schedule ->
                    entry.scheduleAlarms().schedule(schedule, ZonedDateTime.now().plusMinutes(1))
                    // goAsync() leaves about 10 s before Android considers the receiver hung.
                    withTimeoutOrNull(RECEIVER_BUDGET_MILLIS) { entry.runner().run(schedule.action) }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_ID = "schedule_id"
        private const val RECEIVER_BUDGET_MILLIS = 9_000L
    }
}

/** Alarms do not survive reboots, and time or zone changes shift every trigger. */
class ScheduleRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in RESTORE_ACTIONS) return
        val entry = QuickEntryPoint.get(context)
        val pending = goAsync()
        entry.scope().launch {
            try {
                entry.scheduleAlarms().sync(entry.schedules().observeSchedules().first())
            } finally {
                pending.finish()
            }
        }
    }
}

private val RESTORE_ACTIONS = setOf(
    Intent.ACTION_BOOT_COMPLETED,
    Intent.ACTION_TIME_CHANGED,
    Intent.ACTION_TIMEZONE_CHANGED,
    Intent.ACTION_MY_PACKAGE_REPLACED,
)
