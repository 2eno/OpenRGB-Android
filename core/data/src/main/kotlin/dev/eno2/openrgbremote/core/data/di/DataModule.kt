package dev.eno2.openrgbremote.core.data.di

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.eno2.openrgbremote.core.data.connection.AndroidNetworkMonitor
import dev.eno2.openrgbremote.core.data.connection.AndroidLocalNetworkPermission
import dev.eno2.openrgbremote.core.domain.repository.LocalNetworkPermission
import dev.eno2.openrgbremote.core.data.connection.ApplicationScope
import dev.eno2.openrgbremote.core.data.connection.ConnectionKeepAlive
import dev.eno2.openrgbremote.core.data.connection.ConnectionManager
import dev.eno2.openrgbremote.core.data.connection.NetworkMonitor
import dev.eno2.openrgbremote.core.data.db.AppDatabase
import dev.eno2.openrgbremote.core.data.db.FavoriteColorDao
import dev.eno2.openrgbremote.core.data.db.ProfileDao
import dev.eno2.openrgbremote.core.data.db.ScheduleDao
import dev.eno2.openrgbremote.core.data.repository.RoomScheduleRepository
import dev.eno2.openrgbremote.core.data.settings.DataStoreQuickActionRepository
import dev.eno2.openrgbremote.core.data.effects.DataStoreEffectParamsStore
import dev.eno2.openrgbremote.core.data.ssh.KeystoreSecretStore
import dev.eno2.openrgbremote.core.data.ssh.SecretStore
import dev.eno2.openrgbremote.core.data.ssh.SshCredentials
import dev.eno2.openrgbremote.core.data.ssh.SshJumpTransports
import dev.eno2.openrgbremote.core.domain.repository.SshCredentialRepository
import dev.eno2.openrgbremote.core.domain.repository.QuickActionRepository
import dev.eno2.openrgbremote.core.domain.repository.ScheduleRepository
import dev.eno2.openrgbremote.core.data.effects.AudioLevelSource
import dev.eno2.openrgbremote.core.data.effects.EffectEngine
import dev.eno2.openrgbremote.core.data.effects.MicrophoneAudioSource
import dev.eno2.openrgbremote.core.domain.repository.EffectController
import dev.eno2.openrgbremote.core.domain.repository.EffectLauncher
import kotlinx.coroutines.flow.map
import dev.eno2.openrgbremote.core.data.db.ServerDao
import dev.eno2.openrgbremote.core.data.profile.JsonProfileCodec
import dev.eno2.openrgbremote.core.data.profile.RoomProfileRepository
import dev.eno2.openrgbremote.core.domain.repository.ProfileCodec
import dev.eno2.openrgbremote.core.domain.repository.ProfileRepository
import dev.eno2.openrgbremote.core.domain.usecase.ApplyProfile
import dev.eno2.openrgbremote.core.domain.usecase.DuplicateProfile
import dev.eno2.openrgbremote.core.domain.usecase.DeleteProfile
import dev.eno2.openrgbremote.core.domain.usecase.GetProfile
import dev.eno2.openrgbremote.core.domain.usecase.ImportProfiles
import dev.eno2.openrgbremote.core.domain.usecase.ObserveProfiles
import dev.eno2.openrgbremote.core.domain.usecase.SaveProfile
import dev.eno2.openrgbremote.core.data.lighting.LightingController
import dev.eno2.openrgbremote.core.data.repository.RoomFavoriteColorRepository
import dev.eno2.openrgbremote.core.domain.repository.FavoriteColorRepository
import dev.eno2.openrgbremote.core.domain.repository.LightingRepository
import dev.eno2.openrgbremote.core.data.discovery.AndroidLocalNetworkProvider
import dev.eno2.openrgbremote.core.data.discovery.LocalNetworkProvider
import dev.eno2.openrgbremote.core.data.discovery.NetworkScanRepository
import dev.eno2.openrgbremote.core.data.repository.RoomServerRepository
import dev.eno2.openrgbremote.core.data.settings.DataStoreSettingsRepository
import dev.eno2.openrgbremote.core.domain.repository.ConnectionRepository
import dev.eno2.openrgbremote.core.domain.repository.DiscoveryRepository
import dev.eno2.openrgbremote.core.domain.repository.ServerRepository
import dev.eno2.openrgbremote.core.domain.repository.SettingsRepository
import dev.eno2.openrgbremote.core.domain.usecase.ObserveDevice
import dev.eno2.openrgbremote.core.domain.usecase.ObserveServerOverviews
import dev.eno2.openrgbremote.core.domain.usecase.SaveServer
import dev.eno2.openrgbremote.core.domain.usecase.ScanNetwork
import dev.eno2.openrgbremote.core.domain.usecase.ValidateServerInput
import dev.eno2.openrgbremote.sdk.client.OpenRgbClientConfig
import dev.eno2.openrgbremote.sdk.discovery.OpenRgbProbe
import dev.eno2.openrgbremote.sdk.discovery.SubnetScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindings {
    @Binds abstract fun serverRepository(impl: RoomServerRepository): ServerRepository
    @Binds abstract fun settingsRepository(impl: DataStoreSettingsRepository): SettingsRepository
    @Binds abstract fun discoveryRepository(impl: NetworkScanRepository): DiscoveryRepository
    @Binds abstract fun connectionRepository(impl: ConnectionManager): ConnectionRepository
    @Binds @Singleton abstract fun networkMonitor(impl: AndroidNetworkMonitor): NetworkMonitor
    @Binds abstract fun localNetworkPermission(impl: AndroidLocalNetworkPermission): LocalNetworkPermission
    @Binds abstract fun favoriteColorRepository(impl: RoomFavoriteColorRepository): FavoriteColorRepository
    @Binds abstract fun lightingRepository(impl: LightingController): LightingRepository
    @Binds abstract fun profileRepository(impl: RoomProfileRepository): ProfileRepository
    @Binds abstract fun profileCodec(impl: JsonProfileCodec): ProfileCodec
    @Binds abstract fun effectController(impl: EffectEngine): EffectController
    @Binds abstract fun audioLevelSource(impl: MicrophoneAudioSource): AudioLevelSource
    @Binds abstract fun quickActionRepository(impl: DataStoreQuickActionRepository): QuickActionRepository
    @Binds abstract fun scheduleRepository(impl: RoomScheduleRepository): ScheduleRepository
    @Binds abstract fun localNetworkProvider(impl: AndroidLocalNetworkProvider): LocalNetworkProvider
}

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME).build()

    @Provides
    fun serverDao(db: AppDatabase): ServerDao = db.serverDao()

    @Provides
    fun favoriteColorDao(db: AppDatabase): FavoriteColorDao = db.favoriteColorDao()

    @Provides
    fun profileDao(db: AppDatabase): ProfileDao = db.profileDao()

    @Provides
    fun scheduleDao(db: AppDatabase): ScheduleDao = db.scheduleDao()

    @Provides fun observeProfiles(profiles: ProfileRepository) = ObserveProfiles(profiles)
    @Provides fun getProfile(profiles: ProfileRepository) = GetProfile(profiles)
    @Provides fun saveProfile(profiles: ProfileRepository) = SaveProfile(profiles)
    @Provides fun deleteProfile(profiles: ProfileRepository, quickActions: QuickActionRepository, schedules: ScheduleRepository) =
        DeleteProfile(profiles, quickActions, schedules)
    @Provides fun duplicateProfile(profiles: ProfileRepository, save: SaveProfile) = DuplicateProfile(profiles, save)
    @Provides fun importProfiles(profiles: ProfileRepository, codec: ProfileCodec, save: SaveProfile) = ImportProfiles(profiles, codec, save)
    @Provides fun applyProfile(overviews: ObserveServerOverviews, lighting: LightingRepository, effects: EffectLauncher) =
        ApplyProfile(overviews, lighting, effects)

    @Provides
    @Singleton
    fun effectEngine(
        overviews: ObserveServerOverviews,
        lighting: LightingRepository,
        connections: ConnectionManager,
        settings: SettingsRepository,
        audio: AudioLevelSource,
        store: DataStoreEffectParamsStore,
        @ApplicationScope scope: CoroutineScope,
    ): EffectEngine = EffectEngine(
        devices = overviews().map { list -> list.flatMap { it.devices } },
        lighting = lighting,
        keepAlive = connections::acquireKeepAlive,
        targetFps = settings.settings.map { it.targetFps },
        audio = audio,
        scope = scope,
        store = store,
    )

    @Provides
    @Singleton
    fun lightingController(connections: ConnectionManager, @ApplicationScope scope: CoroutineScope): LightingController =
        LightingController(connections, scope)

    @Provides
    @Singleton
    fun dataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }

    @Provides
    fun clientConfig(): OpenRgbClientConfig = OpenRgbClientConfig(
        clientName = "OpenRGB Remote (Android)",
        clientHostname = Build.MODEL,
    )

    @Provides
    @Singleton
    fun connectionManager(
        servers: ServerRepository,
        networkMonitor: NetworkMonitor,
        @ApplicationScope scope: CoroutineScope,
        config: OpenRgbClientConfig,
        credentials: SshCredentials,
        localNetwork: LocalNetworkPermission,
    ): ConnectionManager = ConnectionManager(
        servers, networkMonitor, scope, config,
        startInForeground = false,
        jumpTransports = SshJumpTransports(credentials),
        localNetwork = localNetwork,
    )

    @Provides
    @Singleton
    fun secretStore(@ApplicationContext context: Context): SecretStore = KeystoreSecretStore(context)

    @Provides
    @Singleton
    fun sshCredentials(store: SecretStore): SshCredentials = SshCredentials(store)

    @Provides
    fun sshCredentialRepository(credentials: SshCredentials): SshCredentialRepository = credentials

    @Provides
    fun keepAlive(connections: ConnectionManager): ConnectionKeepAlive = connections

    @Provides
    fun subnetScanner(): SubnetScanner = SubnetScanner(OpenRgbProbe(clientName = "OpenRGB Remote (scan)"))

    @Provides fun validateServerInput() = ValidateServerInput()
    @Provides fun saveServer(servers: ServerRepository, validate: ValidateServerInput) = SaveServer(servers, validate)
    @Provides fun observeServerOverviews(servers: ServerRepository, connections: ConnectionRepository) =
        ObserveServerOverviews(servers, connections)
    @Provides fun observeDevice(overviews: ObserveServerOverviews) = ObserveDevice(overviews)
    @Provides fun scanNetwork(discovery: DiscoveryRepository, servers: ServerRepository) = ScanNetwork(discovery, servers)
}
