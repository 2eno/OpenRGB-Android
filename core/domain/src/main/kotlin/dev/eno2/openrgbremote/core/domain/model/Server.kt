package dev.eno2.openrgbremote.core.domain.model

import dev.eno2.openrgbremote.sdk.protocol.ProtocolConstants
import java.security.MessageDigest
import java.util.Base64

/**
 * A PC running the OpenRGB SDK server, as saved by the user.
 *
 * With a [jumpHost], [host] and [port] are the OpenRGB address *as seen from the jump host*
 * (often `127.0.0.1` when OpenRGB runs on the jump host itself).
 */
data class Server(
    val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int = ProtocolConstants.DEFAULT_PORT,
    /** Connect automatically whenever the app is in use. */
    val autoConnect: Boolean = true,
    val createdAtMillis: Long = 0,
    val jumpHost: JumpHost? = null,
) {
    val address: String get() = hostPort(host, port)
}

internal fun hostPort(host: String, port: Int): String = if (':' in host) "[$host]:$port" else "$host:$port"

enum class SshAuth {
    /** The key pair the app generates; its public key goes into the jump host's authorized_keys. */
    APP_KEY,
    PASSWORD,
}

/**
 * SSH server the connection is tunneled through, like `ssh -J`: the app logs in there and opens a
 * TCP channel to the OpenRGB server. The traffic is encrypted and the jump host checks who
 * connects, so OpenRGB itself can stay bound to 127.0.0.1.
 *
 * The password is not part of this model; it is kept encrypted by the credential store.
 */
data class JumpHost(
    val host: String,
    val port: Int = DEFAULT_PORT,
    val username: String,
    val auth: SshAuth = SshAuth.APP_KEY,
    /** The host key the user confirmed; null until then, which blocks connecting. */
    val hostKey: SshHostKey? = null,
) {
    val address: String get() = hostPort(host, port)

    companion object {
        const val DEFAULT_PORT = 22
    }
}

/** A public SSH host key as in known_hosts: algorithm name and base64 key blob. */
data class SshHostKey(val type: String, val base64: String) {
    /** Same format as `ssh-keygen -lf`: "SHA256:" + unpadded base64 of the blob's SHA-256. */
    val fingerprint: String
        get() {
            val blob = Base64.getDecoder().decode(base64)
            val digest = MessageDigest.getInstance("SHA-256").digest(blob)
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        }

    /** "type base64", as stored. */
    fun encode(): String = "$type $base64"

    companion object {
        fun decode(text: String?): SshHostKey? {
            val parts = text?.trim()?.split(' ')?.filter { it.isNotEmpty() } ?: return null
            if (parts.size < 2) return null
            val valid = runCatching { Base64.getDecoder().decode(parts[1]) }.isSuccess
            return if (valid) SshHostKey(parts[0], parts[1]) else null
        }
    }
}
