package dev.eno2.openrgbremote.quick

import android.content.Context
import android.widget.Toast
import dev.eno2.openrgbremote.R
import dagger.hilt.android.EntryPointAccessors
import dev.eno2.openrgbremote.ui.common.LocalNetworkEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Widget, tiles and the activity fallback have no UI of their own to show a failure in, so the
 * two errors the user can act on are shown as a short text toast (allowed from the background).
 */
suspend fun showQuickActionFeedback(context: Context, result: QuickActionResult) {
    val lanDenied = !EntryPointAccessors.fromApplication(context.applicationContext, LocalNetworkEntryPoint::class.java)
        .localNetworkPermission().granted.value
    val message = when (result) {
        // Android 17+ without the permission: only the app can ask for it.
        QuickActionResult.NoDevices if lanDenied -> R.string.quick_result_lan_denied
        QuickActionResult.NoDevices -> R.string.quick_result_no_devices
        QuickActionResult.Missing -> R.string.quick_result_missing
        else -> return
    }
    withContext(Dispatchers.Main) { Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show() }
}
