package com.atomic.atomicamp.app.library.data

/** One row of the Albums tab: an album's identity plus enough to render a tile. */
data class AlbumSummary(
    val album: String,
    val albumArtist: String,
    val trackCount: Int,
    val albumArtPath: String?,
    val year: Int,
)

/** One row of the Artists tab. */
data class ArtistSummary(
    val artist: String,
    val trackCount: Int,
    val albumCount: Int,
)
