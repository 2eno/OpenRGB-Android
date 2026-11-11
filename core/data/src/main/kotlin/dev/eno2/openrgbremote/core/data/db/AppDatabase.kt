package dev.eno2.openrgbremote.core.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ServerEntity::class, FavoriteColorEntity::class, ProfileEntity::class, ScheduleEntity::class],
    version = 6,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun favoriteColorDao(): FavoriteColorDao
    abstract fun profileDao(): ProfileDao
    abstract fun scheduleDao(): ScheduleDao

    companion object {
        const val NAME = "openrgb-remote.db"
    }
}
