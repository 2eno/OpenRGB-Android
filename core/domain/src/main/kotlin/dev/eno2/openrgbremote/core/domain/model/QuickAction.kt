package dev.eno2.openrgbremote.core.domain.model

import dev.eno2.openrgbremote.core.effects.EffectId
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Something a widget button, Quick Settings tile or schedule can trigger. */
sealed interface QuickAction {
    data class ApplyProfile(val profileId: Long) : QuickAction

    data class StartEffect(val effect: EffectId) : QuickAction

    /** Stops a running effect and switches all LEDs off. */
    data object Off : QuickAction

    /** Compact text form for storage, e.g. "profile:-1", "effect:FIRE", "off". */
    fun encode(): String = when (this) {
        is ApplyProfile -> "profile:$profileId"
        is StartEffect -> "effect:${effect.name}"
        Off -> "off"
    }

    companion object {
        const val SLOTS = 4

        fun decode(text: String?): QuickAction? {
            if (text.isNullOrBlank()) return null
            val (kind, value) = text.split(':', limit = 2).let { it[0] to it.getOrNull(1) }
            return when (kind) {
                "profile" -> value?.toLongOrNull()?.let { ApplyProfile(it) }
                "effect" -> EffectId.entries.firstOrNull { it.name == value }?.let { StartEffect(it) }
                "off" -> Off
                else -> null
            }
        }
    }
}

/**
 * Applies [action] at [time] on [days]. Only actions that do not need a foreground service are
 * allowed (profiles and "off"): Android does not let background alarms start one.
 */
data class Schedule(
    val id: Long = 0,
    val time: LocalTime,
    val days: Set<DayOfWeek>,
    val action: QuickAction,
    val enabled: Boolean = true,
) {
    init {
        require(action !is QuickAction.StartEffect) { "Schedules cannot start effects" }
    }

    /** Next moment after [now] at which this schedule fires, or null if it never does. */
    fun nextTrigger(now: ZonedDateTime): ZonedDateTime? {
        if (!enabled || days.isEmpty()) return null
        for (offset in 0..7) {
            val date = now.toLocalDate().plusDays(offset.toLong())
            if (date.dayOfWeek !in days) continue
            // atZone resolves DST gaps by moving forward, so a 02:30 schedule still fires on the
            // night the clocks jump from 02:00 to 03:00.
            val candidate = LocalDateTime.of(date, time).atZone(now.zone)
            if (candidate.isAfter(now)) return candidate
        }
        return null
    }

    companion object {
        fun nextTrigger(schedules: List<Schedule>, now: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())): Pair<Schedule, ZonedDateTime>? =
            schedules.mapNotNull { s -> s.nextTrigger(now)?.let { s to it } }.minByOrNull { it.second }
    }
}
