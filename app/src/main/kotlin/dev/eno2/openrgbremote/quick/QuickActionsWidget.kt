package dev.eno2.openrgbremote.quick

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dev.eno2.openrgbremote.MainActivity
import dev.eno2.openrgbremote.R
import dev.eno2.openrgbremote.core.domain.model.EffectState
import dev.eno2.openrgbremote.core.domain.model.QuickAction
import kotlinx.coroutines.flow.first

private data class WidgetSlot(val index: Int, val label: QuickActionLabel?, val running: Boolean)

/**
 * Home screen widget with the four favorites as large buttons (2 x 2). Tapping runs the action;
 * a running favorite effect is stopped by tapping it again. Empty slots open the setup screen.
 */
class QuickActionsWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entry = QuickEntryPoint.get(context)
        val actions = entry.quickActions().observeSlots().first()
        val runningEffect = (entry.effects().state.value as? EffectState.Running)?.config?.effect
        val slots = actions.mapIndexed { index, action ->
            WidgetSlot(index, action?.let { entry.labels().label(it) }, action is QuickAction.StartEffect && action.effect == runningEffect)
        }
        val setUp = context.getString(R.string.widget_set_up)
        provideContent {
            GlanceTheme {
                Column(
                    GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(24.dp).padding(8.dp),
                ) {
                    for (row in 0 until 2) {
                        Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                            for (column in 0 until 2) {
                                SlotButton(context, slots[row * 2 + column], setUp, GlanceModifier.defaultWeight().fillMaxHeight().padding(4.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SlotButton(context: Context, slot: WidgetSlot, setUp: String, modifier: GlanceModifier) {
        val label = slot.label
        val action = if (label == null) {
            actionStartActivity(MainActivity.openFavoritesIntent(context))
        } else {
            actionRunCallback<RunFavoriteCallback>(actionParametersOf(RunFavoriteCallback.SLOT to slot.index))
        }
        val name = label?.name ?: setUp
        Column(
            modifier
                .background(if (slot.running) GlanceTheme.colors.primaryContainer else GlanceTheme.colors.secondaryContainer)
                .cornerRadius(16.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .clickable(action)
                .semantics { contentDescription = name },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(GlanceModifier.fillMaxWidth().height(10.dp).cornerRadius(5.dp)) {
                val colors = label?.colors ?: listOf(0x808080)
                colors.forEach { rgb -> Box(GlanceModifier.defaultWeight().fillMaxHeight().background(Color(rgb or (0xFF shl 24)))) {} }
            }
            Spacer(GlanceModifier.height(6.dp).width(1.dp))
            Text(
                name,
                maxLines = 2,
                style = TextStyle(
                    color = if (slot.running) GlanceTheme.colors.onPrimaryContainer else GlanceTheme.colors.onSecondaryContainer,
                    fontSize = 14.sp,
                    fontWeight = if (slot.running) FontWeight.Bold else FontWeight.Medium,
                ),
            )
        }
    }

    companion object {
        /** Redraws every placed widget, e.g. after favorites or the running effect changed. */
        suspend fun refresh(context: Context) = QuickActionsWidget().updateAll(context)
    }
}

class RunFavoriteCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val index = parameters[SLOT] ?: return
        val entry = QuickEntryPoint.get(context)
        val action = entry.quickActions().observeSlots().first().getOrNull(index) ?: return
        val result = entry.runner().run(action, toggle = true)
        if (result == QuickActionResult.NeedsForeground) {
            context.startActivity(MainActivity.runIntent(context, action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        showQuickActionFeedback(context, result)
        QuickActionsWidget.refresh(context)
    }

    companion object {
        val SLOT = ActionParameters.Key<Int>("slot")
    }
}

class QuickActionsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickActionsWidget()
}
