package dev.eno2.openrgbremote.core.domain.testing

import dev.eno2.openrgbremote.core.domain.model.AppSettings
import dev.eno2.openrgbremote.core.domain.model.ColorTarget
import dev.eno2.openrgbremote.core.domain.model.EffectConfig
import dev.eno2.openrgbremote.core.domain.model.EffectState
import dev.eno2.openrgbremote.core.domain.repository.EffectController
import dev.eno2.openrgbremote.core.domain.repository.SettingsRepository
import dev.eno2.openrgbremote.core.effects.EffectId
import dev.eno2.openrgbremote.core.domain.model.ConnectionStatus
import dev.eno2.openrgbremote.core.domain.model.DeviceKey
import dev.eno2.openrgbremote.core.domain.model.Profile
import dev.eno2.openrgbremote.core.domain.repository.ProfileRepository
import dev.eno2.openrgbremote.core.domain.repository.FavoriteColorRepository
import dev.eno2.openrgbremote.core.domain.repository.LightingRepository
import dev.eno2.openrgbremote.sdk.model.DeviceMode
import dev.eno2.openrgbremote.core.domain.model.ConnectionTestResult
import dev.eno2.openrgbremote.core.domain.model.ScanUpdate
import dev.eno2.openrgbremote.core.domain.model.Server
import dev.eno2.openrgbremote.core.domain.model.QuickAction
import dev.eno2.openrgbremote.core.domain.model.Schedule
import dev.eno2.openrgbremote.core.domain.repository.QuickActionRepository
import dev.eno2.openrgbremote.core.domain.repository.ScheduleRepository
import dev.eno2.openrgbremote.core.domain.repository.ConnectionRepository
import dev.eno2.openrgbremote.core.domain.repository.DiscoveryRepository
import dev.eno2.openrgbremote.core.domain.repository.ServerRepository
import dev.eno2.openrgbremote.sdk.model.OpenRgbDevice
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

class FakeEffectController : EffectController {
    override val state = MutableStateFlow<EffectState>(EffectState.Idle)
    val calls = mutableListOf<String>()

    override fun start(config: EffectConfig) {
        calls += "start ${config.effect} speed=${config.params.speed} targets=${config.targets?.map { it.name }?.sorted()}"
        state.value = EffectState.Running(config)
    }

    override fun stop() {
        calls += "stop"
        state.value = EffectState.Idle
    }

    override fun lastConfig(effect: EffectId): EffectConfig =
        (state.value as? EffectState.Running)?.config?.takeIf { it.effect == effect } ?: EffectConfig(effect)
}

class FakeSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    override val settings = MutableStateFlow(initial)
    override suspend fun setOnboardingCompleted(completed: Boolean) = settings.update { it.copy(onboardingCompleted = completed) }
    override suspend fun setSecurityNoticeAccepted(accepted: Boolean) = settings.update { it.copy(securityNoticeAccepted = accepted) }
    override suspend fun setTargetFps(fps: Int) = settings.update { it.copy(targetFps = fps) }
}

class FakeQuickActionRepository(initial: List<QuickAction?> = List(QuickAction.SLOTS) { null }) : QuickActionRepository {
    val slots = MutableStateFlow(initial)
    override fun observeSlots(): Flow<List<QuickAction?>> = slots
    override suspend fun setSlot(index: Int, action: QuickAction?) = slots.update { list -> list.toMutableList().also { it[index] = action } }
}

class FakeScheduleRepository(initial: List<Schedule> = emptyList()) : ScheduleRepository {
    val schedules = MutableStateFlow(initial)
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1
    override fun observeSchedules(): Flow<List<Schedule>> = schedules
    override suspend fun getSchedule(id: Long) = schedules.value.firstOrNull { it.id == id }
    override suspend fun save(schedule: Schedule): Long {
        val id = if (schedule.id == 0L) nextId++ else schedule.id
        schedules.update { list -> list.filterNot { it.id == id } + schedule.copy(id = id) }
        return id
    }
    override suspend fun delete(id: Long) = schedules.update { list -> list.filterNot { it.id == id } }
}
