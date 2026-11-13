package de.aikitori.tagwerk.core

sealed interface ArtworkChange {
    data object Keep : ArtworkChange
    data object Remove : ArtworkChange
    data class Replace(val artwork: Artwork) : ArtworkChange
}

/**
 * Änderungen für mehrere Dateien auf einmal. In [fields] stehen nur die Felder, die geändert werden;
 * ein leerer Wert löscht das Feld.
 */
data class BatchEdit(
    val fields: Map<Field, String> = emptyMap(),
    val artwork: ArtworkChange = ArtworkChange.Keep,
    /** Tracknummern 1..n in der Reihenfolge der Dateien vergeben und die Gesamtzahl setzen. */
    val numberTracks: Boolean = false,
) {
    fun apply(tags: Tags, index: Int, count: Int): Tags {
        var result = tags.with(fields)
        if (numberTracks) {
            result = result.with(Field.TRACK, (index + 1).toString()).with(Field.TRACK_TOTAL, count.toString())
        }
        return when (artwork) {
            ArtworkChange.Keep -> result
            ArtworkChange.Remove -> result.copy(artwork = null)
            is ArtworkChange.Replace -> result.copy(artwork = artwork.artwork)
        }
    }

    val isEmpty get() = fields.isEmpty() && artwork == ArtworkChange.Keep && !numberTracks

    companion object {
        /** Gemeinsamer Wert je Feld; null, wenn sich die Dateien unterscheiden. */
        fun commonValues(all: List<Tags>): Map<Field, String?> = Field.entries.associateWith { field ->
            all.map { it[field] }.distinct().singleOrNull()
        }
    }
}
