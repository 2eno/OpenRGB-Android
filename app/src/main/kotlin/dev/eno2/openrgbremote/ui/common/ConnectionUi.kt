package dev.eno2.openrgbremote.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.eno2.openrgbremote.R
import dev.eno2.openrgbremote.core.designsystem.component.StatusPill
import dev.eno2.openrgbremote.core.designsystem.theme.StatusColors
import dev.eno2.openrgbremote.core.domain.model.ConnectionProblem
import dev.eno2.openrgbremote.core.domain.model.ConnectionStatus
import kotlinx.coroutines.delay

/** Seconds left until the next reconnect attempt, counting down live. */
@Composable
private fun rememberCountdown(status: ConnectionStatus.Reconnecting): Long {
    var remaining by remember(status) { mutableLongStateOf((status.retryInMillis + 999) / 1000) }
    LaunchedEffect(status) {
        while (remaining > 0) {
            delay(1_000)
            remaining--
        }
    }
    return remaining
}

/** Compact connection status label. Announced politely by TalkBack when it changes. */
@Composable
fun ConnectionStatusPill(status: ConnectionStatus, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    AnimatedContent(targetState = status, label = "status", modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite }) { s ->
        when (s) {
            is ConnectionStatus.Connected -> StatusPill(
                stringResource(R.string.status_connected),
                Icons.Outlined.CheckCircle,
                colors.surfaceContainerHigh,
                StatusColors.connected(),
            )
            is ConnectionStatus.Connecting -> StatusPill(stringResource(R.string.status_connecting), Icons.Outlined.Sync, colors.secondaryContainer, colors.onSecondaryContainer)
            is ConnectionStatus.Reconnecting -> {
                val seconds = rememberCountdown(s)
                val text = if (seconds > 0) stringResource(R.string.status_retry_in, seconds.toInt()) else stringResource(R.string.status_retrying)
                StatusPill(text, Icons.Outlined.WarningAmber, colors.tertiaryContainer, colors.onTertiaryContainer)
            }
            is ConnectionStatus.Failed -> StatusPill(stringResource(R.string.status_failed), Icons.Outlined.ErrorOutline, colors.errorContainer, colors.onErrorContainer)
            ConnectionStatus.Offline -> StatusPill(stringResource(R.string.status_offline), Icons.Outlined.CloudOff, colors.surfaceContainerHigh, colors.onSurfaceVariant)
        }
    }
}

/** Explains a connection problem and offers the fix action. */
@Composable
fun ProblemCard(
    problem: ConnectionProblem,
    detail: String?,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val text = problem.text
    val haptics = LocalHapticFeedback.current
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
                Text(stringResource(text.title), style = MaterialTheme.typography.titleMedium)
            }
            Text(stringResource(text.explanation), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(text.hint), style = MaterialTheme.typography.bodyMedium)
            if (!detail.isNullOrBlank() && problem == ConnectionProblem.UNKNOWN) {
                Text(stringResource(R.string.problem_details, detail), style = MaterialTheme.typography.bodySmall)
            }
            if (onRetry != null) {
                Button(
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onRetry()
                    },
                    modifier = Modifier.align(Alignment.End),
                ) { Text(stringResource(R.string.action_retry)) }
            }
        }
    }
}

/** Indeterminate spinner with label for loading rows. */
@Composable
fun LoadingRow(text: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
