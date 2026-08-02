package com.atomic.atomicamp.app.library.scan

/**
 * Metadata guessed from where a file sits and what it is called. Every field is nullable: absent
 * means "no confident guess", which the caller must treat as unknown rather than substituting
 * something plausible.
 */
data class InferredMetadata(
    val artist: String? = null,
    val album: String? = null,
    val title: String? = null,
    val trackNumber: Int? = null,
) {
    val isEmpty: Boolean
        get() = artist == null && album == null && title == null && trackNumber == null
}

/**
 * Recovers metadata from folder layout and filename for files with missing or empty tags.
 *
 * Most libraries are already organised as `Artist/Album/NN Title.ext`, so the information is
 * usually right there in the path — no network, no fingerprinting, no guessing at content.
 *
 * These are *guesses*. Callers must only apply them where a real tag is absent, and must record
 * that the value was inferred: silently overwriting tags, or presenting a guess as fact, turns a
 * merely incomplete library into an untrustworthy one.
 */
object PathMetadataInference {

    /** `CD1`, `Disc 2`, `Volume 3` — a disc subfolder, not the album name. */
    private val DISC_FOLDER = Regex("""^(cd|disc|disk|volume|vol)[\s._-]*\d+$""", RegexOption.IGNORE_CASE)

    /** Leading track number: `01 Title`, `1. Title`, `03 - Title`. */
    private val LEADING_TRACK_NUMBER = Regex("""^(\d{1,3})[\s._-]+(.*)$""")

    /** Bracketed junk many rippers leave behind: `(1969)`, `[FLAC]`, `{remaster}`. */
    private val BRACKETED = Regex("""[\[({][^\])}]*[\])}]""")

    fun infer(relativeDir: String, fileName: String): InferredMetadata {
        val segments = relativeDir.split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !DISC_FOLDER.matches(it) }

        // Deepest remaining folder is the album; the one above it is the artist. A single folder is
        // far more often an album than an artist, so don't guess an artist from it.
        val album = segments.lastOrNull()
        val artistFromPath = if (segments.size >= 2) segments[segments.size - 2] else null

        val baseName = fileName.substringBeforeLast('.', fileName).trim()
        val trackMatch = LEADING_TRACK_NUMBER.find(baseName)
        val trackNumber = trackMatch?.groupValues?.get(1)?.toIntOrNull()
        val afterTrackNumber = trackMatch?.groupValues?.get(2)?.trim() ?: baseName

        // `Artist - Title` in the filename, but only trusted when the path gave us no artist --
        // otherwise a title like "Wake Up - Remix" would be misread as an artist.
        var artist = artistFromPath
        var title = afterTrackNumber
        if (artistFromPath == null) {
            val parts = afterTrackNumber.split(" - ").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size == 2) {
                artist = parts[0]
                title = parts[1]
            }
        }

        return InferredMetadata(
            artist = artist?.cleaned(),
            album = album?.cleaned(),
            title = title.cleaned(),
            trackNumber = trackNumber,
        )
    }

    /** Strips bracketed noise and separator characters used in place of spaces. */
    private fun String.cleaned(): String? =
        BRACKETED.replace(this, " ")
            .replace('_', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('-', '.', ' ')
            .takeIf { it.isNotEmpty() }
}
