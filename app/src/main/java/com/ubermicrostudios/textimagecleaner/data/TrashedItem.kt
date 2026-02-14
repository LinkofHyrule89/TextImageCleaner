package com.ubermicrostudios.textimagecleaner.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trashed_items")
data class TrashedItem(
    @PrimaryKey val uriString: String,
    val fileName: String,
    val mimeType: String,
    val originalDate: Long,
    val trashedDate: Long,
    val fileSize: Long,
    val sender: String? = null
)
