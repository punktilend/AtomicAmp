package com.atomic.atomicamp.app.library.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MusicFolderDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(folder: MusicFolder)

    @Delete
    suspend fun delete(folder: MusicFolder)

    @Query("SELECT * FROM music_folders ORDER BY dateAddedMs")
    fun all(): Flow<List<MusicFolder>>

    @Query("SELECT * FROM music_folders ORDER BY dateAddedMs")
    suspend fun allOnce(): List<MusicFolder>
}
