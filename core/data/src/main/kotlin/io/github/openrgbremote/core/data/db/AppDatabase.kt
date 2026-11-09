package io.github.openrgbremote.core.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ServerEntity::class, FavoriteColorEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun favoriteColorDao(): FavoriteColorDao

    companion object {
        const val NAME = "openrgb-remote.db"
    }
}
