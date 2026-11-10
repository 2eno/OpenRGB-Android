package dev.eno2.openrgbremote.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import dev.eno2.openrgbremote.core.domain.model.QuickAction
import dev.eno2.openrgbremote.core.domain.model.Schedule
import kotlinx.coroutines.flow.Flow
import java.time.DayOfWeek
import java.time.LocalTime

@Entity(tableName = "schedules")
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Minutes after midnight. */
    @ColumnInfo(name = "minute_of_day") val minuteOfDay: Int,
    /** Bit 0 = Monday ... bit 6 = Sunday. */
    val days: Int,
    /** [QuickAction.encode]. */
    val action: String,
    val enabled: Boolean,
) {
    /** null if the stored action is no longer valid (e.g. an unknown kind). */
    fun toDomain(): Schedule? {
        val decoded = QuickAction.decode(action)?.takeIf { it !is QuickAction.StartEffect } ?: return null
        return Schedule(
            id = id,
            time = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60),
            days = DayOfWeek.entries.filter { days and (1 shl (it.value - 1)) != 0 }.toSet(),
            action = decoded,
            enabled = enabled,
        )
    }

    companion object {
        fun from(schedule: Schedule) = ScheduleEntity(
            id = schedule.id,
            minuteOfDay = schedule.time.hour * 60 + schedule.time.minute,
            days = schedule.days.fold(0) { bits, day -> bits or (1 shl (day.value - 1)) },
            action = schedule.action.encode(),
            enabled = schedule.enabled,
        )
    }
}

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules ORDER BY minute_of_day, id")
    fun observeAll(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules WHERE id = :id")
    suspend fun get(id: Long): ScheduleEntity?

    @Insert
    suspend fun insert(schedule: ScheduleEntity): Long

    @Update
    suspend fun update(schedule: ScheduleEntity)

    @Query("DELETE FROM schedules WHERE id = :id")
    suspend fun delete(id: Long)
}
