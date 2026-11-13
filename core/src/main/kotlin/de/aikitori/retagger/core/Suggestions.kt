package de.aikitori.retagger.core

/**
 * Guesses tag values from a file name, its sibling files and the folder names above it.
 * Parts shared by most files in a folder (e.g. the band in "Song - Band.mp3") are suggested
 * as artist or album, parts that differ per file as title.
 */
object Suggestions {
    private const val MAX = 4
    private val TRACK = Regex("^(\\d{1,3})(?:\\s*[-._)]\\s*|\\s+)(.+)$")
    private val SEPARATOR = Regex("\\s+[-–—]\\s+|\\s*_-_\\s*")
    private val YEAR = Regex("[(\\[]?\\b((?:19|20)\\d{2})\\b[)\\]]?")
    private val DISC = Regex("^(?:cd|disc|disk)\\s*(\\d{1,2})$", RegexOption.IGNORE_CASE)
    private val GENERIC = setOf("music", "musik", "download", "downloads", "audio", "mp3", "sdcard", "0", "emulated", "media", "songs")

    private class Name(val track: String?, val parts: List<String>, val years: List<String>)

    private fun parse(text: String): Name {
        var rest = text.replace('_', ' ').replace(Regex("\\s+"), " ").trim()
        val years = YEAR.findAll(rest).map { it.groupValues[1] }.toList()
        rest = rest.replace(YEAR, " ").trim()
        val track = TRACK.matchEntire(rest)?.let { match ->
            rest = match.groupValues[2]
            Tags.normalize(Field.TRACK, match.groupValues[1])
        }
        val parts = rest.split(SEPARATOR)
            .map { it.trim(' ', '-', '–', '.', '(', ')', '[', ']') }
            .filter { it.isNotEmpty() && !it.all(Char::isDigit) }
        return Name(track, parts, years)
    }

    /**
     * @param fileName the file to guess for, with extension
     * @param folders folder names from the chosen root down to the file's folder
     * @param siblings names of all music files in the same folder (may include [fileName])
     */
    fun from(fileName: String, folders: List<String>, siblings: List<String>): Map<Field, List<String>> {
        val file = parse(fileName.baseName())
        val others = siblings.filter { it != fileName }.map { parse(it.baseName()) }
        // a part is shared if most other files contain it too
        val shared = if (others.isEmpty()) {
            emptySet()
        } else {
            file.parts.filterTo(mutableSetOf()) { part ->
                others.count { other -> other.parts.any { it.equals(part, ignoreCase = true) } } * 2 >= others.size
            }
        }
        val own = file.parts.filterNot { it in shared }

        val meaningful = folders.filterNot { it.lowercase() in GENERIC }
        val discFolder = meaningful.lastOrNull()?.let { DISC.matchEntire(it.trim()) }
        val albumFolders = if (discFolder != null) meaningful.dropLast(1) else meaningful
        val album = albumFolders.lastOrNull()?.let(::parse)
        val artistFolder = albumFolders.dropLast(1).lastOrNull()?.let(::parse)
        // "Band - Album" as folder name
        val albumArtist = album?.parts?.takeIf { it.size > 1 }?.first()
        val albumTitle = album?.parts?.let { if (it.size > 1) it.drop(1).joinToString(" - ") else it.firstOrNull() }

        val artists = buildList {
            addAll(shared)
            albumArtist?.let(::add)
            artistFolder?.parts?.let(::addAll)
            if (others.isEmpty() && file.parts.size > 1) addAll(file.parts.dropLast(1))
        }
        // without siblings "Artist - Title" is the most common order
        val titles = if (others.isEmpty()) file.parts.reversed() else own
        val albums = buildList {
            albumTitle?.let(::add)
            addAll(shared.filterNot { it.equals(artistFolder?.parts?.firstOrNull(), ignoreCase = true) })
        }

        return mapOf(
            Field.TITLE to titles,
            Field.ARTIST to artists,
            Field.ALBUM_ARTIST to artists,
            Field.ALBUM to albums,
            Field.YEAR to file.years + (album?.years ?: emptyList()) + (artistFolder?.years ?: emptyList()),
            Field.TRACK to listOfNotNull(file.track),
            Field.TRACK_TOTAL to listOfNotNull(siblings.size.takeIf { file.track != null && it > 1 }?.toString()),
            Field.DISC to listOfNotNull(discFolder?.groupValues?.get(1)?.let { Tags.normalize(Field.DISC, it) }),
        ).mapValues { (_, values) -> values.distinctBy { it.lowercase() }.take(MAX) }
            .filterValues { it.isNotEmpty() }
    }

    /** Suggestions for several files at once: only values that make sense for all of them. */
    fun forBatch(files: List<String>, folders: List<String>): Map<Field, List<String>> {
        val first = files.firstOrNull() ?: return emptyMap()
        return from(first, folders, files) - setOf(Field.TITLE, Field.TRACK)
    }
}
