package dev.eno2.openrgbremote.core.domain.usecase

import dev.eno2.openrgbremote.core.domain.model.Server
import dev.eno2.openrgbremote.core.domain.repository.ServerRepository

sealed interface SaveServerResult {
    data class Saved(val id: Long) : SaveServerResult
    data class Invalid(val validation: ServerInputValidation.Invalid) : SaveServerResult

    /** Another saved server already uses this host and port. */
    data class Duplicate(val existing: Server) : SaveServerResult
}

/** Validates, normalizes and stores a server. [id] 0 creates a new one. */
class SaveServer(
    private val servers: ServerRepository,
    private val validate: ValidateServerInput = ValidateServerInput(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend operator fun invoke(
        id: Long,
        name: String,
        host: String,
        port: String,
        autoConnect: Boolean = true,
        jumpHost: JumpHostForm? = null,
    ): SaveServerResult {
        val input = when (val result = validate(name, host, port, jumpHost)) {
            is ServerInputValidation.Invalid -> return SaveServerResult.Invalid(result)
            is ServerInputValidation.Valid -> result.input
        }
        val existing = servers.findByAddress(input.host, input.port, input.jumpHost?.host)
        if (existing != null && existing.id != id) return SaveServerResult.Duplicate(existing)
        val previous = if (id != 0L) servers.getServer(id) else null
        val server = Server(
            id = id,
            name = input.name,
            host = input.host,
            port = input.port,
            autoConnect = autoConnect,
            jumpHost = input.jumpHost,
            createdAtMillis = previous?.createdAtMillis ?: clock(),
        )
        return SaveServerResult.Saved(servers.save(server))
    }
}
