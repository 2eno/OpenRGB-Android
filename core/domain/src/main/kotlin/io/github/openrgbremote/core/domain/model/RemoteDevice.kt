package io.github.openrgbremote.core.domain.model

import io.github.openrgbremote.sdk.model.OpenRgbDevice
import java.util.Base64

/**
 * Identifies a device across connections.
 *
 * The protocol's device id is not stable: indices (protocol < 6) shift when devices are added and
 * unique ids (protocol 6) are reassigned when the server restarts. Name, location and serial are
 * what OpenRGB itself uses to match devices in its profiles.
 */
data class DeviceKey(
    val serverId: Long,
    val name: String,
    val location: String,
    val serial: String,
) {
    /** Compact, URL-safe representation for navigation arguments and storage. */
    fun encode(): String = listOf(serverId.toString(), name, location, serial)
        .joinToString(SEPARATOR) { ENCODER.encodeToString(it.toByteArray(Charsets.UTF_8)) }

    fun matches(device: OpenRgbDevice): Boolean =
        device.name == name && device.location == location && device.serial == serial

    companion object {
        // Each part is Base64url-encoded on its own, so no device name can collide with the separator.
        private const val SEPARATOR = "."
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()

        fun of(serverId: Long, device: OpenRgbDevice): DeviceKey =
            DeviceKey(serverId, device.name, device.location, device.serial)

        fun decode(encoded: String): DeviceKey? = runCatching {
            val parts = encoded.split(SEPARATOR).map { String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }
            if (parts.size != 4) return null
            DeviceKey(parts[0].toLong(), parts[1], parts[2], parts[3])
        }.getOrNull()
    }
}

/** A device together with the server it belongs to. */
data class RemoteDevice(val serverId: Long, val device: OpenRgbDevice) {
    val key: DeviceKey get() = DeviceKey.of(serverId, device)
}

/** One saved server with its live state, as shown on the devices screen. */
data class ServerOverview(
    val server: Server,
    val status: ConnectionStatus,
    val devices: List<RemoteDevice>,
)
