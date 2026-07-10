package com.ubermicrostudios.textimagecleaner

import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ubermicrostudios.textimagecleaner.data.AppDatabase
import com.ubermicrostudios.textimagecleaner.data.TrashedItem
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RestoreTrashTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = AppDatabase.getDatabase(context)
    }

    @Test
    fun restoreToGalleryAndPermanentDelete() = runBlocking {
        val name = "restore_${UUID.randomUUID()}.jpg"
        val trashFile = File(context.filesDir, "trash/$name")
        trashFile.parentFile?.mkdirs()
        FileOutputStream(trashFile).use { out ->
            Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
                .compress(Bitmap.CompressFormat.JPEG, 90, out)
        }

        val item = TrashedItem(
            uriString = "content://mms/part/test_${UUID.randomUUID()}",
            fileName = name,
            mimeType = "image/jpeg",
            originalDate = System.currentTimeMillis() - 86_400_000,
            trashedDate = System.currentTimeMillis(),
            fileSize = trashFile.length(),
            messageBody = "restored body"
        )
        database.trashDao().insert(item)

        MediaUtils.restoreToGallery(context, item)
        assertTrue(mediaExists("Restored_$name"))

        database.trashDao().delete(item)
        trashFile.delete()
        assertFalse(trashFile.exists())
    }

    private fun mediaExists(displayName: String): Boolean {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            null,
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(displayName),
            null
        )?.use { return it.count > 0 }
        return false
    }
}
