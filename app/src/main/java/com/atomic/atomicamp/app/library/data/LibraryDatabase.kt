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
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tracks ADD COLUMN metadataInferred INTEGER NOT NULL DEFAULT 0")
    }
}

@Database(entities = [Track::class, MusicFolder::class], version = 2, exportSchema = false)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun musicFolderDao(): MusicFolderDao

    companion object {
        @Volatile
        private var instance: LibraryDatabase? = null

        fun get(context: Context): LibraryDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LibraryDatabase::class.java,
                    "library.db",
                ).addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
