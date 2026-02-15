package com.ubermicrostudios.textimagecleaner

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.ubermicrostudios.textimagecleaner.data.AppDatabase
import com.ubermicrostudios.textimagecleaner.data.TrashedItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Instrumented tests to verify media operations: delete, trash, restore, and backup.
 * These tests interact with the system Telephony and MediaStore providers.
 */
@RunWith(AndroidJUnit4::class)
class DeletionWorkerTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = AppDatabase.getDatabase(context)
    }

    /**
     * Test Case: Verify "Move to Trash" (Standard Delete) and automated message text capture.
     * Ensures that when an item is trashed, its associated text message is saved in Room.
     */
    @Test
    fun testMoveToTrashWithMessage() = runBlocking {
        val testMessage = "Hello from Unit Test ${UUID.randomUUID()}"
        val mmsUri = insertMockMms()
        assertNotNull("MMS insertion failed. Ensure app is Default SMS in settings.", mmsUri)
        
        // Insert a multipart MMS with both image and text
        val partUri = insertMockMmsPart(mmsUri!!, "trash_test.jpg")
        assertNotNull("MMS part insertion failed", partUri)
        insertMockMmsTextPart(mmsUri, testMessage)

        // Execute background worker (Move to Trash mode)
        val result = runDeletionWorker(partUri!!, attachmentsOnly = false)
        assertEquals(ListenableWorker.Result.Success(), result)

        // Verify the database record contains the captured text
        val trashedItems = database.trashDao().getAllTrashedItems().first()
        val trashedItem = trashedItems.find { it.uriString == partUri.toString() }
        assertNotNull("Item should be in trash DB", trashedItem)
        assertEquals("Message body should be captured accurately", testMessage, trashedItem!!.messageBody)
        
        // Cleanup local test artifacts
        val trashFile = File(context.filesDir, "trash/${trashedItem.fileName}")
        if (trashFile.exists()) trashFile.delete()
        database.trashDao().delete(trashedItem)
    }

    /**
     * Test Case: Verify "Delete Permanently" (Attachment Only).
     * Validates that the trash is bypassed and only the media part is removed.
     */
    @Test
    fun testDeletePermanentlyNoTrash() = runBlocking {
        val mmsUri = insertMockMms()
        val partUri = insertMockMmsPart(mmsUri!!, "permanent_delete.jpg")

        // Execute background worker (Permanent Delete mode)
        val result = runDeletionWorker(partUri!!, attachmentsOnly = true)
        assertEquals(ListenableWorker.Result.Success(), result)

        // Verify original system states
        assertTrue("Original MMS Part should be gone", !uriExists(partUri))
        assertTrue("Parent MMS Message should still exist (empty)", uriExists(mmsUri))

        // Verify NO trash record was created
        val trashedItems = database.trashDao().getAllTrashedItems().first()
        val trashedItem = trashedItems.find { it.uriString == partUri.toString() }
        assertNull("Item should NOT be in the trash database", trashedItem)
    }

    /**
     * Test Case: Verify "Backup to Gallery" before deletion.
     * Confirms that a copy is successfully placed in public MediaStore before the original is lost.
     */
    @Test
    fun testBackupBeforeDelete() = runBlocking {
        val fileName = "backup_test_${UUID.randomUUID()}.jpg"
        val mmsUri = insertMockMms()
        val partUri = insertMockMmsPart(mmsUri!!, fileName)

        val urisFilePath = File(context.cacheDir, "test_uris_backup.txt")
        urisFilePath.writeText(partUri.toString())

        val worker = TestListenableWorkerBuilder<DeletionWorker>(
            context = context,
            inputData = workDataOf(
                DeletionWorker.KEY_URIS_FILE_PATH to urisFilePath.absolutePath,
                DeletionWorker.KEY_BACKUP_BEFORE_DELETE to true,
                DeletionWorker.KEY_DELETE_ATTACHMENTS_ONLY to false
            )
        ).build()

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.Success(), result)

        // Verify the backup exists in the public Images collection
        assertTrue("Backup should exist in MediaStore with correct name", verifyMediaExists("backup_$fileName"))
        
        // Also verify standard trashing occurred as a secondary step
        verifyTrashed(partUri!!)
    }

    /**
     * Test Case: Verify "Restore from Trash" back to Gallery.
     * Simulates a user taking an item out of the app's safety trash and making it public again.
     */
    @Test
    fun testRestoreFromTrash() = runBlocking {
        val uniqueName = "restore_test_${UUID.randomUUID()}.jpg"
        val trashFile = File(context.filesDir, "trash/$uniqueName")
        trashFile.parentFile?.mkdirs()
        FileOutputStream(trashFile).use { out ->
            Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 100, out)
        }

        // Mock a trash entry
        val trashedItem = TrashedItem(
            uriString = "content://mms/part/mock_restore_${UUID.randomUUID()}",
            fileName = uniqueName,
            mimeType = "image/jpeg",
            originalDate = System.currentTimeMillis(),
            trashedDate = System.currentTimeMillis(),
            fileSize = trashFile.length(),
            messageBody = "Restored context"
        )
        database.trashDao().insert(trashedItem)

        // Perform the restoration logic
        MediaUtils.restoreToGallery(context, trashedItem)

        // Verify successful export to MediaStore
        assertTrue("Restored item should exist in MediaStore", verifyMediaExists("Restored_$uniqueName"))

        trashFile.delete()
        database.trashDao().delete(trashedItem)
    }

    // --- Helpers for Test Verification and Mock Data Injection ---

    private fun uriExists(uri: Uri): Boolean {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { 
                it.moveToFirst() 
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun verifyMediaExists(displayName: String): Boolean {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(displayName)
        context.contentResolver.query(collection, null, selection, selectionArgs, null)?.use { cursor ->
            return cursor.count > 0
        }
        return false
    }

    private suspend fun runDeletionWorker(targetUri: Uri, attachmentsOnly: Boolean): ListenableWorker.Result {
        val urisFilePath = File(context.cacheDir, "test_uris.txt")
        urisFilePath.writeText(targetUri.toString())

        val worker = TestListenableWorkerBuilder<DeletionWorker>(
            context = context,
            inputData = workDataOf(
                DeletionWorker.KEY_URIS_FILE_PATH to urisFilePath.absolutePath,
                DeletionWorker.KEY_DELETE_ATTACHMENTS_ONLY to attachmentsOnly
            )
        ).build()

        return worker.doWork()
    }

    private suspend fun verifyTrashed(originalUri: Uri) {
        val trashedItems = database.trashDao().getAllTrashedItems().first()
        val trashedItem = trashedItems.find { it.uriString == originalUri.toString() }
        assertNotNull("Item should be recorded in trash DB", trashedItem)

        val trashFile = File(context.filesDir, "trash/${trashedItem!!.fileName}")
        assertTrue("File should exist in internal trash", trashFile.exists())
        
        // Cleanup
        trashFile.delete()
        database.trashDao().delete(trashedItem)
    }

    private fun insertMockMms(): Uri? {
        val values = ContentValues().apply {
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
            put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000)
            put(Telephony.Mms.CONTENT_TYPE, "application/vnd.wap.mms-message")
        }
        return context.contentResolver.insert(Telephony.Mms.CONTENT_URI, values)
    }

    private fun insertMockMmsPart(mmsUri: Uri, partName: String): Uri? {
        val mmsId = mmsUri.lastPathSegment
        val partUri = Uri.parse("content://mms/$mmsId/part")
        val values = ContentValues().apply {
            put(Telephony.Mms.Part.MSG_ID, mmsId)
            put(Telephony.Mms.Part.CONTENT_TYPE, "image/jpeg")
            put(Telephony.Mms.Part.NAME, partName)
            put(Telephony.Mms.Part.FILENAME, partName)
        }
        val inserted = context.contentResolver.insert(partUri, values)
        inserted?.let { uri ->
            context.contentResolver.openOutputStream(uri)?.use { 
                Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 100, it)
            }
        }
        return inserted
    }

    private fun insertMockMmsTextPart(mmsUri: Uri, text: String): Uri? {
        val mmsId = mmsUri.lastPathSegment
        val partUri = Uri.parse("content://mms/$mmsId/part")
        val values = ContentValues().apply {
            put(Telephony.Mms.Part.MSG_ID, mmsId)
            put(Telephony.Mms.Part.CONTENT_TYPE, "text/plain")
            put(Telephony.Mms.Part.TEXT, text)
        }
        return context.contentResolver.insert(partUri, values)
    }
}
