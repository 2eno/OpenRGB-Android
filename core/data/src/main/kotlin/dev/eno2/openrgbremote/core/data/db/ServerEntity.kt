package dev.eno2.openrgbremote.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.eno2.openrgbremote.core.domain.model.JumpHost
import dev.eno2.openrgbremote.core.domain.model.Server
import dev.eno2.openrgbremote.core.domain.model.SshAuth
import dev.eno2.openrgbremote.core.domain.model.SshHostKey

/**
 * Schema 6 adds the jump host. [jumpHost] is "" for a direct connection rather than NULL: SQLite
 * treats NULLs in a unique index as distinct, which would allow duplicate direct servers.
 * The same OpenRGB address may be saved once per jump host (127.0.0.1:6742 is common).
 */
@Entity(tableName = "servers", indices = [Index(value = ["host", "port", "jump_host"], unique = true)])
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int,
    @ColumnInfo(name = "auto_connect") val autoConnect: Boolean,
    @ColumnInfo(name = "created_at") val createdAtMillis: Long,
    @ColumnInfo(name = "jump_host", defaultValue = "") val jumpHost: String = "",
    @ColumnInfo(name = "jump_port", defaultValue = "22") val jumpPort: Int = JumpHost.DEFAULT_PORT,
    @ColumnInfo(name = "jump_user", defaultValue = "") val jumpUser: String = "",
    /** [SshAuth] name. */
    @ColumnInfo(name = "jump_auth", defaultValue = "APP_KEY") val jumpAuth: String = SshAuth.APP_KEY.name,
    /** Confirmed host key, "type base64"; null until the user confirmed the fingerprint. */
    @ColumnInfo(name = "jump_host_key") val jumpHostKey: String? = null,
) {
    fun toDomain() = Server(
        id = id,
        name = name,
        host = host,
        port = port,
        autoConnect = autoConnect,
        createdAtMillis = createdAtMillis,
        jumpHost = jumpHost.takeIf { it.isNotEmpty() }?.let {
            JumpHost(
                host = it,
                port = jumpPort,
                username = jumpUser,
                auth = SshAuth.entries.firstOrNull { a -> a.name == jumpAuth } ?: SshAuth.APP_KEY,
                hostKey = SshHostKey.decode(jumpHostKey),
            )
        },
    )

    companion object {
        fun from(server: Server) = ServerEntity(
            id = server.id,
            name = server.name,
            host = server.host,
            port = server.port,
            autoConnect = server.autoConnect,
            createdAtMillis = server.createdAtMillis,
            jumpHost = server.jumpHost?.host.orEmpty(),
            jumpPort = server.jumpHost?.port ?: JumpHost.DEFAULT_PORT,
            jumpUser = server.jumpHost?.username.orEmpty(),
            jumpAuth = (server.jumpHost?.auth ?: SshAuth.APP_KEY).name,
            jumpHostKey = server.jumpHost?.hostKey?.encode(),
        )
    }
}
