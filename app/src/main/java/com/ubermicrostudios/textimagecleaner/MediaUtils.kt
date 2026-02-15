package com.ubermicrostudios.textimagecleaner

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.provider.Telephony
import com.ubermicrostudios.textimagecleaner.data.TrashedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/**
 * Utility functions for media and MMS operations shared between the UI, background workers, and tests.
 * Refactored for Android 15+ (API 35+).
 */
object MediaUtils {

    /** 
     * Moves an item from the app's internal trash folder back to the public gallery storage.
     * Note: This restores visibility in external Photo/Gallery apps but does not reinject it into SMS.
     * Uses MediaStore API for Scoped Storage compliance.
     */
    suspend fun restoreToGallery(context: Context, item: TrashedItem) = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, "trash/${item.fileName}")
        if (!file.exists()) return@withContext

        // Prepare metadata for public insertion in Scoped Storage
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Restored_${item.fileName}")
            put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TextImageCleaner")
            put(MediaStore.MediaColumns.IS_PENDING, 1) // Scoped storage lock
        }

        // Determine correct collection based on MIME type
        val collection = if (item.mimeType.startsWith("video/")) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        
        val uri = context.contentResolver.insert(collection, values)
        
        uri?.let { destUri ->
            // Perform actual file stream copy
            context.contentResolver.openOutputStream(destUri)?.use { out ->
                FileInputStream(file).use { it.copyTo(out) }
            }
            // Unlock file for other apps to see (Clear IS_PENDING)
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(destUri, values, null, null)
        }
    }

    /** 
     * Queries the system Telephony provider to find all media attachments (Images and Videos)
     * currently stored in MMS messages.
     */
    suspend fun loadMmsMedia(contentResolver: ContentResolver): List<MediaItem> = withContext(Dispatchers.IO) {
        val messageDates = mutableMapOf<Long, Long>()
        
        // 1. Map MMS message IDs to their timestamps (handles second/millisecond conversion)
        contentResolver.query(Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE), null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(Telephony.Mms._ID)
            val dateCol = cursor.getColumnIndexOrThrow(Telephony.Mms.DATE)
            while (cursor.moveToNext()) {
                val rawDate = cursor.getLong(dateCol)
                // Standardize to millisecond timestamp
                messageDates[cursor.getLong(idCol)] = if (rawDate < 10_000_000_000L) rawDate * 1000 else rawDate
            }
        }

        val mediaItems = mutableListOf<MediaItem>()
        
        // 2. Query all 'parts' that are images or videos
        val selection = "${Telephony.Mms.Part.CONTENT_TYPE} LIKE 'image/%' OR ${Telephony.Mms.Part.CONTENT_TYPE} LIKE 'video/%'"
        contentResolver.query(Telephony.Mms.CONTENT_URI.buildUpon().appendPath("part").build(), arrayOf(Telephony.Mms.Part._ID, Telephony.Mms.Part.MSG_ID, Telephony.Mms.Part.CONTENT_TYPE), selection, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(Telephony.Mms.Part._ID)
            val msgIdCol = cursor.getColumnIndexOrThrow(Telephony.Mms.Part.MSG_ID)
            val typeCol = cursor.getColumnIndexOrThrow(Telephony.Mms.Part.CONTENT_TYPE)
            while (cursor.moveToNext()) {
                val partId = cursor.getLong(idCol)
                val msgId = cursor.getLong(msgIdCol)
                val date = messageDates[msgId] ?: 0L
                val uri = Telephony.Mms.CONTENT_URI.buildUpon().appendPath("part").appendPath(partId.toString()).build()
                
                // Fetch associated text message (body) for this specific MMS
                val body = getMmsText(contentResolver, msgId)
                
                // Efficiently determine file size using AssetFileDescriptor
                val size = try {
                    contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
                } catch (e: Exception) {
                    0L
                }
                
                mediaItems.add(MediaItem(uri, date, cursor.getString(typeCol), if (size < 0) 0L else size, body))
            }
        }
        // Return sorted newest first for the UI grid
        mediaItems.sortedByDescending { it.date }
    }

    /** 
     * Scans an MMS message to find its associated plain text body.
     * MMS messages store text as separate parts alongside media.
     */
    fun getMmsText(contentResolver: ContentResolver, msgId: Long): String? {
        val selection = "${Telephony.Mms.Part.MSG_ID} = ? AND ${Telephony.Mms.Part.CONTENT_TYPE} = ?"
        val selectionArgs = arrayOf(msgId.toString(), "text/plain")
        contentResolver.query(Telephony.Mms.CONTENT_URI.buildUpon().appendPath("part").build(), arrayOf(Telephony.Mms.Part.TEXT), selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0)
            }
        }
        return null
    }
}
