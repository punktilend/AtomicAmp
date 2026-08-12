package com.atomic.atomicamp.app.library.lyrics

import java.io.File

/** One timed line. [timeMs] is 0 for unsynced lyrics, where order is all there is. */
data class LyricLine(val timeMs: Long, val text: String)

data class Lyrics(val lines: List<LyricLine>, val synced: Boolean) {

    val isEmpty: Boolean get() = lines.isEmpty()

    /**
     * Index of the line that should be showing at [positionMs], or -1 before the first one.
     *
     * Binary search rather than a scan: this is called on every position tick, and a long song
     * with per-syllable timing runs to hundreds of lines.
     */
    fun indexAt(positionMs: Long): Int {
        if (!synced || lines.isEmpty()) return -1
        var low = 0
        var high = lines.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) / 2
            if (lines[mid].timeMs <= positionMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }
}

/**
 * Parser for `.lrc` lyrics.
 *
 * The format is loose in practice: timestamps come as `[mm:ss.xx]`, `[mm:ss:xx]` or `[mm:ss]`, a
 * single line may carry several timestamps when a chorus repeats, and files are peppered with
 * metadata tags that look exactly like timestamps but are not. Anything unparseable is treated as
 * plain text rather than dropped, so a malformed file degrades to unsynced lyrics instead of to
 * nothing.
 */
object LrcParser {

    private val TIMESTAMP = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val METADATA = Regex("""^\[[a-zA-Z#]+:.*]$""")
    private val OFFSET = Regex("""^\[offset:\s*([+-]?\d+)\s*]$""", RegexOption.IGNORE_CASE)

    fun parse(text: String): Lyrics {
        if (text.isBlank()) return Lyrics(emptyList(), synced = false)

        // Some taggers ship LRC with an offset in milliseconds, positive meaning "show earlier".
        var offsetMs = 0L
        val timed = mutableListOf<LyricLine>()
        val plain = mutableListOf<String>()

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val offsetMatch = OFFSET.find(line)
            if (offsetMatch != null) {
                offsetMs = offsetMatch.groupValues[1].toLongOrNull() ?: 0L
                continue
            }

            val stamps = TIMESTAMP.findAll(line).toList()
            if (stamps.isEmpty()) {
                if (!METADATA.matches(line)) plain += line
                continue
            }

            val content = line.substring(stamps.last().range.last + 1).trim()
            for (stamp in stamps) {
                val minutes = stamp.groupValues[1].toLong()
                val seconds = stamp.groupValues[2].toLong()
                val fraction = stamp.groupValues[3]
                // Two digits are centiseconds, three are milliseconds.
                val fractionMs = when (fraction.length) {
                    0 -> 0L
                    1 -> fraction.toLong() * 100
                    2 -> fraction.toLong() * 10
                    else -> fraction.take(3).toLong()
                }
                timed += LyricLine(
                    timeMs = (minutes * 60_000 + seconds * 1000 + fractionMs - offsetMs)
                        .coerceAtLeast(0L),
                    text = content,
                )
            }
        }

        return if (timed.isNotEmpty()) {
            // Repeated choruses arrive out of order because one source line carries many stamps.
            Lyrics(timed.sortedBy { it.timeMs }, synced = true)
        } else {
            Lyrics(plain.map { LyricLine(0L, it) }, synced = false)
        }
    }
}

/**
 * Finds lyrics for a track, offline only.
 *
 * The app has no internet permission and that is a selling point, so nothing is fetched. A
 * sidecar `.lrc` beside the audio wins over an embedded tag: it is the thing a user can edit
 * without rewriting the music file, so if both exist the sidecar is the more deliberate of the
 * two.
 */
object LyricsLoader {

    fun forAudioFile(audio: File, embedded: String? = null): Lyrics? {
        sidecarFor(audio)?.let { file ->
            val parsed = runCatching { LrcParser.parse(file.readText()) }.getOrNull()
            if (parsed != null && !parsed.isEmpty) return parsed
        }
        if (!embedded.isNullOrBlank()) {
            val parsed = LrcParser.parse(embedded)
            if (!parsed.isEmpty) return parsed
        }
        return null
    }

    /** `Track.flac` -> `Track.lrc`, matched case-insensitively as filesystems vary. */
    fun sidecarFor(audio: File): File? {
        val base = audio.name.substringBeforeLast('.')
        val exact = File(audio.parentFile, "$base.lrc")
        if (exact.isFile) return exact
        return runCatching {
            audio.parentFile?.listFiles()?.firstOrNull {
                it.isFile && it.name.equals("$base.lrc", ignoreCase = true)
            }
        }.getOrNull()
    }
}
