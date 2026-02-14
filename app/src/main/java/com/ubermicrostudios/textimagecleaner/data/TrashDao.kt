package com.ubermicrostudios.textimagecleaner.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrashDao {
    @Query("SELECT * FROM trashed_items ORDER BY trashedDate DESC")
    fun getAllTrashedItems(): Flow<List<TrashedItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: TrashedItem)

    @Delete
    suspend fun delete(item: TrashedItem)

    @Query("DELETE FROM trashed_items")
    suspend fun deleteAll()
    
    @Query("SELECT SUM(fileSize) FROM trashed_items")
    fun getTotalTrashedSize(): Flow<Long?>
}
