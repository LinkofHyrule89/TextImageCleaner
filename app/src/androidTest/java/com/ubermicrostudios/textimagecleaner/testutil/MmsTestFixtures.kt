package com.ubermicrostudios.textimagecleaner.testutil

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Telephony
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Helpers to seed system MMS storage for instrumented tests.
 * Requires the app under test to be the **default SMS app**.
 */
class MmsTestFixtures(private val context: Context) {

    fun uriExists(uri: Uri): Boolean {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use {
                it.moveToFirst()
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    fun insertMms(dateSeconds: Long = System.currentTimeMillis() / 1000): Uri {
        val values = ContentValues().apply {
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
            put(Telephony.Mms.DATE, dateSeconds)
            put(Telephony.Mms.CONTENT_TYPE, "application/vnd.wap.mms-message")
        }
        return context.contentResolver.insert(Telephony.Mms.CONTENT_URI, values)
            ?: error(
                "MMS insert failed. Grant Default SMS role to " +
                    "com.ubermicrostudios.textimagecleaner and re-run."
            )
    }

    fun insertImagePart(
        mmsUri: Uri,
        partName: String = "img_${UUID.randomUUID()}.jpg",
        sizePx: Int = 12
    ): Uri {
        val mmsId = mmsUri.lastPathSegment ?: error("bad mms uri")
        val partUri = Uri.parse("content://mms/$mmsId/part")
        val values = ContentValues().apply {
            put(Telephony.Mms.Part.MSG_ID, mmsId)
            put(Telephony.Mms.Part.CONTENT_TYPE, "image/jpeg")
            put(Telephony.Mms.Part.NAME, partName)
            put(Telephony.Mms.Part.FILENAME, partName)
        }
        val inserted = context.contentResolver.insert(partUri, values)
            ?: error("Image part insert failed")
        context.contentResolver.openOutputStream(inserted)?.use { out ->
            Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
                .compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        return inserted
    }

    fun insertVideoPart(
        mmsUri: Uri,
        partName: String = "vid_${UUID.randomUUID()}.mp4"
    ): Uri {
        val mmsId = mmsUri.lastPathSegment ?: error("bad mms uri")
        val partUri = Uri.parse("content://mms/$mmsId/part")
        val values = ContentValues().apply {
            put(Telephony.Mms.Part.MSG_ID, mmsId)
            put(Telephony.Mms.Part.CONTENT_TYPE, "video/mp4")
            put(Telephony.Mms.Part.NAME, partName)
            put(Telephony.Mms.Part.FILENAME, partName)
        }
        val inserted = context.contentResolver.insert(partUri, values)
            ?: error("Video part insert failed")
        // Minimal non-empty payload (not a real MP4; enough for stream copy / delete tests).
        context.contentResolver.openOutputStream(inserted)?.use { out ->
            out.write(ByteArray(256) { (it % 251).toByte() })
        }
        return inserted
    }

    fun insertTextPart(mmsUri: Uri, text: String): Uri {
        val mmsId = mmsUri.lastPathSegment ?: error("bad mms uri")
        val partUri = Uri.parse("content://mms/$mmsId/part")
        val values = ContentValues().apply {
            put(Telephony.Mms.Part.MSG_ID, mmsId)
            put(Telephony.Mms.Part.CONTENT_TYPE, "text/plain")
            put(Telephony.Mms.Part.TEXT, text)
        }
        return context.contentResolver.insert(partUri, values)
            ?: error("Text part insert failed")
    }

    fun uniqueBody(prefix: String = "TIC-TEST"): String =
        "$prefix-${UUID.randomUUID()}"
}
