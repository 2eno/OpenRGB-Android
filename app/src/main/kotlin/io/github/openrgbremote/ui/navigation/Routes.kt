package io.github.openrgbremote.ui.navigation

import kotlinx.serialization.Serializable

@Serializable data object DevicesRoute

/** [key] is an encoded [io.github.openrgbremote.core.domain.model.DeviceKey]. */
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
