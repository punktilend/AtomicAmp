package com.atomic.atomicamp.app.library.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PathMetadataInferenceTest {

    @Test
    fun `reads artist album track and title from a conventional layout`() {
        val result = PathMetadataInference.infer("Neon Fields/Coastline", "03 Ocean Drive.mp3")

        assertEquals("Neon Fields", result.artist)
        assertEquals("Coastline", result.album)
        assertEquals("Ocean Drive", result.title)
        assertEquals(3, result.trackNumber)
    }

    @Test
    fun `disc subfolders are not mistaken for the album`() {
        val result = PathMetadataInference.infer("Pink Floyd/The Wall/CD2", "01 Hey You.mp3")

        assertEquals("Pink Floyd", result.artist)
        assertEquals("The Wall", result.album)
    }

    @Test
    fun `disc folder spellings are all recognised`() {
        for (disc in listOf("CD1", "cd 1", "Disc 2", "disk-3", "Volume 1", "vol.2")) {
            val result = PathMetadataInference.infer("Artist/Album/$disc", "01 Song.mp3")
            assertEquals("album should survive '$disc'", "Album", result.album)
            assertEquals("artist should survive '$disc'", "Artist", result.artist)
        }
    }

    @Test
    fun `a lone folder is treated as an album, not an artist`() {
        val result = PathMetadataInference.infer("Greatest Hits", "01 Song.mp3")

        assertEquals("Greatest Hits", result.album)
        assertNull("a single folder is too ambiguous to call an artist", result.artist)
    }

    @Test
    fun `files at the tree root yield no artist or album`() {
        val result = PathMetadataInference.infer("", "random.mp3")

        assertNull(result.artist)
        assertNull(result.album)
        assertEquals("random", result.title)
    }

    @Test
    fun `artist is taken from the filename only when the path did not supply one`() {
        val result = PathMetadataInference.infer("", "01 - Portishead - Roads.mp3")

        assertEquals("Portishead", result.artist)
        assertEquals("Roads", result.title)
        assertEquals(1, result.trackNumber)
    }

    @Test
    fun `a hyphen in the title is not mistaken for an artist when the path knows better`() {
        val result = PathMetadataInference.infer("Arcade Fire/Funeral", "02 Wake Up - Remix.mp3")

        assertEquals("Arcade Fire", result.artist)
        assertEquals(
            "the path already gave an artist, so the hyphen must stay part of the title",
            "Wake Up - Remix",
            result.title,
        )
    }

    @Test
    fun `track number separators are all handled`() {
        assertEquals(1, PathMetadataInference.infer("", "01 Song.mp3").trackNumber)
        assertEquals(2, PathMetadataInference.infer("", "02. Song.mp3").trackNumber)
        assertEquals(3, PathMetadataInference.infer("", "03 - Song.mp3").trackNumber)
        assertEquals(4, PathMetadataInference.infer("", "04_Song.mp3").trackNumber)
        assertEquals(112, PathMetadataInference.infer("", "112 Song.mp3").trackNumber)
    }

    @Test
    fun `a leading number is not stolen from a title that begins with one`() {
        // No separator after the digits, so this is a title, not a track number.
        val result = PathMetadataInference.infer("", "1979.mp3")

        assertNull(result.trackNumber)
        assertEquals("1979", result.title)
    }

    @Test
    fun `bracketed noise and underscores are cleaned up`() {
        val result = PathMetadataInference.infer("Radiohead/OK Computer [1997] (Remaster)", "05 Let_Down.mp3")

        assertEquals("OK Computer", result.album)
        assertEquals("Let Down", result.title)
    }

    @Test
    fun `an extensionless filename still yields a title`() {
        val result = PathMetadataInference.infer("Artist/Album", "Untitled")

        assertEquals("Untitled", result.title)
    }

    @Test
    fun `empty metadata reports itself as empty`() {
        assertTrue(InferredMetadata().isEmpty)
        assertTrue(!InferredMetadata(artist = "x").isEmpty)
    }
}
