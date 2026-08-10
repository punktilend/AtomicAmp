package com.atomic.atomicamp.app.library.scan

/** One track described by a cue sheet, resolved to absolute times within the audio file. */
data class CueTrack(
    val trackNumber: Int,
    val title: String,
    val performer: String?,
    val startMs: Long,
    /** Null for the final track: it runs to the end of the file, whose length the cue doesn't know. */
    val endMs: Long?,
)

/** A parsed cue sheet: the audio file(s) it describes, plus the tracks inside. */
data class CueSheet(
    val audioFileNames: List<String>,
    val albumTitle: String?,
    val albumPerformer: String?,
    val year: Int?,
    val tracks: List<CueTrack>,
) {
    /**
     * True when this sheet describes one continuous audio file that needs splitting.
     *
     * A sheet naming several files describes an album that is *already* one file per track, and
     * its times restart at zero for each. Treating those as offsets into a single file would put
     * every track at 0:00, so only single-file sheets may be used for splitting.
     */
    val isSingleFile: Boolean get() = audioFileNames.size == 1

    val audioFileName: String? get() = audioFileNames.singleOrNull()
}

/**
 * Parses cue sheets, which describe an album ripped as one continuous audio file.
 *
 * Without this, such an album appears in the library as a single entry hours long instead of its
 * actual tracks — the tracks exist only as offsets in this text file.
 *
 * Written against EAC-style sheets, which is what the ripping tools in common use produce:
 * CRLF line endings, quoted values, and indentation that carries no meaning.
 */
object CueParser {

    /** Cue times are MM:SS:FF where FF is CD frames, of which there are exactly 75 per second. */
    private const val FRAMES_PER_SECOND = 75

    private val COMMAND = Regex("""^\s*(\S+)\s+(.*)$""")

    fun parse(text: String): CueSheet {
        val audioFileNames = mutableListOf<String>()
        var albumTitle: String? = null
        var albumPerformer: String? = null
        var year: Int? = null

        // Track-level fields override disc-level ones, so which section we're in matters.
        data class Pending(var number: Int, var title: String?, var performer: String?, var startMs: Long?)

        val pendingTracks = mutableListOf<Pending>()
        var current: Pending? = null

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim().removeSuffix("\r")
            if (line.isEmpty()) continue
            val match = COMMAND.find(line) ?: continue
            val command = match.groupValues[1].uppercase()
            val argument = match.groupValues[2].trim()

            when (command) {
                "FILE" -> unquoteFirst(argument)?.let(audioFileNames::add)

                "TRACK" -> {
                    val number = argument.substringBefore(' ').toIntOrNull()
                    // Only audio tracks are playable; data tracks would be nonsense to list.
                    if (number != null && argument.uppercase().contains("AUDIO")) {
                        current = Pending(number, null, null, null).also(pendingTracks::add)
                    } else {
                        current = null
                    }
                }

                "TITLE" -> if (current != null) current.title = unquote(argument) else albumTitle = unquote(argument)

                "PERFORMER" ->
                    if (current != null) current.performer = unquote(argument) else albumPerformer = unquote(argument)

                "INDEX" -> {
                    val indexNumber = argument.substringBefore(' ').toIntOrNull()
                    // INDEX 00 is the pregap, which belongs to the previous track's tail.
                    // INDEX 01 is where the track actually begins.
                    if (current != null && indexNumber == 1) {
                        current.startMs = parseCueTime(argument.substringAfter(' ').trim())
                    }
                }

                "REM" -> {
                    val remainder = argument.split(Regex("""\s+"""), limit = 2)
                    if (remainder.size == 2 && remainder[0].uppercase() == "DATE") {
                        year = Regex("""\d{4}""").find(remainder[1])?.value?.toIntOrNull()
                    }
                }
            }
        }

        val started = pendingTracks.filter { it.startMs != null }.sortedBy { it.startMs }
        val tracks = started.mapIndexed { i, track ->
            CueTrack(
                trackNumber = track.number,
                title = track.title ?: "Track ${track.number}",
                performer = track.performer ?: albumPerformer,
                startMs = track.startMs!!,
                // A track ends where the next begins; the last runs to the end of the file.
                endMs = started.getOrNull(i + 1)?.startMs,
            )
        }

        return CueSheet(audioFileNames.toList(), albumTitle, albumPerformer, year, tracks)
    }

    /** `MM:SS:FF` to milliseconds. Minutes are unbounded — a long rip exceeds 60. */
    fun parseCueTime(value: String): Long? {
        val parts = value.trim().split(':')
        if (parts.size != 3) return null
        val minutes = parts[0].toLongOrNull() ?: return null
        val seconds = parts[1].toLongOrNull() ?: return null
        val frames = parts[2].toLongOrNull() ?: return null
        if (minutes < 0 || seconds < 0 || frames < 0) return null
        return (minutes * 60_000L) + (seconds * 1_000L) + (frames * 1_000L / FRAMES_PER_SECOND)
    }

    private fun unquote(value: String): String? =
        value.trim().removeSurrounding("\"").trim().takeIf { it.isNotEmpty() }

    /** `FILE "name.flac" WAVE` — the filename is quoted, with a type keyword after it. */
    private fun unquoteFirst(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.startsWith('"')) {
            val end = trimmed.indexOf('"', startIndex = 1)
            if (end > 1) return trimmed.substring(1, end)
        }
        return trimmed.substringBeforeLast(' ').trim().takeIf { it.isNotEmpty() }
    }
}
