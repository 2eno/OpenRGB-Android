package dev.eno2.openrgbremote.core.data.lighting

import dev.eno2.openrgbremote.core.data.connection.ConnectionManager
import dev.eno2.openrgbremote.core.domain.model.ColorTarget
import dev.eno2.openrgbremote.core.domain.model.DeviceKey
import dev.eno2.openrgbremote.core.domain.repository.LightingRepository
import dev.eno2.openrgbremote.sdk.client.LedFrameStreamer
import dev.eno2.openrgbremote.sdk.client.OpenRgbClient
import dev.eno2.openrgbremote.sdk.client.OpenRgbException
import dev.eno2.openrgbremote.sdk.model.ColorArray
import dev.eno2.openrgbremote.sdk.model.ColorMode
import dev.eno2.openrgbremote.sdk.model.DeviceMode
import dev.eno2.openrgbremote.sdk.model.OpenRgbDevice
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.ConcurrentHashMap

/**
 * Applies colors and modes through the connections of [ConnectionManager].
 *
 * LED colors always travel as complete device frames through a [LedFrameStreamer]: frames are
 * conflated per device, so dragging in the color picker can call [setColor] as often as it likes
 * without building up a backlog on a slow server. Zone and single-LED changes are merged into the
 * device's current colors, which keeps one code path and one ordering guarantee for everything.
 */
class LightingController(
    private val connections: ConnectionManager,
    private val scope: CoroutineScope,
) : LightingRepository {

    private class Session(val client: OpenRgbClient, val streamer: LedFrameStreamer)

    private val sessions = ConcurrentHashMap<Long, Session>()

    override suspend fun setColor(targets: List<ColorTarget>, rgb: Int, preview: Boolean): Int {
        var reached = 0
        for ((serverId, group) in targets.groupBy { it.key.serverId }) {
            val session = session(serverId) ?: continue
            for (target in group) {
                val device = session.client.devices.value.firstOrNull { target.key.matches(it) } ?: continue
                if (guarded { applyColor(session, device, target, rgb, preview) }) reached++
            }
        }
        return reached
    }

    override suspend fun setFrame(key: DeviceKey, colors: IntArray): Boolean {
        val session = session(key.serverId) ?: return false
        val device = session.client.devices.value.firstOrNull { key.matches(it) } ?: return false
        if (device.ledCount == 0 || device.directModeIndex() == null) return false
        return guarded {
            ensurePerLedMode(session.client, device)
            session.streamer.submit(device.id, colors.copyOf())
            true
        }
    }

    override suspend fun setMode(key: DeviceKey, modeIndex: Int, mode: DeviceMode): Boolean =
        withDevice(key) { client, device -> client.updateMode(device.id, modeIndex, mode) }

    override suspend fun saveMode(key: DeviceKey, modeIndex: Int, mode: DeviceMode): Boolean =
        withDevice(key) { client, device -> client.saveMode(device.id, modeIndex, mode) }

    private suspend fun applyColor(session: Session, device: OpenRgbDevice, target: ColorTarget, rgb: Int, preview: Boolean): Boolean {
        if (device.directModeIndex() == null) {
            // No per-LED mode at all (e.g. some RAM or GPU controllers): the only way to set a color
            // is the color list of a mode. That is a mode change, which may write to the device's
            // flash, so it is never done for preview frames.
            if (preview || target is ColorTarget.Led) return false
            val mode = device.activeMode?.takeIf { it.hasModeSpecificColor } ?: return false
            val count = maxOf(mode.colors.size, mode.colorsMin.toInt(), 1)
            val updated = mode.copy(colorMode = ColorMode.MODE_SPECIFIC, colors = ColorArray.of(IntArray(count) { rgb }))
            return session.client.updateMode(device.id, device.activeModeIndex, updated)
        }
        if (device.ledCount == 0) return false
        ensurePerLedMode(session.client, device)
        val frame = device.colors.toIntArray()
        when (target) {
            is ColorTarget.Device -> frame.fill(rgb)
            is ColorTarget.Zones -> for (index in target.zones) {
                val zone = device.zones.getOrNull(index) ?: continue
                val from = zone.startIndex.coerceIn(0, frame.size)
                val to = (zone.startIndex + zone.ledsCount).coerceIn(from, frame.size)
                frame.fill(rgb, from, to)
            }
            is ColorTarget.Led -> if (target.index in frame.indices) frame[target.index] = rgb else return false
        }
        session.streamer.submit(device.id, frame)
        return true
    }

    /** Hardware effects ignore LED updates, so switch to the per-LED mode before the first frame. */
    private suspend fun ensurePerLedMode(client: OpenRgbClient, device: OpenRgbDevice) {
        val current = client.device(device.id) ?: device
        if (current.activeMode?.hasPerLedColor == true) return
        val index = current.directModeIndex() ?: return
        client.updateMode(current.id, index, current.modes[index].copy(colorMode = ColorMode.PER_LED))
    }

    private suspend fun withDevice(key: DeviceKey, block: suspend (OpenRgbClient, OpenRgbDevice) -> Boolean): Boolean {
        val session = session(key.serverId) ?: return false
        val device = session.client.devices.value.firstOrNull { key.matches(it) } ?: return false
        return guarded { block(session.client, device) }
    }

    /** A dropped connection is reported through the connection status, not as a crash here. */
    private suspend fun guarded(block: suspend () -> Boolean): Boolean = try {
        block()
    } catch (_: OpenRgbException) {
        false
    }

    /** One streamer per live client; a reconnect creates a new client and therefore a new session. */
    private fun session(serverId: Long): Session? {
        val client = connections.client(serverId)
        val existing = sessions[serverId]
        if (client == null) {
            sessions.remove(serverId)?.streamer?.stop()
            return null
        }
        if (existing?.client === client) return existing
        existing?.streamer?.stop()
        return Session(client, LedFrameStreamer(client, scope)).also { sessions[serverId] = it }
    }
}
