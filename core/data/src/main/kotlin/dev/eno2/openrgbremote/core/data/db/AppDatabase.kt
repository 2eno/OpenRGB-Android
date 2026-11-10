package dev.eno2.openrgbremote.core.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ServerEntity::class, FavoriteColorEntity::class, ProfileEntity::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun favoriteColorDao(): FavoriteColorDao
    abstract fun profileDao(): ProfileDao

    companion object {
        const val NAME = "openrgb-remote.db"
    }
}
