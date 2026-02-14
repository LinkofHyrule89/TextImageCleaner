package com.ubermicrostudios.textimagecleaner.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Data class representing an item that has been moved to the internal trash.
 * These items are stored in the local Room database to track their original metadata
 * and where they are physically stored in the app's internal storage.
 */
@Entity(tableName = "trashed_items")
data class TrashedItem(
    /** The original content URI of the MMS part before it was deleted. */
    @PrimaryKey val uriString: String,
    
    /** The name of the file in the app's internal 'trash' directory. */
    val fileName: String,
    
    /** MIME type (e.g., image/jpeg, video/mp4). */
    val mimeType: String,
    
    /** The timestamp of the original MMS message. */
    val originalDate: Long,
    
    /** The timestamp when the item was moved to trash. */
    val trashedDate: Long,
    
    /** Size of the file in bytes. */
    val fileSize: Long,
    
    /** Optional: The sender of the original message. */
    val sender: String? = null
)
