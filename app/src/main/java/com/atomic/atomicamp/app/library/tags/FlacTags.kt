package com.atomic.atomicamp.app.library.tags

import java.io.File
import java.io.RandomAccessFile

/**
 * Reads and writes FLAC Vorbis comments.
 *
 * Android can read tags through `MediaMetadataRetriever` and cannot write them at all, so editing
 * means rewriting the file. That is the dangerous part of a tag editor: the target is the user's
 * only copy of an album. Three rules follow from that, and they are the reason this exists as its
 * own testable piece rather than inside a screen:
 *
 *  - **Never write in place.** Output goes to a sibling temp file and only replaces the original
 *    once it is complete, so a failure halfway leaves the original untouched.
 *  - **Never touch the audio.** Only the metadata blocks are rebuilt; the encoded frames are
 *    copied through byte for byte.
 *  - **Keep what isn't ours.** SEEKTABLE, PICTURE, CUESHEET and APPLICATION blocks survive.
 *    Padding is dropped because the rewritten block is a different size anyway.
 *
 * ID3: some files in this library carry an ID3v2 tag in front of the `fLaC` magic (all of NOFX -
 * *Ribbed*, from a tagger that wrote ID3 onto FLAC). The prefix is **dropped** on write rather
 * than preserved, because a stale ID3 title can win over the Vorbis comment in some readers, and
 * an edit that silently does not take is worse than a file that lost a non-standard tag.
 */
object FlacTags {

    private const val MAGIC = "fLaC"

    private const val BLOCK_STREAMINFO = 0
    private const val BLOCK_PADDING = 1
    private const val BLOCK_VORBIS_COMMENT = 4

    private const val DEFAULT_VENDOR = "AtomicAmp"

    /** Keys this app understands. Vorbis comment names are conventionally upper case. */
    const val TITLE = "TITLE"
    const val ARTIST = "ARTIST"
    const val ALBUM = "ALBUM"
    const val ALBUM_ARTIST = "ALBUMARTIST"
    const val GENRE = "GENRE"
    const val DATE = "DATE"
    const val TRACK_NUMBER = "TRACKNUMBER"
    const val DISC_NUMBER = "DISCNUMBER"

    private class Block(val type: Int, val data: ByteArray)

    private class Parsed(
        val blocks: List<Block>,
        val audioOffset: Long,
        val vendor: String,
    )

    /** Tags as upper-case keys, or null if this is not a readable FLAC. */
    fun read(file: File): Map<String, String>? {
        val parsed = runCatching { parse(file) }.getOrNull() ?: return null
        val comment = parsed.blocks.firstOrNull { it.type == BLOCK_VORBIS_COMMENT }
            ?: return emptyMap()
        return runCatching { parseComments(comment.data) }.getOrDefault(emptyMap())
    }

    /**
     * Replaces the Vorbis comment block with [tags]. Entries with a blank value are removed.
     *
     * Returns false and leaves the file untouched if it cannot be parsed or the rewrite fails.
     */
    fun write(file: File, tags: Map<String, String>): Boolean {
        val parsed = runCatching { parse(file) }.getOrNull() ?: return false

        val kept = parsed.blocks.filter {
            it.type != BLOCK_VORBIS_COMMENT && it.type != BLOCK_PADDING
        }
        // STREAMINFO must come first; the format requires it and decoders rely on it.
        val streamInfo = kept.firstOrNull { it.type == BLOCK_STREAMINFO } ?: return false
        val others = kept.filter { it !== streamInfo }
        val comment = Block(BLOCK_VORBIS_COMMENT, buildComments(parsed.vendor, tags))
        val ordered = listOf(streamInfo) + others + comment

        val temp = File(file.parentFile, "${file.name}.atomicamp.tmp")
        val ok = runCatching {
            temp.outputStream().buffered().use { out ->
                out.write(MAGIC.toByteArray(Charsets.US_ASCII))
                ordered.forEachIndexed { index, block ->
                    val isLast = index == ordered.lastIndex
                    out.write(((if (isLast) 0x80 else 0x00) or block.type))
                    out.write((block.data.size ushr 16) and 0xFF)
                    out.write((block.data.size ushr 8) and 0xFF)
                    out.write(block.data.size and 0xFF)
                    out.write(block.data)
                }
                RandomAccessFile(file, "r").use { input ->
                    input.seek(parsed.audioOffset)
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                    }
                }
            }
            true
        }.getOrDefault(false)

