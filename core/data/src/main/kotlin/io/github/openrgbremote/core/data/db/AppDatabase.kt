package io.github.openrgbremote.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [ServerEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao

    companion object {
        const val NAME = "openrgb-remote.db"
    }
}
