package com.ubermicrostudios.textimagecleaner

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.ubermicrostudios.textimagecleaner.data.AppDatabase
import com.ubermicrostudios.textimagecleaner.testutil.MmsTestFixtures
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeletionWorkerTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var fixtures: MmsTestFixtures

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = AppDatabase.getDatabase(context)
        fixtures = MmsTestFixtures(context)
    }

    @Test
    fun trashCapturesMessageBodyAndFile() = runBlocking {
        val body = fixtures.uniqueBody()
        val mms = fixtures.insertMms()
        val part = fixtures.insertImagePart(mms)
        fixtures.insertTextPart(mms, body)

        assertEquals(ListenableWorker.Result.Success(), runWorker(listOf(part), attachmentsOnly = false))

        val trashed = database.trashDao().getAllTrashedItems().first()
            .find { it.uriString == part.toString() }
        assertNotNull(trashed)
        assertEquals(body, trashed!!.messageBody)
        assertTrue(File(context.filesDir, "trash/${trashed.fileName}").exists())
        cleanupTrash(trashed.uriString)
    }

    @Test
    fun attachmentsOnlyDeletesPartKeepsParentAndNoTrash() = runBlocking {
        val mms = fixtures.insertMms()
        val part = fixtures.insertImagePart(mms)
        fixtures.insertTextPart(mms, fixtures.uniqueBody())

        assertEquals(ListenableWorker.Result.Success(), runWorker(listOf(part), attachmentsOnly = true))

        assertFalse(fixtures.uriExists(part))
        assertTrue(fixtures.uriExists(mms))
        assertNull(
            database.trashDao().getAllTrashedItems().first()
                .find { it.uriString == part.toString() }
        )
    }

    @Test
    fun fullCleanupAllMediaDeletesMessage() = runBlocking {
        val mms = fixtures.insertMms()
        val a = fixtures.insertImagePart(mms, "full_a.jpg")
        val b = fixtures.insertVideoPart(mms, "full_b.mp4")
        fixtures.insertTextPart(mms, fixtures.uniqueBody())

        assertEquals(
            ListenableWorker.Result.Success(),
            runWorker(listOf(a, b), attachmentsOnly = false)
        )
        assertFalse(fixtures.uriExists(mms))
        cleanupTrash(a.toString(), b.toString())
    }

    @Test
    fun fullCleanupPartialKeepsSiblingAndMessage() = runBlocking {
        val mms = fixtures.insertMms()
        val a = fixtures.insertImagePart(mms, "part_a.jpg")
        val b = fixtures.insertImagePart(mms, "part_b.jpg")
        fixtures.insertTextPart(mms, fixtures.uniqueBody())

        assertEquals(ListenableWorker.Result.Success(), runWorker(listOf(a), attachmentsOnly = false))

        assertTrue(fixtures.uriExists(mms))
        assertTrue(fixtures.uriExists(b))
        assertFalse(fixtures.uriExists(a))
        cleanupTrash(a.toString())
    }

    @Test
    fun backupBeforeTrashWritesMediaStore() = runBlocking {
        val fileName = "backup_${UUID.randomUUID()}.jpg"
        val mms = fixtures.insertMms()
        val part = fixtures.insertImagePart(mms, fileName)

        val urisFile = File(context.cacheDir, "uris_${UUID.randomUUID()}.txt")
        urisFile.writeText(part.toString())
        val worker = TestListenableWorkerBuilder<DeletionWorker>(
            context = context,
            inputData = workDataOf(
                DeletionWorker.KEY_URIS_FILE_PATH to urisFile.absolutePath,
                DeletionWorker.KEY_BACKUP_BEFORE_DELETE to true,
                DeletionWorker.KEY_DELETE_ATTACHMENTS_ONLY to false,
                DeletionWorker.KEY_BACKUP_FOLDER_NAME to "TextImageCleaner_Backup"
            )
        ).build()
        assertEquals(ListenableWorker.Result.Success(), worker.doWork())
        assertTrue(mediaExists("backup_$fileName"))
        cleanupTrash(part.toString())
    }

    @Test
    fun multiUriFirstPassProcessesAll() = runBlocking {
        val mms = fixtures.insertMms()
        val parts = (1..5).map { fixtures.insertImagePart(mms, "batch_$it.jpg") }
        fixtures.insertTextPart(mms, fixtures.uniqueBody())

        assertEquals(
            ListenableWorker.Result.Success(),
            runWorker(parts, attachmentsOnly = true)
        )
        parts.forEach { assertFalse("part should be gone: $it", fixtures.uriExists(it)) }
        assertTrue(fixtures.uriExists(mms))
    }

    private suspend fun runWorker(
        uris: List<Uri>,
        attachmentsOnly: Boolean
    ): ListenableWorker.Result {
        val file = File(context.cacheDir, "uris_${UUID.randomUUID()}.txt")
        file.writeText(uris.joinToString("\n") { it.toString() })
        val worker = TestListenableWorkerBuilder<DeletionWorker>(
            context = context,
            inputData = workDataOf(
                DeletionWorker.KEY_URIS_FILE_PATH to file.absolutePath,
                DeletionWorker.KEY_DELETE_ATTACHMENTS_ONLY to attachmentsOnly
            )
        ).build()
        return worker.doWork()
    }

    private suspend fun cleanupTrash(vararg uriStrings: String) {
        val all = database.trashDao().getAllTrashedItems().first()
        uriStrings.forEach { u ->
            all.filter { it.uriString == u }.forEach {
                File(context.filesDir, "trash/${it.fileName}").delete()
                database.trashDao().delete(it)
            }
        }
    }

    private fun mediaExists(displayName: String): Boolean {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        context.contentResolver.query(
            collection,
            null,
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(displayName),
            null
        )?.use { return it.count > 0 }
        return false
    }
}
