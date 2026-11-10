package dev.eno2.openrgbremote.core.data.profile

import dev.eno2.openrgbremote.core.data.db.ProfileDao
import dev.eno2.openrgbremote.core.data.db.ProfileEntity
import dev.eno2.openrgbremote.core.domain.model.Profile
import dev.eno2.openrgbremote.core.domain.repository.ProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class RoomProfileRepository @Inject constructor(private val dao: ProfileDao) : ProfileRepository {
    override fun observeProfiles(): Flow<List<Profile>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getProfile(id: Long): Profile? = dao.get(id)?.toDomain()

    override suspend fun save(profile: Profile): Long =
        if (profile.id == 0L) {
            dao.insert(ProfileEntity.from(profile))
        } else {
            dao.update(ProfileEntity.from(profile))
            profile.id
        }

    override suspend fun delete(id: Long) = dao.delete(id)
}
