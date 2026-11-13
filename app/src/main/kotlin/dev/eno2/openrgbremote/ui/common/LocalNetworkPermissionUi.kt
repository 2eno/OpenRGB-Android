package dev.eno2.openrgbremote.ui.common

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.eno2.openrgbremote.R
import dev.eno2.openrgbremote.core.data.connection.AndroidLocalNetworkPermission

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LocalNetworkEntryPoint {
    fun localNetworkPermission(): AndroidLocalNetworkPermission
}

/** Re-reads the permission, e.g. after the system dialog or the settings screen. */
fun refreshLocalNetworkPermission(context: Context) {
    EntryPointAccessors.fromApplication(context.applicationContext, LocalNetworkEntryPoint::class.java).localNetworkPermission().refresh()
}

/**
 * Asks for ACCESS_LOCAL_NETWORK (Android 17+). Once the user denied it for good, Android answers
 * at once without showing anything; then the app's settings page is opened instead, where
 * "Nearby devices" can be allowed.
 */
@Composable
fun rememberLocalNetworkRequest(): () -> Unit {
    val context = LocalContext.current
    val requestedAt = remember { longArrayOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refreshLocalNetworkPermission(context)
        val answeredWithoutDialog = SystemClock.elapsedRealtime() - requestedAt[0] < NO_DIALOG_MILLIS
        if (!granted && answeredWithoutDialog) openAppSettings(context)
    }
    return {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            requestedAt[0] = SystemClock.elapsedRealtime()
            launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }
}

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** A dialog takes longer than this to be answered by a person. */
private const val NO_DIALOG_MILLIS = 400L

/** Explains why the app needs the permission, with the button that asks for it. */
@Composable
fun LocalNetworkPermissionCard(modifier: Modifier = Modifier) {
    val request = rememberLocalNetworkRequest()
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Router, contentDescription = null)
                Text(stringResource(R.string.lan_card_title), style = MaterialTheme.typography.titleMedium)
            }
            Text(stringResource(R.string.lan_card_text), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = request, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.lan_allow)) }
        }
    }
}
