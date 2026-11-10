package dev.eno2.openrgbremote.quick

import android.content.Context
import android.widget.Toast
import dev.eno2.openrgbremote.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Widget, tiles and the activity fallback have no UI of their own to show a failure in, so the
 * two errors the user can act on are shown as a short text toast (allowed from the background).
 */
suspend fun showQuickActionFeedback(context: Context, result: QuickActionResult) {
    val message = when (result) {
        QuickActionResult.NoDevices -> R.string.quick_result_no_devices
        QuickActionResult.Missing -> R.string.quick_result_missing
        else -> return
    }
    withContext(Dispatchers.Main) { Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show() }
}
