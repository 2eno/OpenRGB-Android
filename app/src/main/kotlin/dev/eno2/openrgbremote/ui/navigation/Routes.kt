package dev.eno2.openrgbremote.ui.navigation

import kotlinx.serialization.Serializable

@Serializable data object DevicesRoute

/** [key] is an encoded [dev.eno2.openrgbremote.core.domain.model.DeviceKey]. */
@Serializable data class DeviceDetailRoute(val key: String)

@Serializable data object SettingsRoute

@Serializable data object ServersRoute

/** [serverId] 0 adds a new server; the other fields prefill the form (e.g. from the scan). */
@Serializable data class ServerEditRoute(
    val serverId: Long = 0,
    val host: String? = null,
    val port: Int? = null,
    val name: String? = null,
)

@Serializable data object ScanRoute

/** Color control. No [device]: all devices. [zone]/[led] are -1 when not restricted. */
@Serializable data class ColorRoute(val device: String? = null, val zone: Int = -1, val led: Int = -1)

@Serializable data object ProfilesRoute

/** [profileId] 0 creates a new profile. */
@Serializable data class ProfileEditRoute(val profileId: Long = 0)
