package com.ubermicrostudios.textimagecleaner.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for interacting with the trashed_items table.
 */
@Dao
interface TrashDao {
    /**
     * Retrieves all items in the trash, sorted by the date they were trashed (newest first).
     * Returns a Flow for reactive UI updates.
     */
    @Query("SELECT * FROM trashed_items ORDER BY trashedDate DESC")
    fun getAllTrashedItems(): Flow<List<TrashedItem>>

    /**
     * Inserts a new trashed item record. Replaces if URI already exists.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: TrashedItem)

    /**
     * Removes a specific item record from the database.
     */
    @Delete
    suspend fun delete(item: TrashedItem)

    /**
     * Deletes all records from the trashed_items table (Empty Trash).
     */
    @Query("DELETE FROM trashed_items")
    suspend fun deleteAll()
    
    /**
     * Calculates the total size of all items currently in the trash.
     */
    @Query("SELECT SUM(fileSize) FROM trashed_items")
    fun getTotalTrashedSize(): Flow<Long?>
}
