package io.github.openrgbremote.core.domain.repository

import io.github.openrgbremote.core.domain.model.AppSettings
import io.github.openrgbremote.sdk.model.DeviceMode
import io.github.openrgbremote.core.domain.model.DeviceKey
import io.github.openrgbremote.core.domain.model.ColorTarget
import io.github.openrgbremote.core.domain.model.ConnectionStatus
import io.github.openrgbremote.core.domain.model.ConnectionTestResult
import io.github.openrgbremote.core.domain.model.Profile
import io.github.openrgbremote.core.domain.model.ScanUpdate
import io.github.openrgbremote.core.domain.model.Server
import io.github.openrgbremote.sdk.model.OpenRgbDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Saved servers. */
interface ServerRepository {
    fun observeServers(): Flow<List<Server>>
    suspend fun getServer(id: Long): Server?
    suspend fun findByAddress(host: String, port: Int): Server?

    /** Inserts ([Server.id] == 0) or updates; returns the id. */
    suspend fun save(server: Server): Long
    suspend fun delete(id: Long)
}

/** Live connections to all saved servers. */
interface ConnectionRepository {
    val statuses: StateFlow<Map<Long, ConnectionStatus>>
    val devices: StateFlow<Map<Long, List<OpenRgbDevice>>>

    /** Connects now, even if auto-connect is off. */
    fun connect(serverId: Long)
    fun disconnect(serverId: Long)

    /** Skips the backoff delay / restarts after giving up. */
    fun retry(serverId: Long)

    /** Re-reads the device list of every connected server. */
    suspend fun refreshAll()

    /** Asks the server to re-detect its hardware (protocol 5+). Returns false if unsupported or offline. */
    suspend fun rescanHardware(serverId: Long): Boolean

    /** One-off handshake with an unsaved address, used by the "test connection" button. */
    suspend fun testConnection(host: String, port: Int): ConnectionTestResult
}

/** Finds OpenRGB servers in the local network. */
interface DiscoveryRepository {
    fun scan(port: Int): Flow<ScanUpdate>
}

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun setOnboardingCompleted(completed: Boolean)
    suspend fun setSecurityNoticeAccepted(accepted: Boolean)
}

/** Changes colors and modes on connected devices. All calls are no-ops for unreachable devices. */
interface LightingRepository {
    /**
     * Sets [rgb] (`0xRRGGBB`) on the targets and returns how many devices were reached.
     * Devices are switched to their per-LED ("Direct") mode first. With [preview] = true the call
     * is a cheap, droppable frame for live dragging; devices that can only take a color through a
     * mode change are skipped until the final call with [preview] = false.
     */
    suspend fun setColor(targets: List<ColorTarget>, rgb: Int, preview: Boolean): Int

    /** Sets one color per LED, e.g. to restore a snapshot. */
    suspend fun setFrame(key: DeviceKey, colors: IntArray): Boolean

    /** Activates a hardware mode with the given parameters. */
    suspend fun setMode(key: DeviceKey, modeIndex: Int, mode: DeviceMode): Boolean

    /** Stores the mode in the device's own memory (only modes with manual save, protocol 3+). */
    suspend fun saveMode(key: DeviceKey, modeIndex: Int, mode: DeviceMode): Boolean
}

/** Colors the user saved as favorites, newest first. */
interface FavoriteColorRepository {
    fun observeFavorites(): Flow<List<Int>>
    suspend fun add(rgb: Int)
    suspend fun remove(rgb: Int)
}

/** Profiles created by the user (built-in profiles live in code). */
interface ProfileRepository {
    fun observeProfiles(): Flow<List<Profile>>
    suspend fun getProfile(id: Long): Profile?

    /** Inserts ([Profile.id] == 0) or updates; returns the id. */
    suspend fun save(profile: Profile): Long
    suspend fun delete(id: Long)
}

/** Why an import was rejected. */
enum class ProfileImportError { NOT_JSON, WRONG_FORMAT, NEWER_VERSION, NO_PROFILES }

/** JSON exchange format for profiles. */
interface ProfileCodec {
    fun encode(profiles: List<Profile>): String

    /** Returns the valid profiles of [json] or the reason it could not be read. */
    fun decode(json: String): ProfileDecodeResult
}

sealed interface ProfileDecodeResult {
    data class Success(val profiles: List<Profile>) : ProfileDecodeResult
    data class Failure(val error: ProfileImportError) : ProfileDecodeResult
}
