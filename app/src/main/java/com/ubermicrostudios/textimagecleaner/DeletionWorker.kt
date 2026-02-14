package com.ubermicrostudios.textimagecleaner

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
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
        const val KEY_TOTAL_COUNT = "total_count"
        const val KEY_DELETED_COUNT = "deleted_count"
        const val KEY_LAST_ITEM_INFO = "last_item_info"
    }

    override suspend fun doWork(): Result {
        val deleteEmpty = inputData.getBoolean(KEY_DELETE_EMPTY_MESSAGES, false)
        val deleteAttachmentsOnly = inputData.getBoolean(KEY_DELETE_ATTACHMENTS_ONLY, false)
        val filePath = inputData.getString(KEY_URIS_FILE_PATH)

        createNotificationChannels()
        setForeground(createForegroundInfo(0, 0, true))

        var totalVerifiedDeleted = 0

        return withContext(Dispatchers.IO) {
            try {
                if (deleteEmpty) {
                    val count = deleteEmptyMessages()
                    showCompletionNotification(count)
                } else if (filePath != null) {
                    val file = File(filePath)
                    if (file.exists()) {
                        val uris = file.readLines().map { Uri.parse(it) }
                        val total = uris.size
                        setForeground(createForegroundInfo(total, 0, false))

                        uris.forEachIndexed { index, uri ->
                            if (isStopped) return@forEachIndexed
                            
                            val itemInfo = getItemInfo(uri)
                            val trashed = trashItem(uri)
                            if (trashed) {
                                val success = if (deleteAttachmentsOnly) {
                                    deleteAttachmentSingle(uri)
                                } else {
                                    deleteMessageForPart(uri)
                                }
                                if (success) totalVerifiedDeleted++
                            }

                            if (index % 5 == 0 || index == total - 1) {
                                setForeground(createForegroundInfo(total, index + 1, false))
                                setProgress(workDataOf(
                                    KEY_TOTAL_COUNT to total, 
                                    KEY_DELETED_COUNT to totalVerifiedDeleted,
                                    KEY_LAST_ITEM_INFO to itemInfo
                                ))
                            }
                        }
                        file.delete()
                    }
                }
                showCompletionNotification(totalVerifiedDeleted) 
                Result.success()
            } catch (e: Exception) {
                Log.e("DeletionWorker", "Error deleting items", e)
                Result.failure()
            } finally {
                if (filePath != null) {
                    val file = File(filePath)
                    if (file.exists()) file.delete()
                }
            }
        }
    }

    private fun getItemInfo(uri: Uri): String {
        return try {
            // Quick check for display in log
            "Item ${uri.lastPathSegment}"
        } catch (e: Exception) {
            "Unknown Item"
        }
    }

    private suspend fun trashItem(uri: Uri): Boolean {
        return try {
            val contentResolver = applicationContext.contentResolver
            val trashedFile = File(applicationContext.filesDir, "trash/${UUID.randomUUID()}")
            trashedFile.parentFile?.mkdirs()

            var mimeType = "image/jpeg"
            val date = System.currentTimeMillis()
            var size = 0L

            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val ctCol = cursor.getColumnIndex(Telephony.Mms.Part.CONTENT_TYPE)
                    if (ctCol != -1) {
                        val type = cursor.getString(ctCol)
                        if (type != null) mimeType = type
                    }
                }
            }

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
                val item = TrashedItem(
                    uriString = uri.toString(),
                    fileName = trashedFile.name,
                    mimeType = mimeType,
                    originalDate = date,
                    trashedDate = System.currentTimeMillis(),
                    fileSize = size
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

    private fun deleteAttachmentSingle(uri: Uri): Boolean {
        return try {
            applicationContext.contentResolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
            false
        }
    }

    private fun deleteMessageForPart(uri: Uri): Boolean {
        return try {
            val partId = uri.lastPathSegment ?: return false
            var msgId: Long? = null
            val contentUri = Uri.parse("content://mms/part")
            
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

    private fun createForegroundInfo(total: Int, progress: Int, indeterminate: Boolean): ForegroundInfo {
        val title = "Trashing Media"
        val content = if (indeterminate) "Preparing..." else "Moving to Trash... ($progress / $total)"

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

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
             ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun showCompletionNotification(deletedCount: Int) {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = "Moved $deletedCount items to Trash."

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
    }

    private fun deleteEmptyMessages(): Int {
        val selection = "${Telephony.Sms.BODY} IS NULL OR ${Telephony.Sms.BODY} = ''"
        return applicationContext.contentResolver.delete(Telephony.Sms.CONTENT_URI, selection, null)
    }
}
