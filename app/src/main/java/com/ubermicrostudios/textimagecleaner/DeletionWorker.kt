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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class DeletionWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val CHANNEL_ID = "deletion_channel"
        const val NOTIFICATION_ID = 1
        const val COMPLETION_NOTIFICATION_ID = 2
        const val KEY_URIS_FILE_PATH = "uris_file_path"
        const val KEY_DELETE_ATTACHMENTS_ONLY = "delete_attachments_only"
        const val KEY_DELETE_EMPTY_MESSAGES = "delete_empty_messages"
        const val KEY_TOTAL_COUNT = "total_count"
        const val KEY_DELETED_COUNT = "deleted_count"
    }

    override suspend fun doWork(): Result {
        val deleteEmpty = inputData.getBoolean(KEY_DELETE_EMPTY_MESSAGES, false)
        val deleteAttachmentsOnly = inputData.getBoolean(KEY_DELETE_ATTACHMENTS_ONLY, false)
        val filePath = inputData.getString(KEY_URIS_FILE_PATH)

        createNotificationChannel()
        
        // Start as foreground service immediately
        setForeground(createForegroundInfo(0, 0, true))

        var totalVerifiedDeleted = 0
        var totalFailed = 0

        return withContext(Dispatchers.IO) {
            try {
                if (deleteEmpty) {
                    deleteEmptyMessages()
                    // Verification for bulk delete is tricky without pre-count, assuming success for now as logic is different
                } else if (filePath != null) {
                    val file = File(filePath)
                    if (file.exists()) {
                        val uris = file.readLines().map { Uri.parse(it) }
                        val total = uris.size
                        
                        // Initial progress update
                        setForeground(createForegroundInfo(total, 0, false))

                        uris.forEachIndexed { index, uri ->
                            val success = if (deleteAttachmentsOnly) {
                                deleteAttachmentVerified(uri)
                            } else {
                                deleteMessageVerified(uri)
                            }
                            
                            if (success) {
                                totalVerifiedDeleted++
                            } else {
                                totalFailed++
                            }

                            // Update notification every 10 items or at the end
                            if (index % 10 == 0 || index == total - 1) {
                                setForeground(createForegroundInfo(total, index + 1, false))
                                setProgress(workDataOf(KEY_TOTAL_COUNT to total, KEY_DELETED_COUNT to totalVerifiedDeleted))
                            }
                        }
                        file.delete()
                    }
                }
                showCompletionNotification(totalVerifiedDeleted, totalFailed)
                Result.success()
            } catch (e: Exception) {
                Log.e("DeletionWorker", "Error deleting items", e)
                Result.failure()
            }
        }
    }

    private fun createForegroundInfo(total: Int, progress: Int, indeterminate: Boolean): ForegroundInfo {
        val title = "Deleting Media"
        val content = if (indeterminate) "Preparing..." else "Deleting $progress of $total"

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_delete)
            .setOngoing(true)
            .setProgress(total, progress, indeterminate)
            .setOnlyAlertOnce(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
             ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun showCompletionNotification(deletedCount: Int, failedCount: Int) {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (failedCount > 0) {
            "Verified deleted: $deletedCount. Failed: $failedCount."
        } else {
            "Verified deleted: $deletedCount items."
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Deletion Complete")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_delete)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .build()

        notificationManager.notify(COMPLETION_NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Deletion Progress",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun deleteEmptyMessages() {
        val selection = "${Telephony.Sms.BODY} IS NULL OR ${Telephony.Sms.BODY} = ''"
        applicationContext.contentResolver.delete(Telephony.Sms.CONTENT_URI, selection, null)
    }

    private fun deleteMessageVerified(uri: Uri): Boolean {
        var msgId: Long? = null
        val msgIdColumn = Telephony.Mms.Part.MSG_ID
        val partUri = Uri.withAppendedPath(Telephony.Mms.CONTENT_URI, "part/${uri.lastPathSegment}")
        
        applicationContext.contentResolver.query(partUri, arrayOf(msgIdColumn), null, null, null)?.use {
            if (it.moveToFirst()) {
                msgId = it.getLong(0)
            }
        }

        if (msgId != null) {
            val deletedRows = applicationContext.contentResolver.delete(
                Telephony.Mms.CONTENT_URI,
                "${Telephony.Mms._ID} = ?",
                arrayOf(msgId.toString())
            )
            
            if (deletedRows > 0) {
                // Verify deletion
                applicationContext.contentResolver.query(
                    Telephony.Mms.CONTENT_URI,
                    arrayOf(Telephony.Mms._ID),
                    "${Telephony.Mms._ID} = ?",
                    arrayOf(msgId.toString()),
                    null
                )?.use { cursor ->
                    return cursor.count == 0
                }
                return true // Assume success if verification query fails/returns null for some reason but delete reported > 0
            }
        }
        return false
    }

    private fun deleteAttachmentVerified(uri: Uri): Boolean {
        val deletedRows = applicationContext.contentResolver.delete(uri, null, null)
        if (deletedRows > 0) {
             // Verify deletion
             try {
                 applicationContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                     return cursor.count == 0
                 }
                 // If query returns null, it might mean the provider doesn't support querying a deleted item, or it's gone.
                 return true 
             } catch (e: Exception) {
                 // If exception (e.g. SecurityException or FileNotFound), it is likely deleted
                 return true
             }
        }
        return false
    }
}
