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
import dev.eno2.openrgbremote.core.domain.model.ProfileAnimation
import dev.eno2.openrgbremote.core.effects.EffectId
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
    /** [EffectId] name of a dynamic profile, null for a static one (schema 5). */
    val effect: String? = null,
    @ColumnInfo(name = "effect_speed", defaultValue = "1.0") val effectSpeed: Float = 1f,
    @ColumnInfo(name = "effect_reverse", defaultValue = "0") val effectReverse: Boolean = false,
) {
    fun toDomain() = Profile(
        id = id,
        name = name,
        colors = colors.split(',').mapNotNull { it.toIntOrNull(16) },
        layout = ColorLayout.entries.firstOrNull { it.name == layout } ?: ColorLayout.GRADIENT,
        brightness = brightness,
        createdAtMillis = createdAtMillis,
        // An effect this app version does not know turns the profile into a static one.
        animation = effect?.let { name -> EffectId.entries.firstOrNull { it.name == name } }?.let { ProfileAnimation(it, effectSpeed, effectReverse) },
    )

    companion object {
        fun from(profile: Profile) = ProfileEntity(
            id = profile.id,
            name = profile.name,
            colors = profile.colors.joinToString(",") { "%06X".format(it and 0xFFFFFF) },
            layout = profile.layout.name,
            brightness = profile.brightness,
            createdAtMillis = profile.createdAtMillis,
            effect = profile.animation?.effect?.name,
            effectSpeed = profile.animation?.speed ?: 1f,
            effectReverse = profile.animation?.reverse ?: false,
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
