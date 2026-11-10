package dev.eno2.openrgbremote.core.data

import dev.eno2.openrgbremote.core.data.db.ScheduleEntity
import dev.eno2.openrgbremote.core.domain.model.QuickAction
import dev.eno2.openrgbremote.core.domain.model.Schedule
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScheduleEntityTest {
    @Test
    fun `schedules round trip through the database row`() {
        val schedule = Schedule(4, LocalTime.of(23, 45), setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY), QuickAction.ApplyProfile(-7), enabled = false)
        val entity = ScheduleEntity.from(schedule)
        assertEquals(23 * 60 + 45, entity.minuteOfDay)
        assertEquals(0b1000001, entity.days)
        assertEquals(schedule, entity.toDomain())
        assertEquals(DayOfWeek.entries.toSet(), entity.copy(days = 0x7F).toDomain()!!.days)
    }

    @Test
    fun `invalid stored actions are dropped instead of crashing`() {
        val row = ScheduleEntity(1, 60, 1, "effect:FIRE", true)
        assertNull(row.toDomain(), "effects are not allowed in schedules")
        assertNull(row.copy(action = "garbage").toDomain())
    }
}
