package de.aikitori.tagwerk.core

/**
 * Muster wie `%track% - %artist% - %title%`, um Tags aus dem Dateinamen zu lesen
 * oder Dateien nach ihren Tags umzubenennen. Dateinamen hier immer ohne Endung.
 */
class FilenamePattern(val pattern: String) {
    private sealed interface Part
    private data class Literal(val text: String) : Part
    private data class Placeholder(val field: Field) : Part

    private val parts: List<Part> = buildList {
        var rest = pattern
        while (rest.isNotEmpty()) {
            val match = TOKEN.find(rest)
            if (match == null) {
                add(Literal(rest))
                break
            }
            if (match.range.first > 0) add(Literal(rest.substring(0, match.range.first)))
            val field = NAMES[match.groupValues[1].lowercase()]
            add(if (field != null) Placeholder(field) else Literal(match.value))
            rest = rest.substring(match.range.last + 1)
        }
    }

    val fields: List<Field> = parts.filterIsInstance<Placeholder>().map { it.field }

    private val regex: Regex = parts.joinToString("", prefix = "^\\s*", postfix = "\\s*$") { part ->
        when (part) {
            is Literal -> Regex.escape(part.text)
            is Placeholder -> if (part.field.numeric) "(\\d+)" else "(.+?)"
        }
    }.toRegex()

    /** Liest die Felder aus einem Dateinamen; null, wenn der Name nicht zum Muster passt. */
    fun parse(baseName: String): Map<Field, String>? {
        val match = regex.matchEntire(baseName) ?: return null
        return fields.zip(match.groupValues.drop(1)).associate { (field, value) ->
            field to Tags.normalize(field, value.replace('_', ' '))
        }
    }

    /** Baut den Dateinamen aus den Tags; Zeichen, die in Dateinamen stören, werden ersetzt. */
    fun format(tags: Tags): String = parts.joinToString("") { part ->
        when (part) {
            is Literal -> part.text
            is Placeholder -> {
                val value = tags[part.field]
                if (part.field == Field.TRACK || part.field == Field.DISC) value.padStart(2, '0') else value
            }
        }
    }.let(::sanitize)

    companion object {
        private val TOKEN = Regex("%([a-z]+)%", RegexOption.IGNORE_CASE)

        val NAMES: Map<String, Field> = mapOf(
            "title" to Field.TITLE,
            "artist" to Field.ARTIST,
            "album" to Field.ALBUM,
            "albumartist" to Field.ALBUM_ARTIST,
            "year" to Field.YEAR,
            "track" to Field.TRACK,
            "tracktotal" to Field.TRACK_TOTAL,
            "disc" to Field.DISC,
            "disctotal" to Field.DISC_TOTAL,
            "genre" to Field.GENRE,
            "composer" to Field.COMPOSER,
        )

        val DEFAULT = "%track% - %title%"
        val SUGGESTIONS = listOf(
            "%track% - %title%",
            "%track% - %artist% - %title%",
            "%artist% - %title%",
            "%artist% - %album% - %track% - %title%",
        )

        private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\x00-\\x1f]")

        fun sanitize(name: String): String = name.replace(ILLEGAL, "_").trim().trimEnd('.')
    }
}

fun String.baseName(): String = substringBeforeLast('.', this)
fun String.extension(): String = substringAfterLast('.', "")
