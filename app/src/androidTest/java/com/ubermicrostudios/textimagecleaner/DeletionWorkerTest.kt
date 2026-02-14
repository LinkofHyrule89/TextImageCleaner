package com.ubermicrostudios.textimagecleaner

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.ubermicrostudios.textimagecleaner.data.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented test to verify the DeletionWorker's ability to move system MMS media
 * to the app's internal trash and delete the original system records based on user settings.
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
     * Test Case: Verify that deleting the entire message removes both the attachment
     * and the parent message record from the system.
     */
    @Test
    fun testDeleteEntireMessage() = runBlocking {
        // 1. Setup mock data
        val mmsUri = insertMockMms()
        assertNotNull("MMS insertion failed. Ensure app is Default SMS app in emulator settings.", mmsUri)
        val partUri = insertMockMmsPart(mmsUri!!)
        assertNotNull("MMS part insertion failed", partUri)

        // 2. Execute worker with 'attachmentsOnly = false'
        val result = runDeletionWorker(partUri!!, attachmentsOnly = false)
        assertTrue("Worker result should be success", result is ListenableWorker.Result.Success)

        // 3. Verification: Part should be gone
        assertTrue("MMS Part should have been deleted", !uriExists(partUri))
        
        // 4. Verification: MMS message should also be gone
        assertTrue("Parent MMS message should have been deleted", !uriExists(mmsUri))

        // 5. Verification: Verify trashing logic worked
        verifyTrashed(partUri)
    }

    /**
     * Test Case: Verify that the 'Delete Attachments Only' setting removes the media
     * part but leaves the parent MMS message shell intact.
     */
    @Test
    fun testDeleteAttachmentOnly() = runBlocking {
        // 1. Setup mock data
        val mmsUri = insertMockMms()
        assertNotNull("MMS insertion failed", mmsUri)
        val partUri = insertMockMmsPart(mmsUri!!)
        assertNotNull("MMS part insertion failed", partUri)

        // 2. Execute worker with 'attachmentsOnly = true'
        val result = runDeletionWorker(partUri!!, attachmentsOnly = true)
        assertTrue("Worker result should be success", result is ListenableWorker.Result.Success)

        // 3. Verification: Part should be gone
        assertTrue("MMS Part should have been deleted", !uriExists(partUri))
        
        // 4. Verification: MMS message should STILL EXIST
        assertTrue("Parent MMS message should NOT have been deleted", uriExists(mmsUri))

        // 5. Verification: Verify trashing logic worked
        verifyTrashed(partUri)
    }

    /** Helper to check if a content URI still exists in the system. */
    private fun uriExists(uri: Uri): Boolean {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { 
                it.moveToFirst() 
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    /** Helper to build and run the DeletionWorker for a single URI. */
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

    /** Helper to verify that an item was correctly moved to the internal trash system. */
    private suspend fun verifyTrashed(originalUri: Uri) {
        val trashedItems = database.trashDao().getAllTrashedItems().first()
        val trashedItem = trashedItems.find { it.uriString == originalUri.toString() }
        assertNotNull("Item should be recorded in the trash database", trashedItem)

        val trashFile = File(context.filesDir, "trash/${trashedItem!!.fileName}")
        assertTrue("File should physically exist in the internal trash directory", trashFile.exists())
        assertTrue("Trashed file size should be greater than zero", trashFile.length() > 0)
        
        // Cleanup trashing artifacts to keep test idempotent
        trashFile.delete()
        database.trashDao().delete(trashedItem)
    }

    /** Helper to insert a dummy MMS record into the system provider. */
    private fun insertMockMms(): Uri? {
        val values = ContentValues().apply {
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
            put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000)
            put(Telephony.Mms.READ, 1)
            put(Telephony.Mms.SUBJECT, "Test Subject")
            put(Telephony.Mms.CONTENT_TYPE, "application/vnd.wap.mms-message")
        }
        return try {
            context.contentResolver.insert(Telephony.Mms.CONTENT_URI, values)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** Helper to insert a dummy media part and write actual bitmap bytes to it. */
    private fun insertMockMmsPart(mmsUri: Uri): Uri? {
        val mmsId = mmsUri.lastPathSegment
        val partUri = Uri.parse("content://mms/$mmsId/part")
        
        val values = ContentValues().apply {
            put(Telephony.Mms.Part.MSG_ID, mmsId)
            put(Telephony.Mms.Part.CONTENT_TYPE, "image/jpeg")
            put(Telephony.Mms.Part.NAME, "test_image.jpg")
            put(Telephony.Mms.Part.FILENAME, "test_image.jpg")
            put(Telephony.Mms.Part.CONTENT_ID, "<test_image>")
            put(Telephony.Mms.Part.CONTENT_LOCATION, "test_image.jpg")
        }
        
        val insertedPartUri = try {
            context.contentResolver.insert(partUri, values)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
        
        insertedPartUri?.let { uri ->
            try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        return insertedPartUri
    }
}
