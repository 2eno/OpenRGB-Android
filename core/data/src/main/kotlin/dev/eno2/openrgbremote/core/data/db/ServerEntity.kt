package dev.eno2.openrgbremote.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.eno2.openrgbremote.core.domain.model.Server

@Entity(tableName = "servers", indices = [Index(value = ["host", "port"], unique = true)])
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int,
    @ColumnInfo(name = "auto_connect") val autoConnect: Boolean,
    @ColumnInfo(name = "created_at") val createdAtMillis: Long,
) {
    fun toDomain() = Server(id, name, host, port, autoConnect, createdAtMillis)

    companion object {
        fun from(server: Server) = ServerEntity(server.id, server.name, server.host, server.port, server.autoConnect, server.createdAtMillis)
    }
}
