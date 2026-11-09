package io.github.openrgbremote.core.data.di

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
import io.github.openrgbremote.core.data.connection.AndroidNetworkMonitor
import io.github.openrgbremote.core.data.connection.ApplicationScope
import io.github.openrgbremote.core.data.connection.ConnectionManager
import io.github.openrgbremote.core.data.connection.NetworkMonitor
import io.github.openrgbremote.core.data.db.AppDatabase
import io.github.openrgbremote.core.data.db.ServerDao
import io.github.openrgbremote.core.data.discovery.AndroidLocalNetworkProvider
import io.github.openrgbremote.core.data.discovery.LocalNetworkProvider
import io.github.openrgbremote.core.data.discovery.NetworkScanRepository
import io.github.openrgbremote.core.data.repository.RoomServerRepository
import io.github.openrgbremote.core.data.settings.DataStoreSettingsRepository
import io.github.openrgbremote.core.domain.repository.ConnectionRepository
import io.github.openrgbremote.core.domain.repository.DiscoveryRepository
import io.github.openrgbremote.core.domain.repository.ServerRepository
import io.github.openrgbremote.core.domain.repository.SettingsRepository
import io.github.openrgbremote.core.domain.usecase.ObserveDevice
import io.github.openrgbremote.core.domain.usecase.ObserveServerOverviews
import io.github.openrgbremote.core.domain.usecase.SaveServer
import io.github.openrgbremote.core.domain.usecase.ScanNetwork
import io.github.openrgbremote.core.domain.usecase.ValidateServerInput
import io.github.openrgbremote.sdk.client.OpenRgbClientConfig
import io.github.openrgbremote.sdk.discovery.OpenRgbProbe
import io.github.openrgbremote.sdk.discovery.SubnetScanner
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
    ): ConnectionManager = ConnectionManager(servers, networkMonitor, scope, config)

    @Provides
    fun subnetScanner(): SubnetScanner = SubnetScanner(OpenRgbProbe(clientName = "OpenRGB Remote (scan)"))

    @Provides fun validateServerInput() = ValidateServerInput()
    @Provides fun saveServer(servers: ServerRepository, validate: ValidateServerInput) = SaveServer(servers, validate)
    @Provides fun observeServerOverviews(servers: ServerRepository, connections: ConnectionRepository) =
        ObserveServerOverviews(servers, connections)
    @Provides fun observeDevice(overviews: ObserveServerOverviews) = ObserveDevice(overviews)
    @Provides fun scanNetwork(discovery: DiscoveryRepository, servers: ServerRepository) = ScanNetwork(discovery, servers)
}