        if (!ok || temp.length() <= 0) {
            temp.delete()
            return false
        }
        // Only now is the original at risk, and only for as long as the rename takes.
        if (!temp.renameTo(file)) {
            val replaced = runCatching {
                temp.copyTo(file, overwrite = true)
                true
            }.getOrDefault(false)
            temp.delete()
            return replaced
        }
        return true
    }

    private fun parse(file: File): Parsed {
        RandomAccessFile(file, "r").use { input ->
            val head = ByteArray(10)
            if (input.read(head) < 4) error("too short")

            var offset = 0L
            if (head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
                val size = (head[6].toInt() and 0x7F shl 21) or
                    (head[7].toInt() and 0x7F shl 14) or
                    (head[8].toInt() and 0x7F shl 7) or
                    (head[9].toInt() and 0x7F)
                offset = 10L + size
            }

            input.seek(offset)
            val magic = ByteArray(4)
            input.readFully(magic)
            if (String(magic, Charsets.US_ASCII) != MAGIC) error("not flac")

            val blocks = mutableListOf<Block>()
            var vendor = DEFAULT_VENDOR
            while (true) {
                val header = ByteArray(4)
                input.readFully(header)
                val isLast = (header[0].toInt() and 0x80) != 0
                val type = header[0].toInt() and 0x7F
                val length = ((header[1].toInt() and 0xFF) shl 16) or
                    ((header[2].toInt() and 0xFF) shl 8) or
                    (header[3].toInt() and 0xFF)
                val data = ByteArray(length)
                input.readFully(data)
                blocks += Block(type, data)
                if (type == BLOCK_VORBIS_COMMENT) {
                    vendor = runCatching { readVendor(data) }.getOrDefault(DEFAULT_VENDOR)
                }
                if (isLast) break
            }
            return Parsed(blocks, input.filePointer, vendor)
        }
    }

    private fun readVendor(data: ByteArray): String {
        val length = littleEndianInt(data, 0)
        return String(data, 4, length, Charsets.UTF_8)
    }

    private fun parseComments(data: ByteArray): Map<String, String> {
        var p = 0
        val vendorLength = littleEndianInt(data, p); p += 4 + vendorLength
        val count = littleEndianInt(data, p); p += 4

        val out = LinkedHashMap<String, String>()
        repeat(count) {
            if (p + 4 > data.size) return out
            val length = littleEndianInt(data, p); p += 4
            if (length < 0 || p + length > data.size) return out
            val entry = String(data, p, length, Charsets.UTF_8); p += length
            val split = entry.indexOf('=')
            if (split > 0) {
                out[entry.substring(0, split).uppercase()] = entry.substring(split + 1)
            }
        }
        return out
    }

    private fun buildComments(vendor: String, tags: Map<String, String>): ByteArray {
        val entries = tags
            .filterValues { it.isNotBlank() }
            .map { (key, value) -> "${key.uppercase()}=$value".toByteArray(Charsets.UTF_8) }

        val vendorBytes = vendor.toByteArray(Charsets.UTF_8)
        val size = 4 + vendorBytes.size + 4 + entries.sumOf { 4 + it.size }
        val out = ByteArray(size)
        var p = 0
        p = putLittleEndianInt(out, p, vendorBytes.size)
        vendorBytes.copyInto(out, p); p += vendorBytes.size
        p = putLittleEndianInt(out, p, entries.size)
        for (entry in entries) {
            p = putLittleEndianInt(out, p, entry.size)
            entry.copyInto(out, p); p += entry.size
        }
        return out
    }

    private fun littleEndianInt(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0xFF) or
            ((data[at + 1].toInt() and 0xFF) shl 8) or
            ((data[at + 2].toInt() and 0xFF) shl 16) or
            ((data[at + 3].toInt() and 0xFF) shl 24)

    private fun putLittleEndianInt(data: ByteArray, at: Int, value: Int): Int {
        data[at] = (value and 0xFF).toByte()
        data[at + 1] = ((value ushr 8) and 0xFF).toByte()
        data[at + 2] = ((value ushr 16) and 0xFF).toByte()
        data[at + 3] = ((value ushr 24) and 0xFF).toByte()
        return at + 4
    }
}
