package com.atomic.atomicamp.app.library.tags

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * These run against synthesised FLAC files rather than real ones, because the interesting cases
 * are structural: a picture block that must survive, an ID3 prefix, a truncated file. Real albums
 * are too large to commit and would not cover any of them on purpose.
 */
class FlacTagsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val audio = ByteArray(4096) { (it % 251).toByte() }

    /** STREAMINFO is 34 bytes and its contents do not matter here, only that it survives intact. */
    private val streamInfo = ByteArray(34) { (it + 7).toByte() }

    private fun block(type: Int, data: ByteArray, last: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((if (last) 0x80 else 0x00) or type)
        out.write((data.size ushr 16) and 0xFF)
        out.write((data.size ushr 8) and 0xFF)
        out.write(data.size and 0xFF)
        out.write(data)
        return out.toByteArray()
    }

    private fun commentBlock(vendor: String, tags: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        fun le(v: Int) {
            out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
            out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
        }
        val vb = vendor.toByteArray()
        le(vb.size); out.write(vb)
        le(tags.size)
        tags.forEach { val b = it.toByteArray(); le(b.size); out.write(b) }
        return out.toByteArray()
    }

    private fun writeFlac(
        name: String,
        tags: List<String> = listOf("TITLE=Original", "ARTIST=Someone"),
        extraBlocks: List<Pair<Int, ByteArray>> = emptyList(),
        id3Prefix: Boolean = false,
    ): File {
        val out = ByteArrayOutputStream()
        if (id3Prefix) {
            // ID3v2 header with a synchsafe size, then that many bytes of padding.
            val payload = 40
            out.write("ID3".toByteArray()); out.write(3); out.write(0); out.write(0)
            out.write(0); out.write(0); out.write(0); out.write(payload)
            out.write(ByteArray(payload))
        }
        out.write("fLaC".toByteArray())
        out.write(block(0, streamInfo, last = false))
        extraBlocks.forEach { (type, data) -> out.write(block(type, data, last = false)) }
        out.write(block(4, commentBlock("reference libFLAC", tags), last = true))
        out.write(audio)

        val file = File(folder.root, name)
        file.writeBytes(out.toByteArray())
        return file
    }

    @Test
    fun readsExistingTags() {
        val file = writeFlac("a.flac")
        val tags = FlacTags.read(file)!!
        assertEquals("Original", tags[FlacTags.TITLE])
        assertEquals("Someone", tags[FlacTags.ARTIST])
    }

    @Test
    fun writtenTagsReadBack() {
        val file = writeFlac("b.flac")
        assertTrue(
            FlacTags.write(
                file,
                mapOf(
                    FlacTags.TITLE to "New Title",
                    FlacTags.ARTIST to "New Artist",
                    FlacTags.ALBUM to "New Album",
                    FlacTags.TRACK_NUMBER to "7",
                ),
            ),
        )
        val tags = FlacTags.read(file)!!
        assertEquals("New Title", tags[FlacTags.TITLE])
        assertEquals("New Artist", tags[FlacTags.ARTIST])
        assertEquals("New Album", tags[FlacTags.ALBUM])
        assertEquals("7", tags[FlacTags.TRACK_NUMBER])
    }

    /** The whole point of the exercise: the music must come out the other side unchanged. */
    @Test
    fun audioSurvivesByteForByte() {
        val file = writeFlac("c.flac")
        FlacTags.write(file, mapOf(FlacTags.TITLE to "Whatever"))

        val bytes = file.readBytes()
        val tail = bytes.copyOfRange(bytes.size - audio.size, bytes.size)
        assertArrayEquals(audio, tail)
    }

    @Test
    fun streamInfoSurvives() {
        val file = writeFlac("d.flac")
        FlacTags.write(file, mapOf(FlacTags.TITLE to "Whatever"))

        val bytes = file.readBytes()
        // fLaC + 4-byte block header, then STREAMINFO.
        val recovered = bytes.copyOfRange(8, 8 + streamInfo.size)
        assertArrayEquals(streamInfo, recovered)
        assertEquals(0, bytes[4].toInt() and 0x7F)
    }

    /** Embedded artwork is the block users would most notice losing. */
    @Test
    fun pictureBlockSurvives() {
        val picture = ByteArray(300) { (it % 97).toByte() }
        val file = writeFlac("e.flac", extraBlocks = listOf(6 to picture))
        FlacTags.write(file, mapOf(FlacTags.TITLE to "Kept"))

        val bytes = file.readBytes()
        val index = bytes.toList().windowed(picture.size)
            .indexOfFirst { it.toByteArray().contentEquals(picture) }
        assertTrue("picture block was dropped", index >= 0)
        assertEquals("Kept", FlacTags.read(file)!![FlacTags.TITLE])
    }

    @Test
    fun removesTagsWithBlankValues() {
        val file = writeFlac("f.flac")
        FlacTags.write(file, mapOf(FlacTags.TITLE to "Only", FlacTags.ARTIST to "  "))
        val tags = FlacTags.read(file)!!
        assertEquals("Only", tags[FlacTags.TITLE])
        assertNull(tags[FlacTags.ARTIST])
    }

    /**
     * The library contains an album tagged this way. Writing must drop the prefix rather than
     * leave a stale ID3 title that can win over the comment we just wrote.
     */
    @Test
    fun id3PrefixedFileIsReadableAndLosesThePrefixOnWrite() {
        val file = writeFlac("g.flac", id3Prefix = true)
        assertEquals("Original", FlacTags.read(file)!![FlacTags.TITLE])

        assertTrue(FlacTags.write(file, mapOf(FlacTags.TITLE to "Rewritten")))
        val bytes = file.readBytes()
        assertEquals("fLaC", String(bytes.copyOfRange(0, 4)))
        assertEquals("Rewritten", FlacTags.read(file)!![FlacTags.TITLE])
    }

    @Test
    fun refusesFilesThatAreNotFlac() {
        val file = File(folder.root, "h.flac").apply { writeBytes("not a flac at all".toByteArray()) }
        assertNull(FlacTags.read(file))
        assertFalse(FlacTags.write(file, mapOf(FlacTags.TITLE to "nope")))
    }

    /** A failed write must not damage the original -- it is the user's only copy. */
    @Test
    fun failedWriteLeavesOriginalIntact() {
        val file = File(folder.root, "i.flac")
        val original = "still not a flac".toByteArray()
        file.writeBytes(original)

        assertFalse(FlacTags.write(file, mapOf(FlacTags.TITLE to "nope")))
        assertArrayEquals(original, file.readBytes())
        assertTrue(folder.root.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    private fun List<Byte>.toByteArray() = ByteArray(size) { this[it] }
}
