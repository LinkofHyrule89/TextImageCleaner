package com.ubermicrostudios.textimagecleaner

import android.content.Context
import android.net.Uri
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaLoadAndSafetyTest {

    private lateinit var context: Context
    private lateinit var fixtures: MmsTestFixtures
    private lateinit var database: AppDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        fixtures = MmsTestFixtures(context)
        database = AppDatabase.getDatabase(context)
    }

    @Test
    fun loadMmsMediaFindsImageAndVideo() = runBlocking {
        val mms = fixtures.insertMms()
        val img = fixtures.insertImagePart(mms)
        val vid = fixtures.insertVideoPart(mms)
        fixtures.insertTextPart(mms, fixtures.uniqueBody())

        val loaded = MediaUtils.loadMmsMedia(context.contentResolver)
        val uris = loaded.map { it.uri.toString() }.toSet()
        assertTrue(img.toString() in uris)
        assertTrue(vid.toString() in uris)
        assertTrue(loaded.any { it.mimeType.startsWith("image/") })
        assertTrue(loaded.any { it.mimeType.startsWith("video/") })
    }

    @Test
    fun trashedUrisExcludedFromSubsequentLoadFilter() = runBlocking {
        val mms = fixtures.insertMms()
        val part = fixtures.insertImagePart(mms)
        fixtures.insertTextPart(mms, fixtures.uniqueBody())

        val file = File(context.cacheDir, "uris_${UUID.randomUUID()}.txt")
        file.writeText(part.toString())
        val worker = TestListenableWorkerBuilder<DeletionWorker>(
            context,
            workDataOf(
                DeletionWorker.KEY_URIS_FILE_PATH to file.absolutePath,
                DeletionWorker.KEY_DELETE_ATTACHMENTS_ONLY to false
            )
        ).build()
        assertEquals(ListenableWorker.Result.Success(), worker.doWork())

        val trashUris = database.trashDao().getAllUriStrings().toHashSet()
        assertTrue(part.toString() in trashUris)

        val loaded = MediaUtils.loadMmsMedia(context.contentResolver)
            .filter { it.uri.toString() !in trashUris }
        assertFalse(loaded.any { it.uri == part })

        // cleanup
        database.trashDao().getAllTrashedItems().first()
            .filter { it.uriString == part.toString() }
            .forEach {
                File(context.filesDir, "trash/${it.fileName}").delete()
                database.trashDao().delete(it)
            }
    }

    @Test
    fun deletingOneMessageDoesNotTouchControlMessage() = runBlocking {
        val control = fixtures.insertMms()
        val controlPart = fixtures.insertImagePart(control, "control.jpg")
        fixtures.insertTextPart(control, fixtures.uniqueBody("CONTROL"))

        val target = fixtures.insertMms()
        val targetPart = fixtures.insertImagePart(target, "target.jpg")
        fixtures.insertTextPart(target, fixtures.uniqueBody("TARGET"))

        val file = File(context.cacheDir, "uris_${UUID.randomUUID()}.txt")
        file.writeText(targetPart.toString())
        val worker = TestListenableWorkerBuilder<DeletionWorker>(
            context,
            workDataOf(
                DeletionWorker.KEY_URIS_FILE_PATH to file.absolutePath,
                DeletionWorker.KEY_DELETE_ATTACHMENTS_ONLY to true
            )
        ).build()
        assertEquals(ListenableWorker.Result.Success(), worker.doWork())

        assertFalse(fixtures.uriExists(targetPart))
        assertTrue(fixtures.uriExists(controlPart))
        assertTrue(fixtures.uriExists(control))
        assertTrue(fixtures.uriExists(target)) // attachments-only keeps parent
    }

    @Test
    fun dateRangeSnapshotIncludesOnlyInRangeItems() = runBlocking {
        val oldSec = (System.currentTimeMillis() / 1000) - 86_400L * 40
        val newSec = System.currentTimeMillis() / 1000

        val oldMms = fixtures.insertMms(oldSec)
        val oldPart = fixtures.insertImagePart(oldMms, "old.jpg")
        fixtures.insertTextPart(oldMms, fixtures.uniqueBody("OLD"))

        val newMms = fixtures.insertMms(newSec)
        val newPart = fixtures.insertImagePart(newMms, "new.jpg")
        fixtures.insertTextPart(newMms, fixtures.uniqueBody("NEW"))

        val loaded = MediaUtils.loadMmsMedia(context.contentResolver)
        val oldItem = loaded.find { it.uri == oldPart }
        val newItem = loaded.find { it.uri == newPart }
        assertTrue(oldItem != null && newItem != null)

        val mid = (oldItem!!.date + newItem!!.date) / 2
        val onlyOld = loaded.filter { it.date in 0..mid }.map { it.uri }
        assertTrue(oldPart in onlyOld)
        assertFalse(newPart in onlyOld)

        val onlyNew = loaded.filter { it.date in (mid + 1)..Long.MAX_VALUE }.map { it.uri }
        assertTrue(newPart in onlyNew)
        assertFalse(oldPart in onlyNew)
    }
}
