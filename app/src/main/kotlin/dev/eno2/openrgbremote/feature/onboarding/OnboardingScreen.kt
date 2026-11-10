package dev.eno2.openrgbremote.feature.onboarding

import android.content.ClipData
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.eno2.openrgbremote.R
import dev.eno2.openrgbremote.core.domain.model.DiscoveredServer
import dev.eno2.openrgbremote.core.domain.usecase.ScanState
import dev.eno2.openrgbremote.feature.servers.ScanContent
import dev.eno2.openrgbremote.feature.servers.ScanViewModel
import dev.eno2.openrgbremote.feature.servers.ServerEditViewModel
import dev.eno2.openrgbremote.feature.servers.ServerForm
import dev.eno2.openrgbremote.feature.servers.formActions
import dev.eno2.openrgbremote.feature.settings.SecurityNotice
import kotlinx.coroutines.launch

private const val STEP_COUNT = 3
const val HEADLESS_COMMAND = "openrgb --server --startminimized"

/**
 * Three steps to the first connection: (1) what the app does + security notice,
 * (2) how to start the SDK server on the PC, (3) scan or manual entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingRoute(onFinished: () -> Unit) {
    var step by rememberSaveable { mutableStateOf(0) }
    var accepted by rememberSaveable { mutableStateOf(false) }
    var showForm by rememberSaveable { mutableStateOf(false) }
    val scanViewModel: ScanViewModel = hiltViewModel()
    val formViewModel: ServerEditViewModel = hiltViewModel()
    val scanState by scanViewModel.state.collectAsStateWithLifecycle()
    val formState by formViewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(formState.savedId) { if (formState.savedId != null) onFinished() }

    OnboardingScreen(
        step = step,
        securityAccepted = accepted,
        scanState = scanState,
        actions = OnboardingActions(
            onAccept = { accepted = it },
            onNext = { step = (step + 1).coerceAtMost(STEP_COUNT - 1) },
            onBack = { step = (step - 1).coerceAtLeast(0) },
            onSkip = onFinished,
            onRescan = scanViewModel::scan,
            onSelectServer = { server ->
                formViewModel.prefill(server.host, server.port, server.suggestedName)
                showForm = true
            },
            onManual = { showForm = true },
        ),
    )

    if (showForm) {
        ModalBottomSheet(onDismissRequest = { showForm = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.edit_title_add), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp))
                ServerForm(formState, formViewModel.formActions())
            }
        }
    }
}

data class OnboardingActions(
    val onAccept: (Boolean) -> Unit = {},
    val onNext: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onSkip: () -> Unit = {},
    val onRescan: () -> Unit = {},
    val onSelectServer: (DiscoveredServer) -> Unit = {},
    val onManual: () -> Unit = {},
)

@Composable
fun OnboardingScreen(step: Int, securityAccepted: Boolean, scanState: ScanState, actions: OnboardingActions) {
    val snackbar = remember { SnackbarHostState() }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.onboarding_step, step + 1, STEP_COUNT),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = actions.onSkip) { Text(stringResource(R.string.action_skip)) }
                }
                LinearProgressIndicator(progress = { (step + 1f) / STEP_COUNT }, modifier = Modifier.fillMaxWidth().padding(end = 8.dp))
            }
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                if (step > 0) TextButton(onClick = actions.onBack) { Text(stringResource(R.string.action_back)) } else Spacer(Modifier)
                if (step < STEP_COUNT - 1) {
                    Button(onClick = actions.onNext, enabled = step != 0 || securityAccepted) { Text(stringResource(R.string.action_continue)) }
                }
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val direction = if (targetState > initialState) 1 else -1
                (slideInHorizontally { it / 4 * direction } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 * direction } + fadeOut())
            },
            label = "onboarding-step",
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) { current ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                    when (current) {
                        0 -> WelcomeStep(securityAccepted, actions.onAccept)
                        1 -> ServerStep(snackbar)
                        else -> ConnectStep(scanState, actions)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 12.dp).semantics { heading() })
}

@Composable
private fun WelcomeStep(accepted: Boolean, onAccept: (Boolean) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) { RgbRings() }
    StepTitle(stringResource(R.string.onboarding_welcome_title))
    Text(stringResource(R.string.onboarding_welcome_text), style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(24.dp))
    SecurityNotice {
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = accepted, role = Role.Checkbox, onValueChange = onAccept)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = accepted, onCheckedChange = null)
            Text(stringResource(R.string.onboarding_security_accept), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun ServerStep(snackbar: SnackbarHostState) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.command_copied)
    StepTitle(stringResource(R.string.onboarding_server_title))
    val steps = listOf(R.string.onboarding_server_step1, R.string.onboarding_server_step2, R.string.onboarding_server_step3, R.string.onboarding_server_step4)
    steps.forEachIndexed { index, text ->
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(32.dp)) {
                Text("${index + 1}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp), textAlign = TextAlign.Center)
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
                if (index == 2) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(HEADLESS_COMMAND, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f).padding(start = 12.dp))
                            IconButton(onClick = {
                                scope.launch {
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("OpenRGB", HEADLESS_COMMAND)))
                                    snackbar.showSnackbar(copied)
                                }
                            }) { Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.action_copy)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectStep(scanState: ScanState, actions: OnboardingActions) {
    StepTitle(stringResource(R.string.onboarding_connect_title))
    Text(stringResource(R.string.onboarding_connect_text), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 16.dp))
    ScanContent(scanState, actions.onSelectServer, actions.onManual, actions.onRescan)
}

/** Decorative logo: a soft RGB gradient circle. */
@Composable
private fun RgbRings(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(112.dp)
            .clip(CircleShape)
            .background(
                Brush.sweepGradient(
                    listOf(Color(0xFFFF4D6D), Color(0xFFFFC14D), Color(0xFF3DDC97), Color(0xFF4D8DFF), Color(0xFFB04DFF), Color(0xFFFF4D6D)),
                ),
            ),
    )
}
