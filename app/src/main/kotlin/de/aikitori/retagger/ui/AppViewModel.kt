package de.aikitori.retagger.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.aikitori.retagger.R
import de.aikitori.retagger.core.Artwork
import de.aikitori.retagger.core.ArtworkChange
import de.aikitori.retagger.core.AudioInfo
import de.aikitori.retagger.core.BatchEdit
import de.aikitori.retagger.core.Field
import de.aikitori.retagger.core.FilenamePattern
import de.aikitori.retagger.core.Suggestions
import de.aikitori.retagger.core.Tags
import de.aikitori.retagger.core.baseName
import de.aikitori.retagger.convert.Mp3Quality
import de.aikitori.retagger.data.Entry
import de.aikitori.retagger.data.Library
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import java.util.concurrent.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Folder : Screen
    data object Editor : Screen
    data object Batch : Screen
}

data class Folder(val documentId: String, val name: String)

data class ConversionState(val total: Int, val index: Int = 0, val name: String = "", val fileProgress: Float = 0f) {
    val overall get() = (index + fileProgress) / total
}

/** One file in the editor. Values hold the text as typed; it is cleaned up only on save. */
data class EditorState(
    val entry: Entry,
    val original: Tags? = null,
    val info: AudioInfo? = null,
    val values: Map<Field, String> = emptyMap(),
    val artwork: Artwork? = null,
    val busy: Boolean = true,
) {
    val tags get() = Tags().with(values).copy(artwork = artwork)
    val dirty get() = original != null && tags != original
    val valid get() = values.all { (field, value) -> Tags.isValid(field, value.trim()) }
}

