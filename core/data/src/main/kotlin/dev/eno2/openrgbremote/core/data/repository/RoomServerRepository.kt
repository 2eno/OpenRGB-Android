package dev.eno2.openrgbremote.core.data.repository

import dev.eno2.openrgbremote.core.data.db.ServerDao
import dev.eno2.openrgbremote.core.data.db.ServerEntity
import dev.eno2.openrgbremote.core.domain.model.Server
import dev.eno2.openrgbremote.core.domain.repository.ServerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class RoomServerRepository @Inject constructor(private val dao: ServerDao) : ServerRepository {
    override fun observeServers(): Flow<List<Server>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getServer(id: Long): Server? = dao.get(id)?.toDomain()

    override suspend fun findByAddress(host: String, port: Int, jumpHost: String?): Server? =
        dao.findByAddress(host, port, jumpHost.orEmpty())?.toDomain()

    override suspend fun save(server: Server): Long =
        if (server.id == 0L) {
            dao.insert(ServerEntity.from(server))
        } else {
            dao.update(ServerEntity.from(server))
            server.id
        }

    override suspend fun delete(id: Long) = dao.delete(id)
}
