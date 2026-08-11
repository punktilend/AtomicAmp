package com.atomic.atomicamp.app.library.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_DB = "migration-test"

private const val FOLDER_URI =
    "content://com.android.externalstorage.documents/tree/0000-0000%3AMusic"
private const val TRACK_A = "$FOLDER_URI/document/0000-0000%3AMusic%2FBadly%20Drawn%2F01.flac"
private const val TRACK_B = "$FOLDER_URI/document/0000-0000%3AMusic%2FBadly%20Drawn%2F02.flac"

/**
 * Migration coverage for [LibraryDatabase].
 *
 * These matter more than most tests here: the head unit is killed at every ignition-off and the
 * library is expensive to rebuild over USB, so a migration that quietly drops rows costs the user
 * a very long rescan in a parked car. The schemas under `app/schemas` are the real ones each
 * version shipped with, recovered by building the commit that introduced them.
 */
@RunWith(AndroidJUnit4::class)
class LibraryMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LibraryDatabase::class.java,
    )

    @Test
    fun migration1To2_keepsTracksAndTreatsExistingMetadataAsRead() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.insertTrack(TRACK_A, title = "Bad Day")
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, *LIBRARY_MIGRATIONS)

        db.query("SELECT uri, title, artist, metadataInferred FROM tracks").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals(TRACK_A, cursor.getString(0))
            assertEquals("Bad Day", cursor.getString(1))
            assertEquals("Artist", cursor.getString(2))
            // Rows that predate the column were scanned from real tags, not inferred from a path.
            assertEquals(0, cursor.getInt(3))
        }
    }

    @Test
    fun migration2To3_addsEmptyPlaylistTablesAndLeavesTracksAlone() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.insertTrack(TRACK_A, title = "Bad Day", metadataInferred = true)
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, *LIBRARY_MIGRATIONS)

        db.query("SELECT COUNT(*) FROM playlists").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM playlist_tracks").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.query("SELECT uri, metadataInferred FROM tracks").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals(TRACK_A, cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
        }
    }

    @Test
    fun migration3To4_givesEachTrackAnIdWithoutLosingItsFileOrMetadata() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.insertTrack(TRACK_A, title = "Bad Day", metadataInferred = false)
            db.insertTrack(TRACK_B, title = "Yellow", metadataInferred = true)
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, *LIBRARY_MIGRATIONS)

        db.query(
            "SELECT id, uri, title, album, year, durationMs, metadataInferred, " +
                "clipStartMs, clipEndMs FROM tracks ORDER BY title",
        ).use { cursor ->
            assertEquals(2, cursor.count)

            assertTrue(cursor.moveToFirst())
            // Every pre-v4 track is a whole file, so its identity is simply its uri.
            assertEquals(TRACK_A, cursor.getString(0))
            assertEquals(TRACK_A, cursor.getString(1))
            assertEquals("Bad Day", cursor.getString(2))
            assertEquals("Album", cursor.getString(3))
            assertEquals(1998, cursor.getInt(4))
            assertEquals(240_000L, cursor.getLong(5))
            assertEquals(0, cursor.getInt(6))
            // A whole file is not a clip of itself; both bounds stay unset.
            assertTrue(cursor.isNull(7))
            assertTrue(cursor.isNull(8))

            assertTrue(cursor.moveToNext())
            assertEquals(TRACK_B, cursor.getString(0))
            assertEquals(TRACK_B, cursor.getString(1))
            assertEquals("Yellow", cursor.getString(2))
            assertEquals(1, cursor.getInt(6))
        }
    }

    @Test
    fun migration3To4_repointsPlaylistEntriesAtTrackIdsKeepingTheirOrder() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.insertTrack(TRACK_A, title = "Bad Day", metadataInferred = false)
            db.insertTrack(TRACK_B, title = "Yellow", metadataInferred = false)
            db.execSQL(
                "INSERT INTO playlists (id, name, dateCreatedMs) VALUES (1, 'Drive', 1700000000000)",
            )
            // Deliberately not in track order -- a playlist's order is the user's, not the album's.
            db.execSQL(
                "INSERT INTO playlist_tracks (playlistId, position, trackUri) VALUES (1, 0, ?)",
                arrayOf(TRACK_B),
            )
            db.execSQL(
                "INSERT INTO playlist_tracks (playlistId, position, trackUri) VALUES (1, 1, ?)",
                arrayOf(TRACK_A),
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, *LIBRARY_MIGRATIONS)

        db.query("SELECT name FROM playlists WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Drive", cursor.getString(0))
        }
        db.query("SELECT playlistId, position, trackId FROM playlist_tracks ORDER BY position")
            .use { cursor ->
                assertEquals(2, cursor.count)

                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals(TRACK_B, cursor.getString(2))

                assertTrue(cursor.moveToNext())
                assertEquals(1L, cursor.getLong(0))
                assertEquals(1, cursor.getInt(1))
                assertEquals(TRACK_A, cursor.getString(2))
            }
    }

    /**
     * Rebuilding a table is where a foreign key quietly goes missing, and this one is load-bearing:
     * without the cascade, deleting a playlist would leave its entries behind forever.
     */
    @Test
    fun migration3To4_keepsDeletingAPlaylistCascadingToItsEntries() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.insertTrack(TRACK_A, title = "Bad Day", metadataInferred = false)
            db.insertTrack(TRACK_B, title = "Yellow", metadataInferred = false)
            db.execSQL("INSERT INTO playlists (id, name, dateCreatedMs) VALUES (1, 'Drive', 1)")
            db.execSQL("INSERT INTO playlists (id, name, dateCreatedMs) VALUES (2, 'Keep', 2)")
            db.execSQL(
                "INSERT INTO playlist_tracks (playlistId, position, trackUri) VALUES (1, 0, ?)",
                arrayOf(TRACK_A),
            )
            db.execSQL(
                "INSERT INTO playlist_tracks (playlistId, position, trackUri) VALUES (2, 0, ?)",
                arrayOf(TRACK_B),
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, *LIBRARY_MIGRATIONS)

        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM playlists WHERE id = 1")

        db.query("SELECT playlistId, trackId FROM playlist_tracks").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals(2L, cursor.getLong(0))
            assertEquals(TRACK_B, cursor.getString(1))
        }
    }

    /**
     * The path a user who installed early actually takes, and the one that proves Room itself
     * accepts the result -- opening the database is what runs its identity-hash check.
     */
    @Test
    fun migratingAllTheWayFromVersion1LeavesADatabaseRoomWillOpen() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.insertTrack(TRACK_A, title = "Bad Day")
            db.execSQL(
                "INSERT INTO music_folders (uri, displayName, dateAddedMs) VALUES (?, 'Music', 1)",
                arrayOf(FOLDER_URI),
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 4, true, *LIBRARY_MIGRATIONS).close()

        val room = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            LibraryDatabase::class.java,
            TEST_DB,
        ).addMigrations(*LIBRARY_MIGRATIONS).build()
        helper.closeWhenFinished(room)

        val opened = room.openHelper.writableDatabase
        opened.query("SELECT id FROM tracks").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals(TRACK_A, cursor.getString(0))
        }
        // The granted folder has to survive too, or the library would look unconfigured.
        opened.query("SELECT uri FROM music_folders").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals(FOLDER_URI, cursor.getString(0))
        }
    }
}

/**
 * Inserts a track using the columns that exist at the given version: [metadataInferred] is null
 * for a version 1 database, which has no such column.
 */
private fun SupportSQLiteDatabase.insertTrack(
    uri: String,
    title: String,
    metadataInferred: Boolean? = null,
) {
    val columns = mutableListOf(
        "uri", "folderUri", "relativeDir", "title", "artist", "album", "albumArtist",
        "genre", "composer", "year", "trackNumber", "discNumber", "durationMs",
        "albumArtPath", "dateAddedMs",
    )
    val values = mutableListOf<Any?>(
        uri, FOLDER_URI, "Badly Drawn", title, "Artist", "Album", "Album Artist",
        "Genre", "Composer", 1998, 3, 1, 240_000L, "/data/art/cover.jpg", 1_700_000_000_000L,
    )
    if (metadataInferred != null) {
        columns += "metadataInferred"
        values += if (metadataInferred) 1 else 0
    }

    execSQL(
        "INSERT INTO tracks (${columns.joinToString(", ")}) " +
            "VALUES (${columns.joinToString(", ") { "?" }})",
        values.toTypedArray(),
    )
}
