package dev.eno2.openrgbremote.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.eno2.openrgbremote.MainActivity
import dev.eno2.openrgbremote.R
import dev.eno2.openrgbremote.core.domain.model.EffectState
import dev.eno2.openrgbremote.core.domain.repository.EffectController
import dev.eno2.openrgbremote.feature.effects.effectNameRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the process alive while an app-rendered effect is streamed, and shows the mandatory
 * notification with a stop action. The effect itself runs in [EffectController]; this service
 * only follows its state and ends itself when the effect stops.
 *
 * Type `connectedDevice` because the app drives external lighting hardware; `microphone` is added
 * only while the music effect runs with the permission granted.
 */
@AndroidEntryPoint
class EffectService : Service() {

    @Inject lateinit var effects: EffectController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            effects.stop()
        }
        // Always enter the foreground first: a service started with startForegroundService()
        // must do so even if it stops right away.
        val running = effects.state.value as? EffectState.Running
        enterForeground(running)
        if (running == null) {
            finish()
            return START_NOT_STICKY
        }
        if (observer == null) {
            observer = scope.launch {
                effects.state
                    .map { state -> (state as? EffectState.Running)?.let { Triple(it.config.effect, it.deviceCount, microphoneType(it)) } }
                    .distinctUntilChanged()
                    .collect { summary ->
                        val state = effects.state.value
                        if (summary == null || state !is EffectState.Running) finish() else enterForeground(state)
                    }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun microphoneType(state: EffectState.Running): Boolean =
        state.config.effect.needsMicrophone &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun enterForeground(state: EffectState.Running?) {
        ensureChannel(this)
        var type = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            // The microphone type exists from Android 11, which is also when background recording
            // started to require it; Android 10 records without it.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && state != null && microphoneType(state)) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(state), type)
    }

    private fun notification(state: EffectState.Running?): android.app.Notification {
        val name = state?.let { getString(effectNameRes(it.config.effect)) } ?: getString(R.string.nav_effects)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, EffectService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_effect)
            .setContentTitle(getString(R.string.notification_effect_title, name))
            .setContentText(state?.let { resources.getQuantityString(R.plurals.effect_device_count, it.deviceCount, it.deviceCount) })
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "effects"
        private const val NOTIFICATION_ID = 6742
        const val ACTION_STOP = "dev.eno2.openrgbremote.action.STOP_EFFECT"

        fun ensureChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_channel_effects), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.notification_channel_effects_text) }
            NotificationManagerCompat.from(context).createNotificationChannel(channel)
        }
    }
}
