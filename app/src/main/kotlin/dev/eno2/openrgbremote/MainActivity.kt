package dev.eno2.openrgbremote

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import dagger.hilt.android.AndroidEntryPoint
import dev.eno2.openrgbremote.core.designsystem.theme.OpenRgbTheme
import dev.eno2.openrgbremote.core.domain.model.QuickAction
import dev.eno2.openrgbremote.quick.QuickActionRunner
import dev.eno2.openrgbremote.quick.showQuickActionFeedback
import dev.eno2.openrgbremote.ui.AppRoot
import dev.eno2.openrgbremote.ui.navigation.OpenRequest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var runner: QuickActionRunner

    private var openRequest by mutableStateOf<OpenRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        setContent {
            OpenRgbTheme {
                AppRoot(openRequest = openRequest, onOpenRequestHandled = { openRequest = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Deep links from the widget and tiles: open the favorites screen or run an action in the foreground. */
    private fun handle(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_FAVORITES, false) == true) openRequest = OpenRequest.FAVORITES
        QuickAction.decode(intent?.getStringExtra(EXTRA_RUN))?.let { action ->
            // Started from a tile or widget where Android refused the background service start.
            // It is allowed once the activity is visible, which onCreate is not yet.
            lifecycleScope.launch {
                lifecycle.withResumed {}
                showQuickActionFeedback(this@MainActivity, runner.run(action))
            }
            intent?.removeExtra(EXTRA_RUN)
        }
    }

    companion object {
        private const val EXTRA_OPEN_FAVORITES = "open_favorites"
        private const val EXTRA_RUN = "run_action"

        fun openFavoritesIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_FAVORITES, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

        fun runIntent(context: Context, action: QuickAction): Intent =
            Intent(context, MainActivity::class.java).putExtra(EXTRA_RUN, action.encode()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
