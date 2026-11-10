package dev.eno2.openrgbremote.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.eno2.openrgbremote.core.domain.repository.SettingsRepository
import dev.eno2.openrgbremote.feature.onboarding.OnboardingRoute
import dev.eno2.openrgbremote.ui.navigation.MainNavigation
import dev.eno2.openrgbremote.ui.navigation.OpenRequest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AppStage { LOADING, ONBOARDING, MAIN }

@HiltViewModel
class AppViewModel @Inject constructor(private val settings: SettingsRepository) : ViewModel() {
    val stage: StateFlow<AppStage> = settings.settings
        .map { if (it.onboardingCompleted) AppStage.MAIN else AppStage.ONBOARDING }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppStage.LOADING)

    fun completeOnboarding() {
        viewModelScope.launch { settings.setOnboardingCompleted(true) }
    }
}

@Composable
fun AppRoot(
    openRequest: OpenRequest? = null,
    onOpenRequestHandled: () -> Unit = {},
    viewModel: AppViewModel = hiltViewModel(),
) {
    val stage by viewModel.stage.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Crossfade(targetState = stage, label = "app-stage") { current ->
            when (current) {
                AppStage.LOADING -> Box(Modifier.fillMaxSize())
                AppStage.ONBOARDING -> OnboardingRoute(onFinished = viewModel::completeOnboarding)
                AppStage.MAIN -> MainNavigation(openRequest, onOpenRequestHandled)
            }
        }
    }
}
