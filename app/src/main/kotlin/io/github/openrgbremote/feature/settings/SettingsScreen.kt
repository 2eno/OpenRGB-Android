package io.github.openrgbremote.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.openrgbremote.BuildConfig
import io.github.openrgbremote.R
import io.github.openrgbremote.core.designsystem.component.SectionHeader
import io.github.openrgbremote.core.domain.repository.ServerRepository
import io.github.openrgbremote.core.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    servers: ServerRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    val serverCount: StateFlow<Int> = servers.observeServers().map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Showing the introduction again switches the app back to the onboarding stage. */
    fun showOnboardingAgain() {
        viewModelScope.launch { settings.setOnboardingCompleted(false) }
    }
}

@Composable
fun SettingsRoute(onOpenServers: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val count by viewModel.serverCount.collectAsStateWithLifecycle()
    SettingsScreen(count, onOpenServers, viewModel::showOnboardingAgain)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(serverCount: Int, onOpenServers: () -> Unit, onShowOnboarding: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_settings)) }) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                SectionHeader(stringResource(R.string.settings_section_connections))
                SettingsCard {
                    ListItem(
                        colors = transparentListItem(),
                        modifier = Modifier.clickable(onClick = onOpenServers),
                        leadingContent = { Icon(Icons.Outlined.Computer, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.settings_servers)) },
                        supportingContent = { Text(pluralStringResource(R.plurals.servers_saved, serverCount, serverCount)) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    )
                }

                SectionHeader(stringResource(R.string.settings_section_security))
                SecurityNotice()

                SectionHeader(stringResource(R.string.settings_section_about))
                SettingsCard {
                    ListItem(
                        colors = transparentListItem(),
                        leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.settings_version)) },
                        supportingContent = { Text(BuildConfig.VERSION_NAME) },
                    )
                    ListItem(
                        colors = transparentListItem(),
                        headlineContent = { Text(stringResource(R.string.settings_protocol)) },
                        supportingContent = { Text(stringResource(R.string.settings_protocol_value)) },
                    )
                    ListItem(
                        colors = transparentListItem(),
                        modifier = Modifier.clickable(onClick = onShowOnboarding),
                        leadingContent = { Icon(Icons.Outlined.Replay, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.settings_show_onboarding)) },
                    )
                }
                Text(
                    stringResource(R.string.settings_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
    }
}

/** Warning that the SDK server is unauthenticated; shown in settings and onboarding. */
@Composable
fun SecurityNotice(modifier: Modifier = Modifier, footer: @Composable () -> Unit = {}) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Shield, contentDescription = null)
                Text(stringResource(R.string.security_title), style = MaterialTheme.typography.titleMedium)
            }
            Text(stringResource(R.string.security_text), style = MaterialTheme.typography.bodyMedium)
            footer()
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) { Column { content() } }
}

@Composable
private fun transparentListItem() = ListItemDefaults.colors(containerColor = Color.Transparent)
