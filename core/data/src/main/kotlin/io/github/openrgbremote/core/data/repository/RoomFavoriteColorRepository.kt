package io.github.openrgbremote.core.data.repository

import io.github.openrgbremote.core.data.db.FavoriteColorDao
import io.github.openrgbremote.core.data.db.FavoriteColorEntity
import io.github.openrgbremote.core.domain.repository.FavoriteColorRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class RoomFavoriteColorRepository @Inject constructor(private val dao: FavoriteColorDao) : FavoriteColorRepository {
    override fun observeFavorites(): Flow<List<Int>> = dao.observeAll()

    override suspend fun add(rgb: Int) = dao.insert(FavoriteColorEntity(rgb and 0xFFFFFF, System.currentTimeMillis()))

    override suspend fun remove(rgb: Int) = dao.delete(rgb and 0xFFFFFF)
}
