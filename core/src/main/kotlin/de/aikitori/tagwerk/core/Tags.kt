package de.aikitori.tagwerk.core

/** Die Felder, die Tagwerk bearbeitet. Die Reihenfolge ist auch die Reihenfolge im Editor. */
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

/** Tags einer Datei. Leere Felder fehlen in [fields]. */
data class Tags(val fields: Map<Field, String> = emptyMap(), val artwork: Artwork? = null) {
    operator fun get(field: Field): String = fields[field].orEmpty()

    fun with(field: Field, value: String): Tags {
        val clean = normalize(field, value)
        return copy(fields = if (clean.isEmpty()) fields - field else fields + (field to clean))
    }

    fun with(values: Map<Field, String>): Tags = values.entries.fold(this) { tags, (field, value) -> tags.with(field, value) }

    /** Felder, deren Wert nicht passt (z. B. Buchstaben in der Tracknummer). */
    fun invalidFields(): Set<Field> = fields.filter { (field, value) -> !isValid(field, value) }.keys

    companion object {
        fun normalize(field: Field, value: String): String {
            val trimmed = value.trim()
            // "07" als 7 speichern, sonst schreiben die Formate es unterschiedlich zurück
            return if (field.numeric && trimmed.all(Char::isDigit) && trimmed.isNotEmpty()) {
                trimmed.trimStart('0').ifEmpty { "0" }
            } else {
                trimmed
            }
        }

        fun isValid(field: Field, value: String): Boolean = !field.numeric || value.all(Char::isDigit)
    }
}

/** Technische Angaben zur Datei, nur zum Anzeigen. */
data class AudioInfo(val format: String, val seconds: Int, val bitrateKbps: String)

data class AudioFileTags(val tags: Tags, val info: AudioInfo)