/** Several files. [edits] holds only the fields the user touched. */
data class BatchState(
    val entries: List<Entry>,
    val tags: List<Tags?> = emptyList(),
    val edits: Map<Field, String> = emptyMap(),
    val artwork: ArtworkChange = ArtworkChange.Keep,
    val numberTracks: Boolean = false,
    val progress: Pair<Int, Int>? = null,
) {
    val loaded get() = tags.size == entries.size
    val common: Map<Field, String?> get() = BatchEdit.commonValues(tags.filterNotNull())
    val commonArtwork: Artwork? get() = tags.filterNotNull().map { it.artwork }.distinct().singleOrNull()
    val variousArtwork get() = tags.filterNotNull().map { it.artwork }.distinct().size > 1
    val edit get() = BatchEdit(edits, artwork, numberTracks)
    val dirty get() = !edit.isEmpty
    val valid get() = edits.all { (field, value) -> Tags.isValid(field, value.trim()) }
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val library = Library(app)
    private val prefs = app.getSharedPreferences("retagger", Context.MODE_PRIVATE)

    var tree by mutableStateOf<Uri?>(null)
        private set
    var path by mutableStateOf<List<Folder>>(emptyList())
        private set
    var entries by mutableStateOf<List<Entry>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var selection by mutableStateOf<Set<String>>(emptySet())
        private set
    var screen by mutableStateOf<Screen>(Screen.Folder)
        private set
    var editor by mutableStateOf<EditorState?>(null)
        private set
    var batch by mutableStateOf<BatchState?>(null)
        private set

    /** Short message for the snackbar; the UI resets it once shown. */
    var message by mutableStateOf<String?>(null)

    var conversion by mutableStateOf<ConversionState?>(null)
        private set
    private var conversionJob: Job? = null

    var mp3Quality by mutableStateOf(
        runCatching { Mp3Quality.valueOf(prefs.getString("mp3_quality", null)!!) }.getOrDefault(Mp3Quality.VBR_V2),
    )
        private set
    var deleteOriginals by mutableStateOf(prefs.getBoolean("delete_originals", false))
        private set

    var parsePattern by mutableStateOf(prefs.getString("parse_pattern", null) ?: FilenamePattern.DEFAULT)
        private set
    var renamePattern by mutableStateOf(prefs.getString("rename_pattern", null) ?: FilenamePattern.DEFAULT)
        private set

    init {
        library.cleanUp()
        val saved = prefs.getString("tree", null)?.let(Uri::parse)
        val granted = app.contentResolver.persistedUriPermissions.any { it.uri == saved && it.isWritePermission }
        if (saved != null && granted) open(saved)
    }

    private fun say(@StringRes id: Int, vararg args: Any) {
        message = getApplication<Application>().getString(id, *args)
    }

    private fun Throwable.text() = localizedMessage ?: javaClass.simpleName

    // --- folder ---

    fun pickFolder(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        tree?.takeIf { it != uri }?.let { old ->
            runCatching { resolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs.edit { putString("tree", uri.toString()) }
        open(uri)
    }

    private fun open(uri: Uri) {
        tree = uri
        viewModelScope.launch {
            val root = runCatching {
                withContext(Dispatchers.IO) { library.rootId(uri).let { Folder(it, library.displayName(uri, it)) } }
            }.getOrElse {
                say(R.string.error_folder, it.text())
                return@launch
            }
            path = listOf(root)
            refresh()
        }
    }

    fun refresh() {
        val tree = tree ?: return
        val folder = path.lastOrNull() ?: return
        loading = true
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { library.list(tree, folder.documentId) } }
                .onSuccess { list ->
                    entries = list
                    selection = selection.filterTo(mutableSetOf()) { id -> list.any { it.documentId == id } }
                }
                .onFailure { say(R.string.error_folder, it.text()) }
            loading = false
        }
    }

    fun enter(entry: Entry) {
        path = path + Folder(entry.documentId, entry.name)
        selection = emptySet()
        entries = emptyList()
        refresh()
    }

    /** One folder up; false if already at the top. */
    fun up(): Boolean {
        if (path.size <= 1) return false
        path = path.dropLast(1)
        selection = emptySet()
        entries = emptyList()
        refresh()
        return true
    }

    fun toggle(entry: Entry) {
        selection = if (entry.documentId in selection) selection - entry.documentId else selection + entry.documentId
    }

    fun selectAll() {
        selection = entries.filterNot { it.isDirectory }.mapTo(mutableSetOf()) { it.documentId }
    }

    fun clearSelection() {
        selection = emptySet()
    }

    fun editSelection() {
        val files = entries.filter { it.documentId in selection }
        when (files.size) {
            0 -> return
            1 -> openEditor(files.single())
            else -> openBatch(files)
        }
    }

    fun backToFolder() {
        screen = Screen.Folder
        editor = null
        batch = null
        refresh()
    }

    // --- single file ---

    fun openEditor(entry: Entry) {
        editor = EditorState(entry)
        screen = Screen.Editor
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { library.read(entry) } }
                .onSuccess { read ->
                    editor = EditorState(entry, read.tags, read.info, read.tags.fields, read.tags.artwork, busy = false)
                }
                .onFailure {
                    say(R.string.error_read, entry.name, it.text())
                    backToFolder()
                }
        }
    }

    fun setField(field: Field, value: String) {
        editor = editor?.let { it.copy(values = it.values + (field to value)) }
    }

    fun setCover(uri: Uri) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { library.loadCover(uri) } }
                .onSuccess { art ->
                    editor = editor?.copy(artwork = art)
                    batch = batch?.copy(artwork = ArtworkChange.Replace(art))
                }
                .onFailure { message = it.text() }
        }
    }

    fun removeCover() {
        editor = editor?.copy(artwork = null)
        batch = batch?.copy(artwork = ArtworkChange.Remove)
    }

    fun save() {
        val state = editor?.takeIf { it.valid && !it.busy } ?: return
        editor = state.copy(busy = true)
        viewModelScope.launch {
            val tags = state.tags
            runCatching { withContext(Dispatchers.IO) { library.write(state.entry, tags) } }
                .onSuccess {
                    editor = editor?.copy(original = tags, values = tags.fields, busy = false)
                    say(R.string.saved)
                }
                .onFailure {
                    editor = editor?.copy(busy = false)
                    say(R.string.error_write, state.entry.name, it.text())
                }
        }
    }

    fun suggestions(entry: Entry): Map<Field, List<String>> =
        Suggestions.from(entry.name, path.map { it.name }, entries.filterNot { it.isDirectory }.map { it.name })

    fun batchSuggestions(files: List<Entry>): Map<Field, List<String>> =
        Suggestions.forBatch(files.map { it.name }, path.map { it.name })

    fun previewParse(pattern: String, entry: Entry) = FilenamePattern(pattern).parse(entry.name.baseName())

    fun previewRename(pattern: String, tags: Tags) = FilenamePattern(pattern).format(tags)

    fun applyParse(pattern: String) {
        parsePattern = pattern
        prefs.edit { putString("parse_pattern", pattern) }
        editor?.let { state ->
            val parsed = previewParse(pattern, state.entry) ?: return
            editor = state.copy(values = state.values + parsed)
        }
        batch?.let { parseBatch(it, FilenamePattern(pattern)) }
    }

    fun applyRename(pattern: String) {
        renamePattern = pattern
        prefs.edit { putString("rename_pattern", pattern) }
        editor?.let { renameSingle(it, FilenamePattern(pattern)) }
        batch?.let { renameBatch(it, FilenamePattern(pattern)) }
    }

    private fun renameSingle(state: EditorState, pattern: FilenamePattern) {
        val tree = tree ?: return
        val name = pattern.format(state.tags).ifEmpty { return }
        editor = state.copy(busy = true)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { library.rename(state.entry, tree, name) } }
                .onSuccess { renamed -> editor = editor?.copy(entry = renamed, busy = false) }
                .onFailure {
                    editor = editor?.copy(busy = false)
                    say(R.string.error_rename, state.entry.name, it.text())
                }
        }
    }

    // --- MP3 conversion ---

    /** Files of the selection (or [single]) that are not MP3 yet. */
    fun convertible(single: Entry? = null): List<Entry> =
        (single?.let(::listOf) ?: entries.filter { it.documentId in selection })
            .filter { !it.isDirectory && !it.name.endsWith(".mp3", ignoreCase = true) }

    fun convert(files: List<Entry>, quality: Mp3Quality, deleteOriginals: Boolean) {
        val tree = tree ?: return
        val folder = path.lastOrNull() ?: return
        if (files.isEmpty() || conversion != null) return
        mp3Quality = quality
        this.deleteOriginals = deleteOriginals
        prefs.edit {
            putString("mp3_quality", quality.name)
            putBoolean("delete_originals", deleteOriginals)
        }
        conversion = ConversionState(files.size)
        conversionJob = viewModelScope.launch {
            var done = 0
            val errors = mutableListOf<String>()
            for ((i, entry) in files.withIndex()) {
                conversion = ConversionState(files.size, i, entry.name)
                val job = coroutineContext[Job]
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        library.convertToMp3(
                            entry, tree, folder.documentId, quality, deleteOriginals,
                            progress = { p -> conversion = conversion?.copy(fileProgress = p) },
                            cancelled = { job?.isActive == false },
                        )
                    }
                }
                if (result.exceptionOrNull() is CancellationException) break
                result.onSuccess { done++ }.onFailure { errors += "${entry.name}: ${it.text()}" }
            }
            val cancelled = conversionJob?.isCancelled == true
            conversion = null
            conversionJob = null
            when {
                cancelled -> say(R.string.convert_cancelled, done)
                errors.isEmpty() -> say(R.string.convert_done, done)
                else -> say(R.string.convert_errors, done, errors.size, errors.first())
            }
            // the file list changed, and the edited file may be gone
            if (screen == Screen.Batch || (deleteOriginals && screen == Screen.Editor)) backToFolder() else refresh()
            selection = emptySet()
        }
    }

    fun cancelConversion() {
        conversionJob?.cancel()
    }

    // --- several files ---

    fun openBatch(files: List<Entry>) {
        batch = BatchState(files, progress = 0 to files.size)
        screen = Screen.Batch
        viewModelScope.launch {
            val tags = mutableListOf<Tags?>()
            val errors = mutableListOf<String>()
            for ((i, entry) in files.withIndex()) {
                batch = batch?.copy(progress = i + 1 to files.size)
                tags += runCatching { withContext(Dispatchers.IO) { library.read(entry).tags } }
                    .onFailure { errors += it.text() }
                    .getOrNull()
            }
            batch = batch?.copy(tags = tags, progress = null)
            when (errors.size) {
                0 -> Unit
                1 -> say(R.string.error_read, files[tags.indexOf(null)].name, errors.single())
                else -> say(R.string.batch_read_errors, errors.size, files.size, errors.first())
            }
        }
    }

    fun setBatchField(field: Field, value: String?) {
        batch = batch?.let { it.copy(edits = if (value == null) it.edits - field else it.edits + (field to value)) }
    }

    fun setNumberTracks(on: Boolean) {
        batch = batch?.copy(numberTracks = on)
    }

    fun keepCovers() {
        batch = batch?.copy(artwork = ArtworkChange.Keep)
    }

    fun applyBatch() {
        val state = batch?.takeIf { it.loaded && it.valid && it.progress == null } ?: return
        val edit = state.edit
        runOnFiles(state, done = { backToFolder() }) { i, entry, tags ->
            val updated = edit.apply(tags, i, state.entries.size)
            if (updated != tags) library.write(entry, updated)
            entry to updated
        }
    }

    private fun parseBatch(state: BatchState, pattern: FilenamePattern) {
        if (!state.loaded || state.progress != null) return
        runOnFiles(state) { _, entry, tags ->
            val parsed = pattern.parse(entry.name.baseName()) ?: return@runOnFiles entry to tags
            val updated = tags.with(parsed)
            if (updated != tags) library.write(entry, updated)
            entry to updated
        }
    }

    private fun renameBatch(state: BatchState, pattern: FilenamePattern) {
        val tree = tree ?: return
        if (!state.loaded || state.progress != null) return
        runOnFiles(state) { _, entry, tags ->
            val name = pattern.format(tags).ifEmpty { return@runOnFiles entry to tags }
            library.rename(entry, tree, name) to tags
        }
    }

    /** Processes the files one by one, shows progress and reports failures together. */
    private fun runOnFiles(
        state: BatchState,
        done: () -> Unit = {},
        step: (Int, Entry, Tags) -> Pair<Entry, Tags>,
    ) {
        viewModelScope.launch {
            val entries = state.entries.toMutableList()
            val tags = state.tags.toMutableList()
            var failed = 0
            for (i in entries.indices) {
                batch = batch?.copy(progress = i + 1 to entries.size)
                val current = tags[i]
                if (current == null) {
                    failed++
                    continue
                }
                runCatching { withContext(Dispatchers.IO) { step(i, entries[i], current) } }
                    .onSuccess { (entry, updated) ->
                        entries[i] = entry
                        tags[i] = updated
                    }
                    .onFailure { failed++ }
            }
            batch = batch?.copy(entries = entries, tags = tags, progress = null)
            val ok = entries.size - failed
            if (failed == 0) say(R.string.batch_done, ok) else say(R.string.batch_errors, ok, failed)
            done()
        }
    }
}
