package dev.eno2.openrgbremote

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import dev.eno2.openrgbremote.core.data.connection.ConnectionManager
import dev.eno2.openrgbremote.core.data.connection.ApplicationScope
import dev.eno2.openrgbremote.core.domain.model.EffectState
import dev.eno2.openrgbremote.core.domain.repository.EffectController
import dev.eno2.openrgbremote.core.domain.repository.ProfileRepository
import dev.eno2.openrgbremote.core.domain.repository.QuickActionRepository
import dev.eno2.openrgbremote.core.domain.repository.ScheduleRepository
import dev.eno2.openrgbremote.quick.QuickActionsWidget
import dev.eno2.openrgbremote.quick.ScheduleAlarms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class OpenRgbRemoteApp : Application() {

    @Inject lateinit var connectionManager: ConnectionManager
    @Inject lateinit var effects: EffectController
    @Inject lateinit var quickActions: QuickActionRepository
    @Inject lateinit var profiles: ProfileRepository
    @Inject lateinit var schedules: ScheduleRepository
    @Inject lateinit var scheduleAlarms: ScheduleAlarms
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // Connections follow the app's visibility so an idle app does not keep sockets and
        // radios busy; a running effect will hold a keep-alive instead.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = connectionManager.setAppInForeground(true)
                override fun onStop(owner: LifecycleOwner) = connectionManager.setAppInForeground(false)
            },
        )
        // The widget shows favorite names and which effect runs: redraw it when either changes.
        scope.launch {
            combine(
                quickActions.observeSlots(),
                profiles.observeProfiles(),
                effects.state.map { (it as? EffectState.Running)?.config?.effect }.distinctUntilChanged(),
            ) { _, _, _ -> }.collect { QuickActionsWidget.refresh(this@OpenRgbRemoteApp) }
        }
        // Keeps one alarm per enabled schedule in sync with the database.
        scope.launch { schedules.observeSchedules().collect { scheduleAlarms.sync(it) } }
    }
}
