package dev.eno2.openrgbremote.quick

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.core.content.getSystemService
import androidx.test.core.app.ApplicationProvider
import dev.eno2.openrgbremote.core.domain.model.QuickAction
import dev.eno2.openrgbremote.core.domain.model.Schedule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Alarms against Robolectric's AlarmManager: one per enabled schedule, at the right time. */
@RunWith(RobolectricTestRunner::class)
class ScheduleAlarmsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val shadow = shadowOf(context.getSystemService<AlarmManager>()!!)
    private val alarms = ScheduleAlarms(context)

    // Monday, 10 November 2025, 06:30 in Berlin.
    private val now = ZonedDateTime.of(2025, 11, 10, 6, 30, 0, 0, ZoneId.of("Europe/Berlin"))

    private fun schedule(id: Long, time: LocalTime, days: Set<DayOfWeek>, enabled: Boolean = true) =
        Schedule(id, time, days, QuickAction.Off, enabled)

    /** The schedule id an alarm fires for. Robolectric 4.16 exposes the PendingIntent only as a deprecated field. */
    @Suppress("DEPRECATION")
    private fun ShadowAlarmManager.ScheduledAlarm.scheduleId() = shadowOf(operation).savedIntent.getLongExtra(ScheduleReceiver.EXTRA_ID, -1)

    private fun scheduledIds() = shadow.scheduledAlarms.map { it.scheduleId() }.toSet()

    @Test
    fun `one inexact wake-up alarm per enabled schedule at its next time`() {
        val morning = schedule(1, LocalTime.of(7, 0), setOf(DayOfWeek.MONDAY))
        val weekend = schedule(2, LocalTime.of(10, 0), setOf(DayOfWeek.SATURDAY))
        alarms.sync(listOf(morning, weekend, schedule(3, LocalTime.of(8, 0), setOf(DayOfWeek.MONDAY), enabled = false)), now)

        assertEquals(setOf(1L, 2L), scheduledIds())
        val first = shadow.scheduledAlarms.single { it.scheduleId() == 1L }
        assertEquals(AlarmManager.RTC_WAKEUP, first.getType())
        assertEquals(now.withHour(7).withMinute(0).toInstant().toEpochMilli(), first.triggerAtMs)
        assertEquals(10 * 60 * 1000L, first.windowLengthMs, "inexact: no exact-alarm permission needed")
        val saturday = now.plusDays(5).withHour(10).withMinute(0).toInstant().toEpochMilli()
        assertEquals(saturday, shadow.scheduledAlarms.first { it !== first }.triggerAtMs)
    }

    @Test
    fun `disabling or deleting a schedule cancels its alarm`() {
        val a = schedule(1, LocalTime.of(7, 0), setOf(DayOfWeek.MONDAY))
        val b = schedule(2, LocalTime.of(22, 0), DayOfWeek.entries.toSet())
        alarms.sync(listOf(a, b), now)
        assertEquals(setOf(1L, 2L), scheduledIds())

        alarms.sync(listOf(a.copy(enabled = false), b), now)
        assertEquals(setOf(2L), scheduledIds())
        alarms.sync(emptyList(), now)
        assertTrue(shadow.scheduledAlarms.isEmpty())
    }

    @Test
    fun `receivers are registered for the alarm and for reboot or clock changes`() {
        val alarm = Intent(context, ScheduleReceiver::class.java)
        assertTrue(context.packageManager.queryBroadcastReceivers(alarm, 0).isNotEmpty())
        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            val receivers = context.packageManager.queryBroadcastReceivers(Intent(action).setPackage(context.packageName), 0)
            assertTrue(receivers.any { it.activityInfo.name == ScheduleRestoreReceiver::class.java.name }, action)
        }
    }
}
