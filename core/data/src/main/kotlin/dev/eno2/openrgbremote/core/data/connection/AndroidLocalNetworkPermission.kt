package dev.eno2.openrgbremote.core.data.connection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.eno2.openrgbremote.core.domain.repository.LocalNetworkPermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ACCESS_LOCAL_NETWORK, enforced from Android 17 for apps targeting it. Earlier versions grant
 * local access with INTERNET. Android has no callback for permission changes, so the activity
 * calls [refresh] when it resumes (after the system dialog or the settings screen).
 */
@Singleton
class AndroidLocalNetworkPermission @Inject constructor(
    @ApplicationContext private val context: Context,
) : LocalNetworkPermission {

    private val state = MutableStateFlow(check())
    override val granted: StateFlow<Boolean> = state.asStateFlow()

    fun refresh() {
        state.value = check()
    }

    private fun check(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
}
