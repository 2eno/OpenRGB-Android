package io.github.openrgbremote.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "favorite_colors")
data class FavoriteColorEntity(
    /** `0xRRGGBB`; a color can be a favorite only once. */
    @PrimaryKey val rgb: Int,
    @ColumnInfo(name = "created_at") val createdAtMillis: Long,
)

@Dao
interface FavoriteColorDao {
    @Query("SELECT rgb FROM favorite_colors ORDER BY created_at DESC")
    fun observeAll(): Flow<List<Int>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(color: FavoriteColorEntity)

    @Query("DELETE FROM favorite_colors WHERE rgb = :rgb")
    suspend fun delete(rgb: Int)
}
