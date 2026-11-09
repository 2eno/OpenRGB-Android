package io.github.openrgbremote.core.data.repository

import io.github.openrgbremote.core.data.db.ServerDao
import io.github.openrgbremote.core.data.db.ServerEntity
import io.github.openrgbremote.core.domain.model.Server
import io.github.openrgbremote.core.domain.repository.ServerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class RoomServerRepository @Inject constructor(private val dao: ServerDao) : ServerRepository {
    override fun observeServers(): Flow<List<Server>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getServer(id: Long): Server? = dao.get(id)?.toDomain()

    override suspend fun findByAddress(host: String, port: Int): Server? = dao.findByAddress(host, port)?.toDomain()

    override suspend fun save(server: Server): Long =
        if (server.id == 0L) {
            dao.insert(ServerEntity.from(server))
        } else {
            dao.update(ServerEntity.from(server))
            server.id
        }

    override suspend fun delete(id: Long) = dao.delete(id)
}
