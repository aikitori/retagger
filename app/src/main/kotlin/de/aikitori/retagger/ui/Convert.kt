package de.aikitori.retagger.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import de.aikitori.retagger.R
import de.aikitori.retagger.convert.ConversionState
import de.aikitori.retagger.convert.AudioQuality
import de.aikitori.retagger.convert.Target
import de.aikitori.retagger.core.ContentType
import de.aikitori.retagger.core.extension
import de.aikitori.retagger.data.Entry

@Composable
private fun AudioQuality.label() = when {
    this == AudioQuality.MP3_V0 -> stringResource(R.string.quality_v0)
    this == AudioQuality.MP3_V2 -> stringResource(R.string.quality_v2)
    target == Target.MP3 -> "CBR $value kbps"
    else -> "$value kbps"
}

/** True if [name] already has the target format. Ogg Vorbis still counts as convertible to Opus. */
private fun Target.has(name: String) = name.extension().lowercase().let { ext ->
    if (this == Target.OPUS) ext == "opus" else ContentType.matches(extension, ext)
}

@Composable
private fun Target.label() = when (this) {
    Target.MP3 -> "MP3"
    Target.M4A -> "M4A (AAC)"
    Target.OPUS -> "Opus"
}

/** Asks for format, quality and whether to delete the originals, then starts the conversion. */
@Composable
fun ConvertDialog(model: AppViewModel, files: List<Entry>, onDismiss: () -> Unit) {
    var quality by rememberSaveable { mutableStateOf(model.convertQuality) }
    var delete by rememberSaveable { mutableStateOf(model.deleteOriginals) }
    // files that already have the chosen format are skipped
    val todo = files.filterNot { quality.target.has(it.name) }
    val context = LocalContext.current
    val start = {
        onDismiss()
        model.convert(todo, quality, delete)
    }
    // for the progress notification; the conversion runs either way. The dialog must stay open until
    // the answer arrives, otherwise this callback is gone and nothing starts.
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { start() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (files.size == 1) stringResource(R.string.convert) else stringResource(R.string.convert_title, files.size))
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (files.size == 1) Text(files.single().name, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (target in Target.entries.filter { it.available }) {
                        FilterChip(
                            selected = quality.target == target,
                            onClick = { if (quality.target != target) quality = model.qualityFor(target) },
                            label = { Text(target.label()) },
                        )
                    }
                }
                for (q in AudioQuality.entries.filter { it.target == quality.target }) {
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = q == quality, onClick = { quality = q }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = q == quality, onClick = null)
                        Text(q.label(), Modifier.padding(start = 8.dp))
                    }
                }
                if (todo.size < files.size) {
                    Text(
                        stringResource(R.string.convert_skipped, files.size - todo.size, quality.target.label()),
                        style = MaterialTheme.typography.bodySmall,
                    )
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
            TextButton(enabled = todo.isNotEmpty(), onClick = {
                val ask = Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                if (ask) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else start()
            }) { Text(stringResource(R.string.convert_start)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Progress while converting. The work runs in a foreground service, the app may go to the background. */
@Composable
fun ConversionProgress(state: ConversionState, onCancel: () -> Unit) {
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
