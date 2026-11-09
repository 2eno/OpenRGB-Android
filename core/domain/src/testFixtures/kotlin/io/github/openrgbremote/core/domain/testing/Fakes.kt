package io.github.openrgbremote.core.domain.testing

import io.github.openrgbremote.core.domain.model.ColorTarget
import io.github.openrgbremote.core.domain.model.ConnectionStatus
import io.github.openrgbremote.core.domain.model.DeviceKey
import io.github.openrgbremote.core.domain.model.Profile
import io.github.openrgbremote.core.domain.repository.ProfileRepository
import io.github.openrgbremote.core.domain.repository.FavoriteColorRepository
import io.github.openrgbremote.core.domain.repository.LightingRepository
import io.github.openrgbremote.sdk.model.DeviceMode
import io.github.openrgbremote.core.domain.model.ConnectionTestResult
import io.github.openrgbremote.core.domain.model.ScanUpdate
import io.github.openrgbremote.core.domain.model.Server
import io.github.openrgbremote.core.domain.repository.ConnectionRepository
import io.github.openrgbremote.core.domain.repository.DiscoveryRepository
import io.github.openrgbremote.core.domain.repository.ServerRepository
import io.github.openrgbremote.sdk.model.OpenRgbDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update

class FakeServerRepository(initial: List<Server> = emptyList()) : ServerRepository {
    val servers = MutableStateFlow(initial)
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0) + 1

    override fun observeServers(): Flow<List<Server>> = servers
    override suspend fun getServer(id: Long) = servers.value.firstOrNull { it.id == id }
    override suspend fun findByAddress(host: String, port: Int) = servers.value.firstOrNull { it.host == host && it.port == port }
    override suspend fun save(server: Server): Long {
        val id = if (server.id == 0L) nextId++ else server.id
        servers.update { list -> list.filterNot { it.id == id } + server.copy(id = id) }
        return id
    }
    override suspend fun delete(id: Long) = servers.update { list -> list.filterNot { it.id == id } }
}

class FakeConnectionRepository : ConnectionRepository {
    override val statuses = MutableStateFlow<Map<Long, ConnectionStatus>>(emptyMap())
    override val devices = MutableStateFlow<Map<Long, List<OpenRgbDevice>>>(emptyMap())
    val calls = mutableListOf<String>()
    var testResult: ConnectionTestResult = ConnectionTestResult.Success(6, "OpenRGB", "pc", 2)

    override fun connect(serverId: Long) { calls += "connect $serverId" }
    override fun disconnect(serverId: Long) { calls += "disconnect $serverId" }
    override fun retry(serverId: Long) { calls += "retry $serverId" }
    override suspend fun refreshAll() { calls += "refreshAll" }
    override suspend fun rescanHardware(serverId: Long): Boolean { calls += "rescan $serverId"; return true }
    override suspend fun testConnection(host: String, port: Int) = testResult.also { calls += "test $host:$port" }
}

class FakeDiscoveryRepository(private val updates: List<ScanUpdate>) : DiscoveryRepository {
    override fun scan(port: Int): Flow<ScanUpdate> = flowOf(*updates.toTypedArray())
}

class FakeLightingRepository : LightingRepository {
    val calls = mutableListOf<String>()
    var reachable = 1

    override suspend fun setColor(targets: List<ColorTarget>, rgb: Int, preview: Boolean): Int {
        calls += "color %06X %s %s".format(rgb, if (preview) "preview" else "final", targets.joinToString(",") { t ->
            when (t) {
                is ColorTarget.Device -> t.key.name
                is ColorTarget.Zones -> t.key.name + t.zones.sorted()
                is ColorTarget.Led -> t.key.name + "#" + t.index
            }
        })
        return if (targets.isEmpty()) 0 else reachable
    }

    val frames = mutableMapOf<String, IntArray>()

    override suspend fun setFrame(key: DeviceKey, colors: IntArray): Boolean {
        frames[key.name] = colors
        calls += "frame ${key.name} ${colors.size}"
        return true
    }

    override suspend fun setMode(key: DeviceKey, modeIndex: Int, mode: DeviceMode): Boolean {
        calls += "mode ${key.name} $modeIndex ${mode.name} speed=${mode.speed} brightness=${mode.brightness} dir=${mode.direction} colors=${mode.colors.size}"
        return true
    }

    override suspend fun saveMode(key: DeviceKey, modeIndex: Int, mode: DeviceMode): Boolean {
        calls += "save ${key.name} $modeIndex"
        return true
    }
}

class FakeFavoriteColorRepository(initial: List<Int> = emptyList()) : FavoriteColorRepository {
    val colors = MutableStateFlow(initial)
    override fun observeFavorites(): Flow<List<Int>> = colors
    override suspend fun add(rgb: Int) = colors.update { listOf(rgb) + (it - rgb) }
    override suspend fun remove(rgb: Int) = colors.update { it - rgb }
}

class FakeProfileRepository(initial: List<Profile> = emptyList()) : ProfileRepository {
    val profiles = MutableStateFlow(initial)
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0) + 1

    override fun observeProfiles(): Flow<List<Profile>> = profiles
    override suspend fun getProfile(id: Long) = profiles.value.firstOrNull { it.id == id }
    override suspend fun save(profile: Profile): Long {
        val id = if (profile.id == 0L) nextId++ else profile.id
        profiles.update { list -> list.filterNot { it.id == id } + profile.copy(id = id) }
        return id
    }
    override suspend fun delete(id: Long) = profiles.update { list -> list.filterNot { it.id == id } }
}
