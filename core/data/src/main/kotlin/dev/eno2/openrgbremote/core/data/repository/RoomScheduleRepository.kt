package dev.eno2.openrgbremote.core.data.repository

import dev.eno2.openrgbremote.core.data.db.ScheduleDao
import dev.eno2.openrgbremote.core.data.db.ScheduleEntity
import dev.eno2.openrgbremote.core.domain.model.Schedule
import dev.eno2.openrgbremote.core.domain.repository.ScheduleRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class RoomScheduleRepository @Inject constructor(private val dao: ScheduleDao) : ScheduleRepository {
    override fun observeSchedules(): Flow<List<Schedule>> = dao.observeAll().map { list -> list.mapNotNull { it.toDomain() } }

    override suspend fun getSchedule(id: Long): Schedule? = dao.get(id)?.toDomain()

    override suspend fun save(schedule: Schedule): Long =
        if (schedule.id == 0L) {
            dao.insert(ScheduleEntity.from(schedule))
        } else {
            dao.update(ScheduleEntity.from(schedule))
            schedule.id
        }

    override suspend fun delete(id: Long) = dao.delete(id)
}
