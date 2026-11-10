package dev.eno2.openrgbremote

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import dev.eno2.openrgbremote.core.data.connection.ConnectionManager
import javax.inject.Inject

@HiltAndroidApp
class OpenRgbRemoteApp : Application() {

    @Inject lateinit var connectionManager: ConnectionManager

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
    }
}
