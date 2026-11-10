package dev.eno2.openrgbremote.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.eno2.openrgbremote.core.domain.model.EffectConfig
import dev.eno2.openrgbremote.core.domain.model.EffectState
import dev.eno2.openrgbremote.core.domain.repository.EffectController
import javax.inject.Inject

/** Starts effects together with the foreground service that keeps them running in the background. */
interface EffectLauncher {
    fun start(config: EffectConfig)
    fun stop()
}

class ServiceEffectLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val effects: EffectController,
) : EffectLauncher {

    override fun start(config: EffectConfig) {
        val wasRunning = effects.state.value is EffectState.Running
        val neededMicrophone = (effects.state.value as? EffectState.Running)?.config?.effect?.needsMicrophone == true
        effects.start(config)
        // The service is started once; it re-declares its type when the microphone need changes.
        if (!wasRunning || neededMicrophone != config.effect.needsMicrophone) {
            ContextCompat.startForegroundService(context, Intent(context, EffectService::class.java))
        }
    }

    override fun stop() = effects.stop()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class EffectLauncherModule {
    @Binds abstract fun launcher(impl: ServiceEffectLauncher): EffectLauncher

    @Binds abstract fun alarmCanceller(impl: dev.eno2.openrgbremote.quick.ScheduleAlarms): dev.eno2.openrgbremote.feature.quick.ScheduleAlarmCanceller
}
