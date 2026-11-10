package dev.eno2.openrgbremote.quick

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.eno2.openrgbremote.MainActivity
import dev.eno2.openrgbremote.R
import dev.eno2.openrgbremote.core.domain.model.EffectState
import dev.eno2.openrgbremote.core.domain.model.QuickAction
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Quick Settings tile for favorite [slot]. Profiles apply on tap; effect tiles show "active" while
 * their effect runs and stop it on the next tap. An empty slot opens the setup screen.
 */
abstract class QuickActionTile(private val slot: Int) : TileService() {

    private var job: Job? = null
    private val entry by lazy { QuickEntryPoint.get(this) }

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onStopListening() {
        job?.cancel()
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        entry.scope().launch {
            val action = entry.quickActions().observeSlots().first().getOrNull(slot)
            if (action == null) {
                openApp(MainActivity.openFavoritesIntent(this@QuickActionTile))
                return@launch
            }
            qsTile?.apply { state = Tile.STATE_ACTIVE; updateTile() }
            val result = entry.runner().run(action, toggle = true)
            if (result == QuickActionResult.NeedsForeground) openApp(MainActivity.runIntent(this@QuickActionTile, action))
            showQuickActionFeedback(applicationContext, result)
            refresh()
            QuickActionsWidget.refresh(applicationContext)
        }
    }

    private fun refresh() {
        job?.cancel()
        job = entry.scope().launch {
            val tile = qsTile ?: return@launch
            val action = entry.quickActions().observeSlots().first().getOrNull(slot)
            val label = action?.let { entry.labels().label(it) }
            val running = (entry.effects().state.value as? EffectState.Running)?.config?.effect
            val active = action is QuickAction.StartEffect && action.effect == running
            tile.label = label?.name ?: getString(R.string.quick_tile_label, slot + 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = when {
                    label == null -> getString(R.string.quick_tile_set_up)
                    active -> getString(R.string.quick_tile_running)
                    else -> getString(R.string.quick_tile_label, slot + 1)
                }
            }
            tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, slot, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

class QuickActionTile1 : QuickActionTile(0)
class QuickActionTile2 : QuickActionTile(1)
class QuickActionTile3 : QuickActionTile(2)
