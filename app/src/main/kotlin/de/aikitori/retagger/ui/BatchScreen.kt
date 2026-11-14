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
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.aikitori.retagger.R
import de.aikitori.retagger.core.ArtworkChange
import de.aikitori.retagger.core.Field
import de.aikitori.retagger.core.extension

private enum class BatchDialog { NONE, DISCARD, PARSE, RENAME, CONVERT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchScreen(model: AppViewModel, snackbar: SnackbarHostState) {
    val state = model.batch ?: return
    val busy = state.progress != null
    var dialog by rememberSaveable { mutableStateOf(BatchDialog.NONE) }
    var menu by rememberSaveable { mutableStateOf(false) }
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(model::setCover)
    }
    val leave = {
        when {
            busy -> Unit
            state.dirty -> dialog = BatchDialog.DISCARD
            else -> model.backToFolder()
        }
    }
    BackHandler(onBack = leave)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.batch_title, state.entries.size)) },
                navigationIcon = {
                    IconButton(onClick = leave, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    IconButton(onClick = model::applyBatch, enabled = state.dirty && state.valid && !busy) {
                        Icon(Icons.Filled.Check, stringResource(R.string.apply))
                    }
                    IconButton(onClick = { menu = true }, enabled = !busy) {
                        Icon(Icons.Filled.MoreVert, stringResource(R.string.more))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.tags_from_filenames)) }, onClick = {
                            menu = false
                            dialog = BatchDialog.PARSE
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.rename_files)) }, onClick = {
                            menu = false
                            dialog = BatchDialog.RENAME
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.fix_extensions)) }, enabled = !state.dirty, onClick = {
                            menu = false
                            model.fixExtensions()
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.convert)) }, enabled = !state.dirty, onClick = {
                            menu = false
                            dialog = BatchDialog.CONVERT
                        })
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            state.progress?.let { (done, total) ->
                LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.progress, done, total),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (!state.loaded) return@Column
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.batch_hint), style = MaterialTheme.typography.bodyMedium)
                CoverRow(state, onPick = { pickCover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, model = model)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.numberTracks, onCheckedChange = model::setNumberTracks, enabled = !busy)
                    Text(stringResource(R.string.number_tracks, state.entries.size))
                }

                val common = state.common
                val suggestions = remember(state.entries) { model.batchSuggestions(state.entries) }
                val various = stringResource(R.string.various)
                FieldList { field, modifier ->
                    val edited = state.edits[field]
                    val numbered = state.numberTracks && (field == Field.TRACK || field == Field.TRACK_TOTAL)
                    TagField(
                        field = field,
                        value = edited ?: common[field].orEmpty(),
                        onChange = { model.setBatchField(field, it) },
                        modifier = modifier,
                        placeholder = if (common[field] == null) various else null,
                        various = common[field] == null && edited == null,
                        enabled = !busy && !numbered,
                        suggestions = suggestions[field].orEmpty(),
                        trailing = if (edited != null) {
                            { IconButton(onClick = { model.setBatchField(field, null) }) { Icon(Icons.AutoMirrored.Filled.Undo, null) } }
                        } else {
                            null
                        },
                    )
                }

                HorizontalDivider()
                val unreadable = stringResource(R.string.unreadable)
                state.entries.forEachIndexed { i, entry ->
                    val failed = state.tags.getOrNull(i) == null
                    Text(
                        if (failed) "${entry.name} $unreadable" else entry.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    val labels = fieldLabels()
    when (dialog) {
        BatchDialog.NONE -> Unit
        BatchDialog.CONVERT -> ConvertDialog(model, state.entries, onDismiss = { dialog = BatchDialog.NONE })
        BatchDialog.DISCARD -> DiscardDialog(onDismiss = { dialog = BatchDialog.NONE }, onDiscard = {
            dialog = BatchDialog.NONE
            model.backToFolder()
        })
        BatchDialog.PARSE -> {
            val total = state.entries.size
            val summary = @Composable { rows: List<Pair<String, String?>> ->
                stringResource(R.string.matches_count, rows.count { it.second != null }, total)
            }
            PatternDialog(
                title = stringResource(R.string.tags_from_filenames),
                initial = model.parsePattern,
                preview = { pattern ->
                    state.entries.map { it.name to model.previewParse(pattern, it)?.let { fields -> describe(fields, labels) } }
                },
                summary = summary,
                onDismiss = { dialog = BatchDialog.NONE },
                onApply = {
                    dialog = BatchDialog.NONE
                    model.applyParse(it)
                },
            )
        }
        BatchDialog.RENAME -> PatternDialog(
            title = stringResource(R.string.rename_files),
            initial = model.renamePattern,
            preview = { pattern ->
                state.entries.zip(state.tags).map { (entry, tags) ->
                    val name = tags?.let { model.previewRename(pattern, it) }?.takeIf { it.isNotEmpty() }
                    entry.name to name?.let { "$it.${entry.name.extension()}" }
                }
            },
            onDismiss = { dialog = BatchDialog.NONE },
            onApply = {
                dialog = BatchDialog.NONE
                model.applyRename(it)
            },
        )
    }
}

@Composable
private fun CoverRow(state: BatchState, onPick: () -> Unit, model: AppViewModel) {
    val shown = when (val change = state.artwork) {
        ArtworkChange.Keep -> state.commonArtwork
        ArtworkChange.Remove -> null
        is ArtworkChange.Replace -> change.artwork
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(shown)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (state.artwork == ArtworkChange.Keep && state.variousArtwork) {
                Text(stringResource(R.string.cover_various), style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onPick) { Text(stringResource(R.string.cover_change)) }
            if (state.artwork != ArtworkChange.Remove && (shown != null || state.variousArtwork)) {
                TextButton(onClick = model::removeCover) { Text(stringResource(R.string.cover_remove)) }
            }
            if (state.artwork != ArtworkChange.Keep) {
                TextButton(onClick = model::keepCovers) { Text(stringResource(R.string.cover_keep)) }
            }
        }
    }
}
