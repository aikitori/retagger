package de.aikitori.retagger.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.aikitori.retagger.R
import de.aikitori.retagger.data.Entry

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FolderScreen(model: AppViewModel, snackbar: SnackbarHostState) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(model::pickFolder)
    }
    val selecting = model.selection.isNotEmpty()
    var converting by remember { mutableStateOf<List<Entry>?>(null) }
    converting?.let { files -> ConvertDialog(model, files, onDismiss = { converting = null }) }
    BackHandler(enabled = selecting || model.path.size > 1) {
        if (selecting) model.clearSelection() else model.up()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    val title = when {
                        selecting -> stringResource(R.string.selected_count, model.selection.size)
                        else -> model.path.lastOrNull()?.name ?: stringResource(R.string.app_name)
                    }
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    when {
                        selecting -> IconButton(onClick = model::clearSelection) {
                            Icon(Icons.Filled.Close, stringResource(R.string.clear_selection))
                        }
                        model.path.size > 1 -> IconButton(onClick = { model.up() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.up))
                        }
                    }
                },
                actions = {
                    if (selecting) {
                        val files = model.selectedFiles()
                        if (files.isNotEmpty()) {
                            IconButton(onClick = { converting = files }) {
                                Icon(Icons.Filled.SwapHoriz, stringResource(R.string.convert))
                            }
                        }
                        IconButton(onClick = model::selectAll) { Icon(Icons.Filled.SelectAll, stringResource(R.string.select_all)) }
                    } else if (model.tree != null) {
                        IconButton(onClick = { pick.launch(model.tree) }) {
                            Icon(Icons.Outlined.FolderOpen, stringResource(R.string.change_folder))
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (selecting) {
                ExtendedFloatingActionButton(
                    onClick = model::editSelection,
                    icon = { Icon(Icons.Filled.Edit, null) },
                    text = { Text(stringResource(R.string.edit_selected)) },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                model.tree == null -> Welcome { pick.launch(null) }
                else -> {
                    if (model.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (!model.loading && model.entries.isEmpty()) {
                        Text(
                            stringResource(R.string.empty_folder),
                            modifier = Modifier.align(Alignment.Center).padding(32.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                        items(model.entries, key = { it.documentId }) { entry ->
                            EntryRow(
                                entry = entry,
                                selecting = selecting,
                                selected = entry.documentId in model.selection,
                                onClick = {
                                    when {
                                        entry.isDirectory -> if (!selecting) model.enter(entry)
                                        selecting -> model.toggle(entry)
                                        else -> model.openEditor(entry)
                                    }
                                },
                                onLongClick = { if (!entry.isDirectory) model.toggle(entry) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(entry: Entry, selecting: Boolean, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = if (selected) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.colors()
        },
        leadingContent = {
            Icon(if (entry.isDirectory) Icons.Outlined.Folder else Icons.Outlined.AudioFile, null)
        },
        headlineContent = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingContent = if (selecting && !entry.isDirectory) {
            { Checkbox(checked = selected, onCheckedChange = { onClick() }) }
        } else {
            null
        },
    )
}

@Composable
private fun Welcome(onPick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(stringResource(R.string.welcome_text), textAlign = TextAlign.Center)
        Button(onClick = onPick) {
            Icon(Icons.Outlined.FolderOpen, null, Modifier.padding(end = 8.dp))
            Text(stringResource(R.string.choose_folder))
        }
    }
}
