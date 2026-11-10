package dev.eno2.openrgbremote.quick

import dev.eno2.openrgbremote.core.data.connection.ConnectionKeepAlive
import dev.eno2.openrgbremote.core.domain.model.BuiltInProfiles
import dev.eno2.openrgbremote.core.domain.model.ConnectionStatus
import dev.eno2.openrgbremote.core.domain.model.EffectState
import dev.eno2.openrgbremote.core.domain.model.QuickAction
import dev.eno2.openrgbremote.core.domain.repository.ConnectionRepository
import dev.eno2.openrgbremote.core.domain.repository.EffectController
import dev.eno2.openrgbremote.core.domain.usecase.ApplyProfile
import dev.eno2.openrgbremote.core.domain.usecase.GetProfile
import dev.eno2.openrgbremote.service.EffectLauncher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

sealed interface QuickActionResult {
    data class Applied(val devices: Int) : QuickActionResult
    data object EffectStarted : QuickActionResult
    data object EffectStopped : QuickActionResult

    /** No PC could be reached within the timeout. */
    data object NoDevices : QuickActionResult

    /** The profile behind the action was deleted. */
    data object Missing : QuickActionResult

    /** Android refused to start the effect service from the background; open the app instead. */
    data object NeedsForeground : QuickActionResult
}

/**
 * Runs favorite and schedule actions, also when the app is not open: it keeps the connections
 * alive for the duration, waits until the PCs connected (at most [CONNECT_TIMEOUT_MILLIS]) and then
 * applies the action. A short tail keeps the connection open so the last frame is delivered.
 */
@Singleton
class QuickActionRunner @Inject constructor(
    private val connections: ConnectionRepository,
    private val keepAlive: ConnectionKeepAlive,
    private val getProfile: GetProfile,
    private val applyProfile: ApplyProfile,
    private val effects: EffectController,
    private val launcher: EffectLauncher,
) {
    /** With [toggle] a favorite effect that is already running is stopped instead (tiles, widget). */
    suspend fun run(action: QuickAction, toggle: Boolean = false): QuickActionResult {
        val running = (effects.state.value as? EffectState.Running)?.config?.effect
        if (toggle && action is QuickAction.StartEffect && running == action.effect) {
            effects.stop()
            return QuickActionResult.EffectStopped
        }
        val handle = keepAlive.acquireKeepAlive()
        try {
            if (!awaitConnections()) return QuickActionResult.NoDevices
            return when (action) {
                is QuickAction.ApplyProfile -> {
                    val profile = getProfile(action.profileId) ?: return QuickActionResult.Missing
                    applied(applyProfile(profile))
                }
                QuickAction.Off -> applied(applyProfile(BuiltInProfiles.all.first { it.presetKey == "off" }))
                is QuickAction.StartEffect -> try {
                    launcher.start(effects.lastConfig(action.effect))
                    QuickActionResult.EffectStarted
                } catch (_: IllegalStateException) {
                    // ForegroundServiceStartNotAllowedException (Android 12+) is an IllegalStateException.
                    effects.stop()
                    QuickActionResult.NeedsForeground
                }
            }
        } finally {
            delay(FLUSH_MILLIS) // let the streamers deliver the frame before connections may close
            handle.close()
        }
    }

    private fun applied(devices: Int) = if (devices > 0) QuickActionResult.Applied(devices) else QuickActionResult.NoDevices

    /** True once no server is connecting any more and at least one is connected. */
    private suspend fun awaitConnections(): Boolean {
        val statuses = connections.statuses
        // The keep-alive takes a moment to start the connections.
        withTimeoutOrNull(START_TIMEOUT_MILLIS) { statuses.first { map -> map.values.any { it != ConnectionStatus.Offline } } }
            ?: return statuses.value.values.any { it is ConnectionStatus.Connected }
        withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) { statuses.first { map -> map.values.none { it is ConnectionStatus.Connecting } } }
        return statuses.value.values.any { it is ConnectionStatus.Connected }
    }

    companion object {
        const val START_TIMEOUT_MILLIS = 1_500L
        const val CONNECT_TIMEOUT_MILLIS = 7_000L
        const val FLUSH_MILLIS = 1_000L
    }
}
