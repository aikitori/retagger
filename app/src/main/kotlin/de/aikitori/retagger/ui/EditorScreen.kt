package de.aikitori.retagger.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.aikitori.retagger.R
import de.aikitori.retagger.core.AudioInfo
import de.aikitori.retagger.core.extension

private enum class Dialog { NONE, DISCARD, PARSE, RENAME }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(model: AppViewModel, snackbar: SnackbarHostState) {
    val state = model.editor ?: return
    var dialog by rememberSaveable { mutableStateOf(Dialog.NONE) }
    var menu by rememberSaveable { mutableStateOf(false) }
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(model::setCover)
    }
    val leave = { if (state.dirty) dialog = Dialog.DISCARD else model.backToFolder() }
    BackHandler(onBack = leave)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(state.entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    IconButton(onClick = model::save, enabled = state.dirty && state.valid && !state.busy) {
                        Icon(Icons.Filled.Check, stringResource(R.string.save))
                    }
                    IconButton(onClick = { menu = true }, enabled = !state.busy) {
                        Icon(Icons.Filled.MoreVert, stringResource(R.string.more))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.tags_from_filename)) }, onClick = {
                            menu = false
                            dialog = Dialog.PARSE
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.rename_file)) }, onClick = {
                            menu = false
                            dialog = Dialog.RENAME
                        })
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.original == null) return@Column
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(state.artwork)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        state.info?.let { Text(it.describe(), style = MaterialTheme.typography.bodySmall) }
                        OutlinedButton(onClick = { pickCover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                            Text(stringResource(R.string.cover_change))
                        }
                        if (state.artwork != null) {
                            TextButton(onClick = model::removeCover) { Text(stringResource(R.string.cover_remove)) }
                        }
                    }
                }
                FieldList { field, modifier ->
                    TagField(field, state.values[field].orEmpty(), { model.setField(field, it) }, modifier, enabled = !state.busy)
                }
            }
        }
    }

    when (dialog) {
        Dialog.NONE -> Unit
        Dialog.DISCARD -> DiscardDialog(onDismiss = { dialog = Dialog.NONE }, onDiscard = {
            dialog = Dialog.NONE
            model.backToFolder()
        })
        Dialog.PARSE -> {
            val labels = fieldLabels()
            PatternDialog(
                title = stringResource(R.string.tags_from_filename),
                initial = model.parsePattern,
                preview = { pattern -> listOf(state.entry.name to model.previewParse(pattern, state.entry)?.let { describe(it, labels) }) },
                onDismiss = { dialog = Dialog.NONE },
                onApply = {
                    dialog = Dialog.NONE
                    model.applyParse(it)
                },
            )
        }
        Dialog.RENAME -> PatternDialog(
            title = stringResource(R.string.rename_file),
            initial = model.renamePattern,
            preview = { pattern ->
                val name = model.previewRename(pattern, state.tags)
                listOf(state.entry.name to name.takeIf { it.isNotEmpty() }?.let { "$it.${state.entry.name.extension()}" })
            },
            onDismiss = { dialog = Dialog.NONE },
            onApply = {
                dialog = Dialog.NONE
                model.applyRename(it)
            },
        )
    }
}

private fun AudioInfo.describe(): String {
    val time = "%d:%02d".format(seconds / 60, seconds % 60)
    return listOf(format, time, bitrateKbps.takeIf { it.isNotEmpty() }?.let { "$it kbps" })
        .filterNotNull()
        .joinToString(" · ")
}
