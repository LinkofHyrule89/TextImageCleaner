package com.ubermicrostudios.textimagecleaner.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Data class representing an item that has been moved to the internal safety trash.
 * These items are stored in the local Room database to track their original metadata
 * and where they are physically stored in the app's internal storage, allowing for 
 * restoration even after the original system MMS records are deleted.
 */
@Entity(tableName = "trashed_items")
data class TrashedItem(
    /** The original content URI string of the MMS part before it was deleted. */
    @PrimaryKey val uriString: String,
    
    /** The name of the unique file in the app's internal 'trash' directory. */
    val fileName: String,
    
    /** MIME type (e.g., image/jpeg, video/mp4) used for rendering and restoration. */
    val mimeType: String,
    
    /** The timestamp of the original MMS message. */
    val originalDate: Long,
    
    /** The timestamp when the user moved this item to trash. */
    val trashedDate: Long,
    
    /** Size of the file in bytes, used to calculate space savings. */
    val fileSize: Long,
    
    /** The associated text message body captured at the moment of trashing. */
    val messageBody: String? = null,
    
    /** Optional metadata: The sender of the original message. */
    val sender: String? = null
)
