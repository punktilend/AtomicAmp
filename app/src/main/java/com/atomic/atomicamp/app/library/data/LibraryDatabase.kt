package com.atomic.atomicamp.app.library.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds [Track.metadataInferred]. A real migration rather than a destructive one: rescanning a
 * large library over USB on a head unit is slow, and there is no reason to make the user pay it.
 */
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tracks ADD COLUMN metadataInferred INTEGER NOT NULL DEFAULT 0")
    }
}

/** Adds playlist storage. */
internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS playlists (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                dateCreatedMs INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS playlist_tracks (
                playlistId INTEGER NOT NULL,
                position INTEGER NOT NULL,
                trackUri TEXT NOT NULL,
                PRIMARY KEY(playlistId, position),
                FOREIGN KEY(playlistId) REFERENCES playlists(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_tracks_playlistId ON playlist_tracks(playlistId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_tracks_trackUri ON playlist_tracks(trackUri)")
    }
}

/**
 * Gives tracks an identity separate from their file, so a cue-split rip can hold many tracks in
 * one FLAC. SQLite cannot change a primary key in place, so both tables are rebuilt.
 *
 * Existing rows map cleanly: every track so far is a whole file, so its id is simply its uri, and
 * playlist entries already reference tracks by that same string.
 */
internal val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE tracks_new (
                id TEXT NOT NULL PRIMARY KEY,
                uri TEXT NOT NULL,
                folderUri TEXT NOT NULL,
                relativeDir TEXT NOT NULL,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                album TEXT NOT NULL,
                albumArtist TEXT NOT NULL,
                genre TEXT NOT NULL,
                composer TEXT NOT NULL,
                year INTEGER NOT NULL,
                trackNumber INTEGER NOT NULL,
                discNumber INTEGER NOT NULL,
                durationMs INTEGER NOT NULL,
                albumArtPath TEXT,
                dateAddedMs INTEGER NOT NULL,
                metadataInferred INTEGER NOT NULL DEFAULT 0,
                clipStartMs INTEGER,
                clipEndMs INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO tracks_new (
                id, uri, folderUri, relativeDir, title, artist, album, albumArtist, genre,
                composer, year, trackNumber, discNumber, durationMs, albumArtPath, dateAddedMs,
                metadataInferred, clipStartMs, clipEndMs
            )
            SELECT uri, uri, folderUri, relativeDir, title, artist, album, albumArtist, genre,
                   composer, year, trackNumber, discNumber, durationMs, albumArtPath, dateAddedMs,
                   metadataInferred, NULL, NULL
            FROM tracks
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE tracks")
        db.execSQL("ALTER TABLE tracks_new RENAME TO tracks")

        // playlist_tracks referenced tracks by uri; it now references the new id.
        db.execSQL(
            """
            CREATE TABLE playlist_tracks_new (
                playlistId INTEGER NOT NULL,
                position INTEGER NOT NULL,
                trackId TEXT NOT NULL,
                PRIMARY KEY(playlistId, position),
                FOREIGN KEY(playlistId) REFERENCES playlists(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "INSERT INTO playlist_tracks_new (playlistId, position, trackId) " +
                "SELECT playlistId, position, trackUri FROM playlist_tracks",
        )
        db.execSQL("DROP TABLE playlist_tracks")
        db.execSQL("ALTER TABLE playlist_tracks_new RENAME TO playlist_tracks")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_tracks_playlistId ON playlist_tracks(playlistId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_tracks_trackId ON playlist_tracks(trackId)")
    }
}

/**
 * Every migration, in order. One list so the builder below and the migration tests can never
 * disagree about which migrations exist -- adding one here covers both.
 */
internal val LIBRARY_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

@Database(
    entities = [Track::class, MusicFolder::class, Playlist::class, PlaylistTrack::class],
    version = 4,
    exportSchema = true,
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun musicFolderDao(): MusicFolderDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        @Volatile
        private var instance: LibraryDatabase? = null

        fun get(context: Context): LibraryDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LibraryDatabase::class.java,
                    "library.db",
                ).addMigrations(*LIBRARY_MIGRATIONS)
                    .build()
                    .also { instance = it }
            }
    }
}
