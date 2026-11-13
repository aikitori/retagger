package de.aikitori.retagger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import de.aikitori.retagger.R
import de.aikitori.retagger.convert.Mp3Quality
import de.aikitori.retagger.data.Entry

@Composable
private fun Mp3Quality.label() = stringResource(
    when (this) {
        Mp3Quality.VBR_V0 -> R.string.quality_v0
        Mp3Quality.VBR_V2 -> R.string.quality_v2
        Mp3Quality.CBR_320 -> R.string.quality_320
        Mp3Quality.CBR_192 -> R.string.quality_192
        Mp3Quality.CBR_128 -> R.string.quality_128
    },
)

/** Asks for quality and whether to delete the originals, then starts the conversion. */
@Composable
fun ConvertDialog(model: AppViewModel, files: List<Entry>, onDismiss: () -> Unit) {
    var quality by rememberSaveable { mutableStateOf(model.mp3Quality) }
    var delete by rememberSaveable { mutableStateOf(model.deleteOriginals) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (files.size == 1) stringResource(R.string.convert_to_mp3) else stringResource(R.string.convert_title, files.size))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (files.size == 1) Text(files.single().name, style = MaterialTheme.typography.bodySmall)
                for (q in Mp3Quality.entries) {
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = q == quality, onClick = { quality = q }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = q == quality, onClick = null)
                        Text(q.label(), Modifier.padding(start = 8.dp))
                    }
                }
                Row(
                    Modifier.fillMaxWidth().toggleable(value = delete, onValueChange = { delete = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = delete, onCheckedChange = null)
                    Text(stringResource(R.string.delete_originals), Modifier.padding(start = 8.dp))
                }
                if (delete) {
                    Text(stringResource(R.string.delete_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.convert_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                model.convert(files, quality, delete)
            }) { Text(stringResource(R.string.convert_start)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Progress while converting; keeps the screen on so the phone doesn't pause the work. */
@Composable
fun ConversionProgress(state: ConversionState, onCancel: () -> Unit) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.converting, state.index + 1, state.total)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                LinearProgressIndicator(progress = { state.fileProgress }, modifier = Modifier.fillMaxWidth())
                if (state.total > 1) LinearProgressIndicator(progress = { state.overall }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}
