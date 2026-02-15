package com.ubermicrostudios.textimagecleaner

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ubermicrostudios.textimagecleaner.data.AppDatabase
import com.ubermicrostudios.textimagecleaner.data.TrashedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Background worker responsible for the heavy lifting of processing media from system MMS storage.
 * It handles moving items to the internal trash, permanent deletion of attachments, and 
 * optional backups to public gallery storage.
 * Optimized for Android 15+ (API 35+) environment.
 */
class DeletionWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
    private val database = AppDatabase.getDatabase(appContext)
    private val trashDao = database.trashDao()

    companion object {
        const val CHANNEL_ID = "deletion_channel"
        const val COMPLETION_CHANNEL_ID = "deletion_completion_channel"
        const val NOTIFICATION_ID = 1
        const val COMPLETION_NOTIFICATION_ID = 2
        
        // Input Data Keys
        const val KEY_URIS_FILE_PATH = "uris_file_path"
        const val KEY_DELETE_ATTACHMENTS_ONLY = "delete_attachments_only"
        const val KEY_DELETE_EMPTY_MESSAGES = "delete_empty_messages"
        const val KEY_BACKUP_BEFORE_DELETE = "backup_before_delete"
        
        // Progress Keys
        const val KEY_TOTAL_COUNT = "total_count"
        const val KEY_DELETED_COUNT = "deleted_count"
        const val KEY_LAST_ITEM_INFO = "last_item_info"
    }

    override suspend fun doWork(): Result {
        val deleteEmpty = inputData.getBoolean(KEY_DELETE_EMPTY_MESSAGES, false)
        val deleteAttachmentsOnly = inputData.getBoolean(KEY_DELETE_ATTACHMENTS_ONLY, false)
        val backupBeforeDelete = inputData.getBoolean(KEY_BACKUP_BEFORE_DELETE, false)
        val filePath = inputData.getString(KEY_URIS_FILE_PATH)

        createNotificationChannels()
        // Initialize foreground notification for background execution
        setForeground(createForegroundInfo(0, 0, true, deleteAttachmentsOnly))

        var totalVerifiedDeleted = 0

        return withContext(Dispatchers.IO) {
            try {
                if (deleteEmpty) {
                    // Scenario 1: Just cleaning up empty message threads
                    val count = deleteEmptyMessages()
                    showCompletionNotification(count, false)
                } else if (filePath != null) {
                    // Scenario 2: Processing specific media items (Trash or Permanent Delete)
                    val file = File(filePath)
                    if (file.exists()) {
                        val uris = file.readLines().map { it.toUri() }
                        val total = uris.size
                        setForeground(createForegroundInfo(total, 0, false, deleteAttachmentsOnly))

                        uris.forEachIndexed { index, uri ->
                            if (isStopped) return@forEachIndexed
                            
                            val itemInfo = getItemInfo(uri)
                            
                            // Optional: Export a copy to the user's Pictures/Movies folder before removal
                            if (backupBeforeDelete) {
                                backupItem(uri)
                            }

                            val success = if (deleteAttachmentsOnly) {
                                // MODE: Permanent Delete (Bypasses Trash Can)
                                deleteAttachmentSingle(uri)
                            } else {
                                // MODE: Move to Trash (Safety First)
                                val trashed = trashItem(uri)
                                if (trashed) {
                                    deleteMessageForPart(uri)
                                } else {
                                    false
                                }
                            }
                            
                            if (success) totalVerifiedDeleted++

                            // Update UI and notification progress periodically
                            if ((index % 5 == 0) || (index == total - 1)) {
                                setForeground(createForegroundInfo(total, index + 1, false, deleteAttachmentsOnly))
                                setProgress(workDataOf(
                                    KEY_TOTAL_COUNT to total, 
                                    KEY_DELETED_COUNT to totalVerifiedDeleted,
                                    KEY_LAST_ITEM_INFO to itemInfo,
                                ))
                            }
                        }
                        file.delete()
                    }
                }
                showCompletionNotification(totalVerifiedDeleted, deleteAttachmentsOnly) 
                Result.success()
            } catch (e: Exception) {
                Log.e("DeletionWorker", "Error during cleanup", e)
                Result.failure()
            } finally {
                // Cleanup temporary URI file
                if (filePath != null) {
                    val file = File(filePath)
                    if (file.exists()) file.delete()
                }
            }
        }
    }

    /** Returns basic display info for the item being processed. */
    private fun getItemInfo(uri: Uri): String {
        return "Item ${uri.lastPathSegment}"
    }

    /**
     * Copies a media file from the system MMS provider to internal storage
     * and creates a record in the TrashedItem database, capturing associated text.
     */
    private suspend fun trashItem(uri: Uri): Boolean {
        return try {
            val contentResolver = applicationContext.contentResolver
            val trashedFile = File(applicationContext.filesDir, "trash/${UUID.randomUUID()}")
            trashedFile.parentFile?.mkdirs()

            var mimeType = "image/jpeg"
            val date = System.currentTimeMillis()
            var size = 0L
            var msgId: Long = -1

            // 1. Get metadata (MIME type and parent Message ID)
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val ctCol = cursor.getColumnIndex(Telephony.Mms.Part.CONTENT_TYPE)
                    if (ctCol != -1) {
                        val type = cursor.getString(ctCol)
                        if (type != null) mimeType = type
                    }
                    val msgIdCol = cursor.getColumnIndex(Telephony.Mms.Part.MSG_ID)
                    if (msgIdCol != -1) {
                        msgId = cursor.getLong(msgIdCol)
                    }
                }
            }
            
            // 2. Fetch associated text message body to preserve context in trash
            val bodyText = if (msgId != -1L) MediaUtils.getMmsText(contentResolver, msgId) else null

            // 3. Perform the physical copy to internal storage
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(trashedFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        size += bytesRead
                    }
                }
            }

            if (size > 0) {
                // 4. Save metadata to Room for UI retrieval
                val item = TrashedItem(
                    uriString = uri.toString(),
                    fileName = trashedFile.name,
                    mimeType = mimeType,
                    originalDate = date,
                    trashedDate = System.currentTimeMillis(),
                    fileSize = size,
                    messageBody = bodyText
                )
                trashDao.insert(item)
                true
            } else {
                trashedFile.delete()
                false
            }
        } catch (e: Exception) {
            Log.e("DeletionWorker", "Failed to trash item: $uri", e)
            false
        }
    }

    /**
     * Copies a media file from the system MMS provider to the public gallery
     * as a backup. Uses MediaStore API for Scoped Storage compliance.
     */
    private suspend fun backupItem(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val contentResolver = applicationContext.contentResolver
            var mimeType = "image/jpeg"
            var displayName = "backup_${uri.lastPathSegment}"

            // Extract metadata for the backup record
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val ctCol = cursor.getColumnIndex(Telephony.Mms.Part.CONTENT_TYPE)
                    if (ctCol != -1) {
                        val type = cursor.getString(ctCol)
                        if (type != null) mimeType = type
                    }
                    val nameCol = cursor.getColumnIndex(Telephony.Mms.Part.NAME)
                    if (nameCol != -1) {
                        val name = cursor.getString(nameCol)
                        if (!name.isNullOrBlank()) displayName = "backup_$name"
                    }
                }
            }

            // Define target location and properties in Scoped Storage
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TextImageCleaner_Backup")
                put(MediaStore.MediaColumns.IS_PENDING, 1) // Atomicity for Scoped Storage
            }

            val collection = if (mimeType.startsWith("video/")) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val destUri = contentResolver.insert(collection, values)
            destUri?.let { targetUri ->
                contentResolver.openOutputStream(targetUri)?.use { output ->
                    contentResolver.openInputStream(uri)?.use { input ->
                        input.copyTo(output)
                    }
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(targetUri, values, null, null)
                true
            } ?: false
        } catch (e: Exception) {
            Log.e("DeletionWorker", "Failed to backup item: $uri", e)
            false
        }
    }

    /** Deletes just the media attachment part, leaving the text message shell intact. */
    private fun deleteAttachmentSingle(uri: Uri): Boolean {
        return try {
            applicationContext.contentResolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
            false
        }
    }

    /** Deletes the entire MMS message that contains this media part. */
    private fun deleteMessageForPart(uri: Uri): Boolean {
        return try {
            val partId = uri.lastPathSegment ?: return false
            var msgId: Long? = null
            val contentUri = "content://mms/part".toUri()
            
            // Find the parent message ID for this media part
            applicationContext.contentResolver.query(
                contentUri,
                arrayOf(Telephony.Mms.Part.MSG_ID),
                "${Telephony.Mms.Part._ID} = ?",
                arrayOf(partId),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    msgId = cursor.getLong(0)
                }
            }

            // Perform the full message deletion
            msgId?.let {
                applicationContext.contentResolver.delete(
                    Telephony.Mms.CONTENT_URI,
                    "${Telephony.Mms._ID} = ?",
                    arrayOf(it.toString())
                ) > 0
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /** Creates the foreground info required to keep the worker running across app restarts. */
    private fun createForegroundInfo(total: Int, progress: Int, indeterminate: Boolean, isDeleteOnly: Boolean): ForegroundInfo {
        val title = if (isDeleteOnly) "Deleting Attachments" else "Trashing Media"
        val actionVerb = if (isDeleteOnly) "Deleting" else "Moving to Trash"
        val content = if (indeterminate) "Preparing..." else "$actionVerb... ($progress / $total)"

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_delete)
            .setOngoing(true)
            .setProgress(total, progress, indeterminate)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()

        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    /** Shows a final system notification when the operation completes. */
    private fun showCompletionNotification(deletedCount: Int, isDeleteOnly: Boolean) {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (isDeleteOnly) {
            "Permanently deleted $deletedCount attachments."
        } else {
            "Moved $deletedCount items to Trash."
        }

        val notification = NotificationCompat.Builder(applicationContext, COMPLETION_CHANNEL_ID)
            .setContentTitle("Cleanup Complete")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_delete)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .build()

        notificationManager.notify(COMPLETION_NOTIFICATION_ID, notification)
    }

    /** Initializes high and low importance notification channels. */
    private fun createNotificationChannels() {
        val progressChannel = NotificationChannel(
            CHANNEL_ID,
            "Deletion Progress",
            NotificationManager.IMPORTANCE_LOW
        )
        
        val completionChannel = NotificationChannel(
            COMPLETION_CHANNEL_ID,
            "Deletion Completion",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifies when message deletion is complete"
        }
        
        notificationManager.createNotificationChannels(listOf(progressChannel, completionChannel))
    }

    /** Deletes SMS message records that have no body text. */
    private fun deleteEmptyMessages(): Int {
        val selection = "${Telephony.Sms.BODY} IS NULL OR ${Telephony.Sms.BODY} = ''"
        return applicationContext.contentResolver.delete(Telephony.Sms.CONTENT_URI, selection, null)
    }
}
