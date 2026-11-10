package dev.eno2.openrgbremote.core.domain

import dev.eno2.openrgbremote.core.domain.model.QuickAction
import dev.eno2.openrgbremote.core.domain.model.Schedule
import dev.eno2.openrgbremote.core.effects.EffectId
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class QuickActionTest {

    @Test
    fun `actions survive encoding and garbage decodes to null`() {
        val actions = listOf(QuickAction.ApplyProfile(-3), QuickAction.ApplyProfile(42), QuickAction.StartEffect(EffectId.FIRE), QuickAction.Off)
        actions.forEach { assertEquals(it, QuickAction.decode(it.encode())) }
        listOf(null, "", "profile:x", "effect:NOPE", "unknown:1", "profile").forEach { assertNull(QuickAction.decode(it), it.toString()) }
    }

    private val berlin = ZoneId.of("Europe/Berlin")
    private fun at(text: String) = ZonedDateTime.parse(text + "+01:00[Europe/Berlin]")

    @Test
    fun `next trigger is later today, on a later day or next week`() {
        val weekend = Schedule(1, LocalTime.of(22, 30), setOf(SATURDAY, SUNDAY), QuickAction.Off)
        // Friday 2026-11-13 20:00 -> Saturday 22:30
        assertEquals(at("2026-11-14T22:30"), weekend.nextTrigger(at("2026-11-13T20:00")))
        // Saturday 21:00 -> same day 22:30
        assertEquals(at("2026-11-14T22:30"), weekend.nextTrigger(at("2026-11-14T21:00")))
        // Saturday exactly 22:30 -> Sunday (strictly after now)
        assertEquals(at("2026-11-15T22:30"), weekend.nextTrigger(at("2026-11-14T22:30")))
        // Sunday 23:00 -> next Saturday
        assertEquals(at("2026-11-21T22:30"), weekend.nextTrigger(at("2026-11-15T23:00")))
        val monday = Schedule(2, LocalTime.of(7, 0), setOf(MONDAY), QuickAction.ApplyProfile(-6))
        // Monday 08:00 -> next Monday, a full week later
        assertEquals(at("2026-11-23T07:00"), monday.nextTrigger(at("2026-11-16T08:00")))
    }

    @Test
    fun `disabled, dayless schedules never fire and effects are rejected`() {
        val now = at("2026-11-13T20:00")
        assertNull(Schedule(1, LocalTime.NOON, setOf(FRIDAY), QuickAction.Off, enabled = false).nextTrigger(now))
        assertNull(Schedule(1, LocalTime.NOON, emptySet(), QuickAction.Off).nextTrigger(now))
        assertFailsWith<IllegalArgumentException> { Schedule(1, LocalTime.NOON, setOf(FRIDAY), QuickAction.StartEffect(EffectId.FIRE)) }
    }

    @Test
    fun `daylight saving gaps still fire`() {
        // 2027-03-28: clocks jump from 02:00 to 03:00 in Berlin.
        val nightly = Schedule(1, LocalTime.of(2, 30), setOf(SUNDAY), QuickAction.Off)
        val trigger = nightly.nextTrigger(ZonedDateTime.of(2027, 3, 27, 12, 0, 0, 0, berlin))!!
        assertEquals(28, trigger.dayOfMonth)
        assertEquals(3, trigger.hour)
    }

    @Test
    fun `earliest of several schedules wins`() {
        val now = at("2026-11-13T20:00")
        val a = Schedule(1, LocalTime.of(23, 0), setOf(FRIDAY), QuickAction.Off)
        val b = Schedule(2, LocalTime.of(21, 0), setOf(FRIDAY), QuickAction.Off)
        val c = Schedule(3, LocalTime.of(19, 0), setOf(FRIDAY), QuickAction.Off, enabled = false)
        assertEquals(2L, Schedule.nextTrigger(listOf(a, b, c), now)?.first?.id)
        assertNull(Schedule.nextTrigger(listOf(c), now))
    }
}
