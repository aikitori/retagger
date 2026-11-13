package de.aikitori.tagwerk.core

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.mp3.MP3File
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.id3.ID3v11Tag
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/** Liest und schreibt Tags über jaudiotagger. Die Datei braucht die passende Endung. */
object TagIO {
    /** Dateiendungen, die jaudiotagger bearbeiten kann. */
    val EXTENSIONS = setOf("mp3", "flac", "ogg", "oga", "m4a", "m4b", "mp4", "wma", "wav", "aif", "aiff", "aifc", "dsf")

    private val fieldKeys = mapOf(
        Field.TITLE to FieldKey.TITLE,
        Field.ARTIST to FieldKey.ARTIST,
        Field.ALBUM to FieldKey.ALBUM,
        Field.ALBUM_ARTIST to FieldKey.ALBUM_ARTIST,
        Field.YEAR to FieldKey.YEAR,
        Field.TRACK to FieldKey.TRACK,
        Field.TRACK_TOTAL to FieldKey.TRACK_TOTAL,
        Field.DISC to FieldKey.DISC_NO,
        Field.DISC_TOTAL to FieldKey.DISC_TOTAL,
        Field.GENRE to FieldKey.GENRE,
        Field.COMPOSER to FieldKey.COMPOSER,
        Field.COMMENT to FieldKey.COMMENT,
    )

    // jaudiotagger loggt jede Kleinigkeit auf INFO/WARNING
    private val quiet = Logger.getLogger("org.jaudiotagger").apply { level = Level.OFF }

    /** Auf Android einschalten, sonst greift jaudiotagger für Cover auf javax.imageio zu. */
    fun useAndroidMode() {
        TagOptionSingleton.getInstance().isAndroid = true
    }

    fun isSupported(name: String) = name.substringAfterLast('.', "").lowercase() in EXTENSIONS

    fun read(file: File): AudioFileTags {
        val audio = AudioFileIO.read(file)
        val tag = audio.tag
        val fields = buildMap<Field, String> {
            if (tag != null) {
                for ((field, key) in fieldKeys) {
                    val value = runCatching { tag.getFirst(key) }.getOrNull().orEmpty()
                    val clean = Tags.normalize(field, value)
                    if (clean.isNotEmpty()) put(field, clean)
                }
            }
        }
        val artwork = tag?.firstArtwork?.let { art ->
            art.binaryData?.takeIf { it.isNotEmpty() }?.let { Artwork(it, art.mimeType ?: guessMime(it)) }
        }
        val header = audio.audioHeader
        val info = AudioInfo(
            format = header?.format.orEmpty().ifEmpty { file.extension.uppercase() },
            seconds = header?.trackLength ?: 0,
            bitrateKbps = header?.bitRate.orEmpty(),
        )
        return AudioFileTags(Tags(fields, artwork), info)
    }

    fun write(file: File, tags: Tags) {
        val invalid = tags.invalidFields()
        require(invalid.isEmpty()) { "Ungültige Werte: $invalid" }
        val audio = AudioFileIO.read(file)
        val tag = audio.tagOrCreateAndSetDefault
        for ((field, key) in fieldKeys) {
            val value = tags[field]
            if (value.isEmpty()) {
                runCatching { tag.deleteField(key) }
            } else {
                tag.setField(key, value)
            }
        }
        tag.deleteArtworkField()
        tags.artwork?.let { art ->
            tag.setField(SizedArtwork(art.data, art.mimeType))
        }
        // ein vorhandenes ID3v1-Tag nachziehen, sonst zeigen alte Player die alten Werte
        if (audio is MP3File && audio.hasID3v1Tag() && audio.hasID3v2Tag()) {
            audio.iD3v1Tag = ID3v11Tag(audio.iD3v2Tag)
        }
        audio.commit()
    }

    fun guessMime(data: ByteArray): String = when {
        data.size > 3 && data[0] == 0x89.toByte() && data[1] == 'P'.code.toByte() -> "image/png"
        data.size > 3 && data[0] == 'G'.code.toByte() && data[1] == 'I'.code.toByte() -> "image/gif"
        else -> "image/jpeg"
    }
}
