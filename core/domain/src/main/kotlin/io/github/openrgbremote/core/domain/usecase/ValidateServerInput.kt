package io.github.openrgbremote.core.domain.usecase

import io.github.openrgbremote.sdk.discovery.Ipv4Subnet

enum class HostError { EMPTY, CONTAINS_SPACES, INVALID }
enum class PortError { EMPTY, NOT_A_NUMBER, OUT_OF_RANGE }

/** Normalized, valid input. */
data class ServerInput(val name: String, val host: String, val port: Int)

sealed interface ServerInputValidation {
    data class Valid(val input: ServerInput) : ServerInputValidation
    data class Invalid(val hostError: HostError?, val portError: PortError?) : ServerInputValidation
}

/**
 * Checks host and port typed by the user.
 *
 * Accepted hosts: IPv4 literals, IPv6 literals (with or without brackets) and DNS names such as
 * `gaming-pc`, `gaming-pc.local` or `pc.fritz.box`. A blank name falls back to the host.
 */
class ValidateServerInput {

    operator fun invoke(name: String, host: String, port: String): ServerInputValidation {
        val trimmedHost = host.trim().removePrefix("[").removeSuffix("]")
        val hostError = when {
            trimmedHost.isEmpty() -> HostError.EMPTY
            trimmedHost.any { it.isWhitespace() } -> HostError.CONTAINS_SPACES
            !isValidHost(trimmedHost) -> HostError.INVALID
            else -> null
        }
        val portText = port.trim()
        val portValue = portText.toIntOrNull()
        val portError = when {
            portText.isEmpty() -> PortError.EMPTY
            portValue == null -> PortError.NOT_A_NUMBER
            portValue !in 1..65535 -> PortError.OUT_OF_RANGE
            else -> null
        }
        if (hostError != null || portError != null) return ServerInputValidation.Invalid(hostError, portError)
        val normalizedHost = trimmedHost.lowercase()
        return ServerInputValidation.Valid(
            ServerInput(name = name.trim().ifEmpty { normalizedHost }, host = normalizedHost, port = portValue!!),
        )
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
    }
}
