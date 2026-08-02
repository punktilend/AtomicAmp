package com.atomic.atomicamp.app.library.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A SAF tree the user granted access to; re-scanned on demand via [uri]. */
@Entity(tableName = "music_folders")
data class MusicFolder(
    @PrimaryKey val uri: String,
    val displayName: String,
    val dateAddedMs: Long,
)
