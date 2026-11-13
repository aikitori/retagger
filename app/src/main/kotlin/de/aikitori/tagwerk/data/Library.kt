package de.aikitori.tagwerk.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import de.aikitori.tagwerk.core.Artwork
import de.aikitori.tagwerk.core.AudioFileTags
import de.aikitori.tagwerk.core.NaturalOrder
import de.aikitori.tagwerk.core.TagIO
import de.aikitori.tagwerk.core.Tags
import de.aikitori.tagwerk.core.extension
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException

/** Eintrag in einem Ordner, den die Person per Storage Access Framework freigegeben hat. */
data class Entry(
    val documentId: String,
    val uri: Uri,
    val name: String,
    val isDirectory: Boolean,
)

/**
 * Zugriff auf den freigegebenen Musikordner. jaudiotagger braucht eine echte Datei, deshalb wird jede
 * Musikdatei zum Lesen und Schreiben in den Cache kopiert und nach dem Schreiben zurückgespielt.
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

    /** Unterordner und Musikdateien, Ordner zuerst, jeweils nach Namen sortiert. */
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
            // "wt" kürzt die Datei, sonst bliebe bei kleineren Tags Müll am Ende stehen
            val output = resolver.openOutputStream(entry.uri, "wt") ?: throw FileNotFoundException(entry.name)
            output.use { out -> file.inputStream().use { it.copyTo(out) } }
        }
    }

    /** Benennt die Datei um; die Endung bleibt. Gibt den neuen Eintrag zurück. */
    fun rename(entry: Entry, tree: Uri, baseName: String): Entry {
        val newName = "$baseName.${entry.name.extension()}"
        if (newName == entry.name) return entry
        val uri = DocumentsContract.renameDocument(resolver, entry.uri, newName) ?: entry.uri
        val id = DocumentsContract.getDocumentId(uri)
        return entry.copy(documentId = id, uri = DocumentsContract.buildDocumentUriUsingTree(tree, id), name = displayName(tree, id))
    }

    private fun <T> withCopy(entry: Entry, block: (File) -> T): T {
        // jaudiotagger erkennt das Format an der Endung
        val file = File.createTempFile("tag", "." + entry.name.extension().lowercase(), workDir)
        try {
            val input = resolver.openInputStream(entry.uri) ?: throw FileNotFoundException(entry.name)
            input.use { inp -> file.outputStream().use { inp.copyTo(it) } }
            return block(file)
        } finally {
            file.delete()
        }
    }

    /** Lädt ein Bild als Cover, große Bilder werden auf höchstens [maxSize] Pixel verkleinert. */
    fun loadCover(uri: Uri, maxSize: Int = 1000): Artwork {
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw FileNotFoundException(uri.toString())
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val mime = bounds.outMimeType ?: "image/jpeg"
        if (bounds.outWidth <= maxSize && bounds.outHeight <= maxSize && (mime == "image/jpeg" || mime == "image/png")) {
            return Artwork(bytes, mime)
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IllegalArgumentException("Kein Bild")
        val scale = minOf(1f, maxSize.toFloat() / maxOf(bitmap.width, bitmap.height))
        val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return Artwork(out.toByteArray(), "image/jpeg")
    }

    /** Reste abgebrochener Bearbeitungen entfernen. */
    fun cleanUp() {
        workDir.listFiles()?.forEach { it.delete() }
    }
}
