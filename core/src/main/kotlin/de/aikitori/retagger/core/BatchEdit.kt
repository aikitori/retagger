package de.aikitori.retagger.core

sealed interface ArtworkChange {
    data object Keep : ArtworkChange
    data object Remove : ArtworkChange
    data class Replace(val artwork: Artwork) : ArtworkChange
}

/**
 * Changes for several files at once. [fields] holds only the fields to change;
 * an empty value removes the field.
 */
data class BatchEdit(
    val fields: Map<Field, String> = emptyMap(),
    val artwork: ArtworkChange = ArtworkChange.Keep,
    /** Set track numbers 1..n in file order, plus the track total. */
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
        /** Shared value per field; null if the files differ. */
        fun commonValues(all: List<Tags>): Map<Field, String?> = Field.entries.associateWith { field ->
            all.map { it[field] }.distinct().singleOrNull()
        }
    }
}
