package de.aikitori.retagger.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import de.aikitori.retagger.core.Artwork
import de.aikitori.retagger.core.AudioFileTags
import de.aikitori.retagger.core.NaturalOrder
import de.aikitori.retagger.core.TagIO
import de.aikitori.retagger.core.Tags
import de.aikitori.retagger.core.baseName
import de.aikitori.retagger.core.extension
import de.aikitori.retagger.convert.Mp3Converter
import de.aikitori.retagger.convert.Mp3Quality
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CancellationException

/** Entry in a folder the user granted via the Storage Access Framework. */
data class Entry(
    val documentId: String,
    val uri: Uri,
    val name: String,
    val isDirectory: Boolean,
)

/**
 * Access to the granted music folder. jaudiotagger needs a real file, so each music file is
 * copied to the cache for reading and writing, and copied back after writing.
 */
class Library(private val context: Context) {
    private val resolver get() = context.contentResolver
    private val workDir get() = File(context.cacheDir, "edit").apply { mkdirs() }

    init {
        TagIO.useAndroidMode()
    }

    fun rootId(tree: Uri): String = DocumentsContract.getTreeDocumentId(tree)

    fun displayName(tree: Uri, documentId: String): String {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return documentId.substringAfterLast(':').substringAfterLast('/')
    }

    /** Subfolders and music files, folders first, each sorted by name. */
    fun list(tree: Uri, documentId: String): List<Entry> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        val entries = mutableListOf<Entry>()
        resolver.query(children, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val name = cursor.getString(1) ?: continue
                val isDirectory = cursor.getString(2) == Document.MIME_TYPE_DIR
                if (name.startsWith('.')) continue
                if (!isDirectory && !TagIO.isSupported(name)) continue
                entries += Entry(id, DocumentsContract.buildDocumentUriUsingTree(tree, id), name, isDirectory)
            }
        } ?: throw FileNotFoundException(documentId)
        return entries.sortedWith(compareBy<Entry> { !it.isDirectory }.then(NaturalOrder.by { it.name }))
    }

    fun read(entry: Entry): AudioFileTags = withCopy(entry) { file -> TagIO.read(file) }

    fun write(entry: Entry, tags: Tags) {
        withCopy(entry) { file ->
            TagIO.write(file, tags)
            // "wt" truncates, otherwise smaller tags would leave junk at the end
            val output = resolver.openOutputStream(entry.uri, "wt") ?: throw FileNotFoundException(entry.name)
            output.use { out -> file.inputStream().use { it.copyTo(out) } }
        }
        rescan(entry.documentId)
    }

    /** Renames the file, keeping the extension. Returns the new entry. */
    fun rename(entry: Entry, tree: Uri, baseName: String): Entry {
        val newName = "$baseName.${entry.name.extension()}"
        if (newName == entry.name) return entry
        val uri = DocumentsContract.renameDocument(resolver, entry.uri, newName) ?: entry.uri
        val id = DocumentsContract.getDocumentId(uri)
        rescan(entry.documentId, id)
        return entry.copy(documentId = id, uri = DocumentsContract.buildDocumentUriUsingTree(tree, id), name = displayName(tree, id))
    }

    /**
     * Converts [entry] to MP3 in [folderId] with the same base name, tags and cover included.
     * The original is deleted afterwards if [deleteOriginal] is set. Returns the new file.
     */
    fun convertToMp3(
        entry: Entry,
        tree: Uri,
        folderId: String,
        quality: Mp3Quality,
        deleteOriginal: Boolean,
        progress: (Float) -> Unit,
        cancelled: () -> Boolean,
    ): Entry {
        val mp3 = File.createTempFile("convert", ".mp3", workDir)
        try {
            val tags = withCopy(entry) { input ->
                Mp3Converter.convert(input, mp3, quality, progress, cancelled)
                // a file without readable tags is still converted
                runCatching { TagIO.read(input).tags }.getOrDefault(Tags())
            }
            if (tags != Tags()) TagIO.write(mp3, tags)

            val folder = DocumentsContract.buildDocumentUriUsingTree(tree, folderId)
            // the provider appends " (1)" if the name is taken
            val uri = DocumentsContract.createDocument(resolver, folder, "audio/mpeg", entry.name.baseName() + ".mp3")
                ?: throw IOException("Could not create ${entry.name.baseName()}.mp3")
            try {
                val output = resolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException(uri.toString())
                output.use { out -> mp3.inputStream().use { it.copyTo(out) } }
            } catch (e: Exception) {
                runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                throw e
            }
            if (deleteOriginal) DocumentsContract.deleteDocument(resolver, entry.uri)
            val id = DocumentsContract.getDocumentId(uri)
            rescan(*listOfNotNull(id, entry.documentId.takeIf { deleteOriginal }).toTypedArray())
            return Entry(id, DocumentsContract.buildDocumentUriUsingTree(tree, id), displayName(tree, id), isDirectory = false)
        } finally {
            mp3.delete()
        }
    }

    /**
     * Asks Android's media library to read these files again. It may have indexed a new file while it was
     * still empty, and some players (e.g. Fossify Music) then refuse to play it. Only works for the
     * phone's own storage provider, whose document ids look like "primary:Music/a.mp3".
     */
    private fun rescan(vararg documentIds: String) {
        val paths = documentIds.mapNotNull { id ->
            val volume = id.substringBefore(':', "")
            val relative = id.substringAfter(':', "")
            when {
                volume.isEmpty() || relative.isEmpty() -> null
                volume == "primary" -> File(Environment.getExternalStorageDirectory(), relative).path
                else -> "/storage/$volume/$relative"
            }
        }
        if (paths.isNotEmpty()) runCatching { MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null) }
    }

    private fun <T> withCopy(entry: Entry, block: (File) -> T): T {
        // jaudiotagger detects the format by extension
        val file = File.createTempFile("tag", "." + entry.name.extension().lowercase(), workDir)
        try {
            val input = resolver.openInputStream(entry.uri) ?: throw FileNotFoundException(entry.name)
            input.use { inp -> file.outputStream().use { inp.copyTo(it) } }
            return block(file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // error messages from jaudiotagger name the temp file, show the real name instead
            throw IOException(e.message?.replace(file.path, entry.name)?.replace(file.name, entry.name), e)
        } finally {
            file.delete()
        }
    }

    /** Loads an image as cover; large images are scaled down to at most [maxSize] pixels. */
    fun loadCover(uri: Uri, maxSize: Int = 1000): Artwork {
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw FileNotFoundException(uri.toString())
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val mime = bounds.outMimeType ?: "image/jpeg"
        if (bounds.outWidth <= maxSize && bounds.outHeight <= maxSize && (mime == "image/jpeg" || mime == "image/png")) {
            return Artwork(bytes, mime)
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IllegalArgumentException("Not an image")
        val scale = minOf(1f, maxSize.toFloat() / maxOf(bitmap.width, bitmap.height))
        val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return Artwork(out.toByteArray(), "image/jpeg")
    }

    /** Removes leftovers from interrupted edits. */
    fun cleanUp() {
        workDir.listFiles()?.forEach { it.delete() }
    }
}
