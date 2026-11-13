package de.aikitori.retagger.core

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.mp3.MP3File
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.mp4.Mp4Tag
import org.jaudiotagger.tag.mp4.Mp4TagCreator
import org.jaudiotagger.tag.id3.ID3v11Tag
import org.jaudiotagger.tag.id3.ID3v22Tag
import org.jaudiotagger.tag.id3.ID3v23Tag
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/** Reads and writes tags via jaudiotagger. The file needs the right extension. */
object TagIO {
    /** File extensions jaudiotagger can handle. */
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

    // jaudiotagger logs every little thing at INFO/WARNING
    private val quiet = Logger.getLogger("org.jaudiotagger").apply { level = Level.OFF }

    /** Enable on Android, otherwise jaudiotagger uses javax.imageio for artwork. */
    fun useAndroidMode() {
        TagOptionSingleton.getInstance().isAndroid = true
    }

    fun isSupported(name: String) = name.substringAfterLast('.', "").lowercase() in EXTENSIONS

    fun read(file: File): AudioFileTags {
        val audio = try {
            AudioFileIO.read(file)
        } catch (e: Exception) {
            if (!isMp3(file)) throw e
            null
        }
        val tag = if (audio != null) audio.tag else RawId3.read(file).tag
        val fields = buildMap<Field, String> {
            if (tag != null) {
                for ((field, key) in fieldKeys) {
                    val value = runCatching { tag.getFirst(key) }.getOrNull().orEmpty()
                    val clean = Tags.normalize(field, value)
                    // ID3 reports a missing total as 0
                    if (clean.isNotEmpty() && !(field.numeric && clean == "0")) put(field, clean)
                }
            }
        }
        val artwork = tag?.firstArtwork?.let { art ->
            art.binaryData?.takeIf { it.isNotEmpty() }?.let { Artwork(it, art.mimeType ?: guessMime(it)) }
        }
        val header = audio?.audioHeader
        val info = AudioInfo(
            format = header?.format.orEmpty().ifEmpty { file.extension.uppercase() },
            seconds = header?.trackLength ?: 0,
            bitrateKbps = header?.bitRate.orEmpty(),
        )
        return AudioFileTags(Tags(fields, artwork), info)
    }

    fun write(file: File, tags: Tags) {
        val invalid = tags.invalidFields()
        require(invalid.isEmpty()) { "Invalid values: $invalid" }
        val audio = try {
            AudioFileIO.read(file)
        } catch (e: Exception) {
            if (!isMp3(file)) throw e
            null
        }
        if (audio == null) {
            // jaudiotagger found no valid MPEG frame, but the ID3 tag can still be rewritten in place
            val raw = RawId3.read(file)
            val tag = when (val old = raw.tag) {
                null -> ID3v23Tag()
                is ID3v22Tag -> ID3v23Tag(old)
                else -> old
            }
            fill(tag, tags)
            tag.write(file, raw.audioStart)
            return
        }
        val tag = audio.tagOrCreateAndSetDefault
        fill(tag, tags)
        if (tag is Mp4Tag) {
            // jaudiotagger's MP4 writer fails on many real files ("incorrect offsets written")
            val ilst = Mp4TagCreator().convertMetadata(tag)
            Mp4Writer.write(file, ByteArray(ilst.remaining()).also { ilst.get(it) })
            return
        }
        // update an existing ID3v1 tag too, otherwise old players show the old values
        if (audio is MP3File && audio.hasID3v1Tag() && audio.hasID3v2Tag()) {
            audio.iD3v1Tag = ID3v11Tag(audio.iD3v2Tag)
        }
        audio.commit()
    }

    private fun fill(tag: Tag, tags: Tags) {
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
    }

    private fun isMp3(file: File) = file.extension.equals("mp3", ignoreCase = true)

    fun guessMime(data: ByteArray): String = when {
        data.size > 3 && data[0] == 0x89.toByte() && data[1] == 'P'.code.toByte() -> "image/png"
        data.size > 3 && data[0] == 'G'.code.toByte() && data[1] == 'I'.code.toByte() -> "image/gif"
        else -> "image/jpeg"
    }
}
