package de.aikitori.retagger.core

/** The fields Retagger edits, in editor order. */
enum class Field(val numeric: Boolean = false) {
    TITLE,
    ARTIST,
    ALBUM,
    ALBUM_ARTIST,
    YEAR,
    TRACK(numeric = true),
    TRACK_TOTAL(numeric = true),
    DISC(numeric = true),
    DISC_TOTAL(numeric = true),
    GENRE,
    COMPOSER,
    COMMENT,
}

class Artwork(val data: ByteArray, val mimeType: String) {
    override fun equals(other: Any?) = other is Artwork && mimeType == other.mimeType && data.contentEquals(other.data)
    override fun hashCode() = 31 * data.contentHashCode() + mimeType.hashCode()
}

/** Tags of one file. Empty fields are absent from [fields]. */
data class Tags(val fields: Map<Field, String> = emptyMap(), val artwork: Artwork? = null) {
    operator fun get(field: Field): String = fields[field].orEmpty()

    fun with(field: Field, value: String): Tags {
        val clean = normalize(field, value)
        return copy(fields = if (clean.isEmpty()) fields - field else fields + (field to clean))
    }

    fun with(values: Map<Field, String>): Tags = values.entries.fold(this) { tags, (field, value) -> tags.with(field, value) }

    /** Fields with an invalid value (e.g. letters in the track number). */
    fun invalidFields(): Set<Field> = fields.filter { (field, value) -> !isValid(field, value) }.keys

    companion object {
        fun normalize(field: Field, value: String): String {
            val trimmed = value.trim()
            // store "07" as 7, the formats would otherwise write it back differently
            return if (field.numeric && trimmed.all(Char::isDigit) && trimmed.isNotEmpty()) {
                trimmed.trimStart('0').ifEmpty { "0" }
            } else {
                trimmed
            }
        }

        fun isValid(field: Field, value: String): Boolean = !field.numeric || value.all(Char::isDigit)
    }
}

/** Technical details of the file, for display only. */
data class AudioInfo(val format: String, val seconds: Int, val bitrateKbps: String)

data class AudioFileTags(val tags: Tags, val info: AudioInfo)
