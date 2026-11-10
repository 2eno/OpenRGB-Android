package dev.eno2.openrgbremote.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import dev.eno2.openrgbremote.core.domain.model.ColorLayout
import dev.eno2.openrgbremote.core.domain.model.Profile
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Comma-separated `RRGGBB` values, e.g. "FF0000,0000FF". */
    val colors: String,
    /** [ColorLayout] name. */
    val layout: String,
    val brightness: Float,
    @ColumnInfo(name = "created_at") val createdAtMillis: Long,
) {
    fun toDomain() = Profile(
        id = id,
        name = name,
        colors = colors.split(',').mapNotNull { it.toIntOrNull(16) },
        layout = ColorLayout.entries.firstOrNull { it.name == layout } ?: ColorLayout.GRADIENT,
        brightness = brightness,
        createdAtMillis = createdAtMillis,
    )

    companion object {
        fun from(profile: Profile) = ProfileEntity(
            id = profile.id,
            name = profile.name,
            colors = profile.colors.joinToString(",") { "%06X".format(it and 0xFFFFFF) },
            layout = profile.layout.name,
            brightness = profile.brightness,
            createdAtMillis = profile.createdAtMillis,
        )
    }
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun get(id: Long): ProfileEntity?

    @Insert
    suspend fun insert(profile: ProfileEntity): Long

    @Update
    suspend fun update(profile: ProfileEntity)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun delete(id: Long)
}
