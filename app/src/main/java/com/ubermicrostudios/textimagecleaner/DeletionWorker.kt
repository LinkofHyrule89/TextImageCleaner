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
 * Background worker for MMS media cleanup.
 *
 * Modes:
 * - attachments only: delete selected part URIs; leave parent MMS (text) intact.
 * - full cleanup (trash): copy selected parts to app trash, then delete the whole MMS
 *   only when every media part of that message was selected; otherwise part-only delete
 *   after trash (Option A — no silent sibling wipe).
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

        const val KEY_URIS_FILE_PATH = "uris_file_path"
        const val KEY_DELETE_ATTACHMENTS_ONLY = "delete_attachments_only"
        const val KEY_DELETE_EMPTY_MESSAGES = "delete_empty_messages"
        const val KEY_BACKUP_BEFORE_DELETE = "backup_before_delete"
        const val KEY_BACKUP_FOLDER_NAME = "backup_folder_name"

        const val KEY_TOTAL_COUNT = "total_count"
        const val KEY_DELETED_COUNT = "deleted_count"
        const val KEY_LAST_ITEM_INFO = "last_item_info"

        const val DEFAULT_BACKUP_FOLDER = "TextImageCleaner_Backup"
    }

    override suspend fun doWork(): Result {
        val deleteEmpty = inputData.getBoolean(KEY_DELETE_EMPTY_MESSAGES, false)
        val deleteAttachmentsOnly = inputData.getBoolean(KEY_DELETE_ATTACHMENTS_ONLY, false)
        val backupBeforeDelete = inputData.getBoolean(KEY_BACKUP_BEFORE_DELETE, false)
        val backupFolderName = inputData.getString(KEY_BACKUP_FOLDER_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_BACKUP_FOLDER
        val filePath = inputData.getString(KEY_URIS_FILE_PATH)

        createNotificationChannels()
        setForeground(createForegroundInfo(0, 0, true, deleteAttachmentsOnly))

        return withContext(Dispatchers.IO) {
            try {
                if (deleteEmpty) {
                    val count = deleteEmptyMessages()
                    showCompletionNotification(count, isDeleteOnly = false, emptyMessages = true)
                    return@withContext Result.success()
                }

                if (filePath == null) return@withContext Result.success()

                val file = File(filePath)
                if (!file.canonicalPath.startsWith(applicationContext.cacheDir.canonicalPath)) {
                    Log.e("DeletionWorker", "URI list path outside cacheDir: $filePath")
                    return@withContext Result.failure()
                }
                if (!file.exists()) return@withContext Result.success()

                val uris = file.readLines().mapNotNull { line ->
                    line.trim().takeIf { it.isNotEmpty() }?.toUri()
                }
                file.delete()

                val total = uris.size
                if (total == 0) {
                    showCompletionNotification(0, deleteAttachmentsOnly)
                    return@withContext Result.success()
                }

                setForeground(createForegroundInfo(total, 0, false, deleteAttachmentsOnly))

                // Resolve msgId per part and group for safe full-message delete.
                val resolved = uris.mapNotNull { uri ->
                    val msgId = resolveMsgId(uri) ?: return@mapNotNull null
                    uri to msgId
                }
                val byMsgId = resolved.groupBy({ it.second }, { it.first })

                // Precompute remaining media part counts for Option A.
                val allMediaPartsByMsg = byMsgId.keys.associateWith { msgId ->
                    listMediaPartIds(msgId)
                }

                var processed = 0
                var successCount = 0

                for ((msgId, partUris) in byMsgId) {
                    if (isStopped) break

                    val allMediaIds = allMediaPartsByMsg[msgId].orEmpty()
                    val selectedIds = partUris.mapNotNull { it.lastPathSegment }.toSet()
                    val selectedAllMedia =
                        allMediaIds.isNotEmpty() && allMediaIds.all { it in selectedIds }

                    var allSelectedTrashed = true
                    for (uri in partUris) {
                        if (isStopped) break

                        if (backupBeforeDelete) {
                            backupItem(uri, backupFolderName)
                        }

                        val ok = if (deleteAttachmentsOnly) {
                            deleteAttachmentSingle(uri)
                        } else {
                            val trashed = trashItem(uri, msgId)
                            if (!trashed) {
                                allSelectedTrashed = false
                                false
                            } else if (!selectedAllMedia) {
                                // Partial selection: trash copy + delete part only (keep text/siblings).
                                deleteAttachmentSingle(uri)
                            } else {
                                // Full media selection: copy only; whole MMS deleted once below.
                                true
                            }
                        }

                        if (ok) successCount++
                        processed++
                        reportProgress(total, processed, successCount, uri)
                    }

                    // Full cleanup only when every media part of this MMS was selected and trashed.
                    if (!deleteAttachmentsOnly && selectedAllMedia && allSelectedTrashed && !isStopped) {
                        deleteMmsMessage(msgId)
                    }
                }

                showCompletionNotification(successCount, deleteAttachmentsOnly)
                Result.success()
            } catch (e: Exception) {
                Log.e("DeletionWorker", "Error during cleanup", e)
                Result.failure()
            } finally {
                if (filePath != null) {
                    val f = File(filePath)
                    if (f.exists()) f.delete()
                }
            }
        }
    }

    private suspend fun reportProgress(total: Int, processed: Int, successCount: Int, uri: Uri) {
        if (processed % 5 == 0 || processed == total) {
            setForeground(
                createForegroundInfo(
                    total,
                    processed,
                    indeterminate = false,
                    isDeleteOnly = inputData.getBoolean(KEY_DELETE_ATTACHMENTS_ONLY, false)
                )
            )
            setProgress(
                workDataOf(
                    KEY_TOTAL_COUNT to total,
                    KEY_DELETED_COUNT to successCount,
                    KEY_LAST_ITEM_INFO to (uri.lastPathSegment ?: uri.toString()),
                )
            )
        }
    }

    private fun resolveMsgId(uri: Uri): Long? {
        return try {
            applicationContext.contentResolver.query(
                uri,
                arrayOf(Telephony.Mms.Part.MSG_ID),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
        } catch (e: Exception) {
            Log.e("DeletionWorker", "resolveMsgId failed: $uri", e)
            null
        }
    }

    /** All image/video part IDs for an MMS message. */
    private fun listMediaPartIds(msgId: Long): Set<String> {
        val ids = mutableSetOf<String>()
        val selection =
            "${Telephony.Mms.Part.MSG_ID} = ? AND (" +
                "${Telephony.Mms.Part.CONTENT_TYPE} LIKE 'image/%' OR " +
                "${Telephony.Mms.Part.CONTENT_TYPE} LIKE 'video/%')"
        try {
            applicationContext.contentResolver.query(
                "content://mms/part".toUri(),
                arrayOf(Telephony.Mms.Part._ID),
                selection,
                arrayOf(msgId.toString()),
                null
            )?.use { cursor ->
                val col = cursor.getColumnIndexOrThrow(Telephony.Mms.Part._ID)
                while (cursor.moveToNext()) {
                    ids.add(cursor.getLong(col).toString())
                }
            }
        } catch (e: Exception) {
            Log.e("DeletionWorker", "listMediaPartIds failed: $msgId", e)
        }
        return ids
    }

    private suspend fun trashItem(uri: Uri, msgId: Long): Boolean {
        return try {
            val contentResolver = applicationContext.contentResolver
            val trashedFile = File(applicationContext.filesDir, "trash/${UUID.randomUUID()}")
            trashedFile.parentFile?.mkdirs()

            var mimeType = "image/jpeg"
            var size = 0L
            val originalDate = resolveMmsDate(msgId)

            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val ctCol = cursor.getColumnIndex(Telephony.Mms.Part.CONTENT_TYPE)
                    if (ctCol != -1) {
                        cursor.getString(ctCol)?.let { mimeType = it }
                    }
                }
            }

            val bodyText = MediaUtils.getMmsText(contentResolver, msgId)

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
                trashDao.insert(
                    TrashedItem(
                        uriString = uri.toString(),
                        fileName = trashedFile.name,
                        mimeType = mimeType,
                        originalDate = originalDate,
                        trashedDate = System.currentTimeMillis(),
                        fileSize = size,
                        messageBody = bodyText
                    )
                )
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

    private fun resolveMmsDate(msgId: Long): Long {
        return try {
            applicationContext.contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf(Telephony.Mms.DATE),
                "${Telephony.Mms._ID} = ?",
                arrayOf(msgId.toString()),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val raw = cursor.getLong(0)
                    if (raw < 10_000_000_000L) raw * 1000 else raw
                } else {
                    System.currentTimeMillis()
                }
            } ?: System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }

    private suspend fun backupItem(uri: Uri, folderName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val contentResolver = applicationContext.contentResolver
            var mimeType = "image/jpeg"
            var displayName = "backup_${uri.lastPathSegment}"
            val album = folderName.ifBlank { DEFAULT_BACKUP_FOLDER }

            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val ctCol = cursor.getColumnIndex(Telephony.Mms.Part.CONTENT_TYPE)
                    if (ctCol != -1) cursor.getString(ctCol)?.let { mimeType = it }
                    val nameCol = cursor.getColumnIndex(Telephony.Mms.Part.NAME)
                    if (nameCol != -1) {
                        val name = cursor.getString(nameCol)
                        if (!name.isNullOrBlank()) displayName = "backup_$name"
                    }
                }
            }

            val isVideo = mimeType.startsWith("video/")
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    if (isVideo) {
                        Environment.DIRECTORY_MOVIES + "/$album"
                    } else {
                        Environment.DIRECTORY_PICTURES + "/$album"
                    }
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val collection = if (isVideo) {
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

    private fun deleteAttachmentSingle(uri: Uri): Boolean {
        return try {
            applicationContext.contentResolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
            Log.e("DeletionWorker", "deleteAttachmentSingle failed: $uri", e)
            false
        }
    }

    private fun deleteMmsMessage(msgId: Long): Boolean {
        return try {
            applicationContext.contentResolver.delete(
                Telephony.Mms.CONTENT_URI,
                "${Telephony.Mms._ID} = ?",
                arrayOf(msgId.toString())
            ) > 0
        } catch (e: Exception) {
            Log.e("DeletionWorker", "deleteMmsMessage failed: $msgId", e)
            false
        }
    }

    private fun createForegroundInfo(
        total: Int,
        progress: Int,
        indeterminate: Boolean,
        isDeleteOnly: Boolean
    ): ForegroundInfo {
        val title = if (isDeleteOnly) "Deleting Attachments" else "Trashing Media"
        val actionVerb = if (isDeleteOnly) "Deleting" else "Moving to Trash"
        val content = if (indeterminate) "Preparing..." else "$actionVerb... ($progress / $total)"

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
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

        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun showCompletionNotification(
        deletedCount: Int,
        isDeleteOnly: Boolean,
        emptyMessages: Boolean = false
    ) {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = when {
            emptyMessages -> "Removed $deletedCount empty SMS records."
            isDeleteOnly -> "Permanently deleted $deletedCount attachments."
            else -> "Moved $deletedCount items to Trash."
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

    private fun deleteEmptyMessages(): Int {
        val selection = "${Telephony.Sms.BODY} IS NULL OR ${Telephony.Sms.BODY} = ''"
        return applicationContext.contentResolver.delete(Telephony.Sms.CONTENT_URI, selection, null)
    }
}
