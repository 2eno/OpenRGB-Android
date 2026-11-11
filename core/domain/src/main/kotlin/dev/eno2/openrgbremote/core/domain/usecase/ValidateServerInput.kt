package dev.eno2.openrgbremote.core.domain.usecase

import dev.eno2.openrgbremote.core.domain.model.JumpHost
import dev.eno2.openrgbremote.core.domain.model.SshAuth
import dev.eno2.openrgbremote.core.domain.model.SshHostKey
import dev.eno2.openrgbremote.sdk.discovery.Ipv4Subnet

enum class HostError { EMPTY, CONTAINS_SPACES, INVALID }
enum class PortError { EMPTY, NOT_A_NUMBER, OUT_OF_RANGE }
enum class UsernameError { EMPTY, INVALID }

/** The jump host fields as typed in the form. */
data class JumpHostForm(
    val host: String,
    val port: String = JumpHost.DEFAULT_PORT.toString(),
    val username: String,
    val auth: SshAuth = SshAuth.APP_KEY,
    val hostKey: SshHostKey? = null,
)

data class JumpHostErrors(val host: HostError? = null, val port: PortError? = null, val username: UsernameError? = null) {
    val any: Boolean get() = host != null || port != null || username != null
}

/** Normalized, valid input. */
data class ServerInput(val name: String, val host: String, val port: Int, val jumpHost: JumpHost? = null)

sealed interface ServerInputValidation {
    data class Valid(val input: ServerInput) : ServerInputValidation
    data class Invalid(val hostError: HostError?, val portError: PortError?, val jumpErrors: JumpHostErrors? = null) : ServerInputValidation
}

/**
 * Checks host and port typed by the user.
 *
 * Accepted hosts: IPv4 literals, IPv6 literals (with or without brackets) and DNS names such as
 * `gaming-pc`, `gaming-pc.local` or `pc.fritz.box`. A blank name falls back to the host.
 */
class ValidateServerInput {

    operator fun invoke(name: String, host: String, port: String, jumpHost: JumpHostForm? = null): ServerInputValidation {
        val trimmedHost = cleanHost(host)
        val hostError = hostError(trimmedHost)
        val portValue = port.trim().toIntOrNull()
        val portError = portError(port)
        val jumpErrors = jumpHost?.let { errors(it) }
        if (hostError != null || portError != null || jumpErrors?.any == true) return ServerInputValidation.Invalid(hostError, portError, jumpErrors)
        val normalizedHost = trimmedHost.lowercase()
        val jump = jumpHost?.let {
            JumpHost(cleanHost(it.host).lowercase(), it.port.trim().toInt(), it.username.trim(), it.auth, it.hostKey)
        }
        // Behind a jump host the address is often 127.0.0.1, which makes a poor default name.
        val defaultName = jump?.host ?: normalizedHost
        return ServerInputValidation.Valid(
            ServerInput(name = name.trim().ifEmpty { defaultName }, host = normalizedHost, port = portValue!!, jumpHost = jump),
        )
    }

    private fun errors(form: JumpHostForm): JumpHostErrors {
        val user = form.username.trim()
        return JumpHostErrors(
            host = hostError(cleanHost(form.host)),
            port = portError(form.port),
            username = when {
                user.isEmpty() -> UsernameError.EMPTY
                // POSIX-ish user names; rejects spaces, '@' (user@host typed into the field) and ':'.
                !USERNAME.matches(user) -> UsernameError.INVALID
                else -> null
            },
        )
    }

    private fun cleanHost(host: String) = host.trim().removePrefix("[").removeSuffix("]")

    private fun hostError(trimmedHost: String): HostError? = when {
        trimmedHost.isEmpty() -> HostError.EMPTY
        trimmedHost.any { it.isWhitespace() } -> HostError.CONTAINS_SPACES
        !isValidHost(trimmedHost) -> HostError.INVALID
        else -> null
    }

    private fun portError(port: String): PortError? {
        val portText = port.trim()
        val portValue = portText.toIntOrNull()
        return when {
            portText.isEmpty() -> PortError.EMPTY
            portValue == null -> PortError.NOT_A_NUMBER
            portValue !in 1..65535 -> PortError.OUT_OF_RANGE
            else -> null
        }
    }

    private fun isValidHost(host: String): Boolean = when {
        Ipv4Subnet.parseIpv4(host) != null -> true
        ':' in host -> IPV6.matches(host) && host.count { it == ':' } in 2..7
        else -> isValidHostName(host)
    }

    private fun isValidHostName(host: String): Boolean {
        val name = host.removeSuffix(".")
        if (name.isEmpty() || name.length > 253) return false
        val labels = name.split('.')
        if (labels.all { label -> label.all { it.isDigit() } }) return false // "192.168.1" is a broken IP, not a name
        return labels.all { HOST_LABEL.matches(it) }
    }

    private companion object {
        val HOST_LABEL = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")
        val IPV6 = Regex("^[0-9A-Fa-f:.]+(%[A-Za-z0-9._-]+)?$")
        val USERNAME = Regex("^[A-Za-z0-9._][A-Za-z0-9._$-]{0,63}$")
    }
}
