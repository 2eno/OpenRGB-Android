package dev.eno2.openrgbremote.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.eno2.openrgbremote.R
import dev.eno2.openrgbremote.core.designsystem.component.ColorPickerPanel
import dev.eno2.openrgbremote.core.domain.model.HsvColor

/** Dialog with wheel, sliders and hex field; [onRemove] adds a "remove" action for list entries. */
@Composable
fun ColorPickerDialog(
    title: String,
    initial: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    var color by remember { mutableStateOf(HsvColor.fromRgb(initial)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ColorPickerPanel(color, onColorChange = { color = it }, onChangeFinished = {})
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(color.toRgb()) }) { Text(stringResource(R.string.action_done)) } },
        dismissButton = {
            Row {
                if (onRemove != null) {
                    TextButton(onClick = onRemove) { Text(stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}
