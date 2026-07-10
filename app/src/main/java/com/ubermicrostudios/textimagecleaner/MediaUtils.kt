package com.ubermicrostudios.textimagecleaner

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.ubermicrostudios.textimagecleaner.data.TrashedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/** On-demand details for a single MMS (loaded only when user opens info). */
data class MmsMessageDetails(
    val msgId: Long,
    val body: String?,
    /** Peers: contact names if resolvable, else raw addresses. */
    val conversationLabel: String?,
    val threadId: Long?,
    val participantAddresses: List<String>
)

/** Shared media / MMS helpers for UI, workers, and tests. */
object MediaUtils {

    /**
     * Restores a trash file into public gallery storage (not back into SMS).
     */
    suspend fun restoreToGallery(context: Context, item: TrashedItem) = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, "trash/${item.fileName}")
        if (!file.exists()) return@withContext

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Restored_${item.fileName}")
            put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                if (item.mimeType.startsWith("video/")) {
                    Environment.DIRECTORY_MOVIES + "/TextImageCleaner"
                } else {
                    Environment.DIRECTORY_PICTURES + "/TextImageCleaner"
                }
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val collection = if (item.mimeType.startsWith("video/")) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val uri = context.contentResolver.insert(collection, values)
        uri?.let { destUri ->
            context.contentResolver.openOutputStream(destUri)?.use { out ->
                FileInputStream(file).use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(destUri, values, null, null)
        }
    }

    /**
     * Fast browse scan of MMS image/video parts.
     * Avoids per-part body queries and AFD size opens (major load bottleneck).
     * Body is captured at trash time; size defaults to 0 until needed.
     */
    suspend fun loadMmsMedia(contentResolver: ContentResolver): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val messageDates = mutableMapOf<Long, Long>()

            contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE),
                null,
                null,
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(Telephony.Mms._ID)
                val dateCol = cursor.getColumnIndexOrThrow(Telephony.Mms.DATE)
                while (cursor.moveToNext()) {
                    val rawDate = cursor.getLong(dateCol)
                    messageDates[cursor.getLong(idCol)] =
                        if (rawDate < 10_000_000_000L) rawDate * 1000 else rawDate
                }
            }

            val mediaItems = mutableListOf<MediaItem>()
            val partUriBase = Telephony.Mms.CONTENT_URI.buildUpon().appendPath("part").build()
            val selection =
                "${Telephony.Mms.Part.CONTENT_TYPE} LIKE 'image/%' OR " +
                    "${Telephony.Mms.Part.CONTENT_TYPE} LIKE 'video/%'"

            contentResolver.query(
                partUriBase,
                arrayOf(
                    Telephony.Mms.Part._ID,
                    Telephony.Mms.Part.MSG_ID,
                    Telephony.Mms.Part.CONTENT_TYPE
                ),
                selection,
                null,
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(Telephony.Mms.Part._ID)
                val msgIdCol = cursor.getColumnIndexOrThrow(Telephony.Mms.Part.MSG_ID)
                val typeCol = cursor.getColumnIndexOrThrow(Telephony.Mms.Part.CONTENT_TYPE)
                while (cursor.moveToNext()) {
                    val partId = cursor.getLong(idCol)
                    val msgId = cursor.getLong(msgIdCol)
                    val uri = partUriBase.buildUpon().appendPath(partId.toString()).build()
                    mediaItems.add(
                        MediaItem(
                            uri = uri,
                            mimeType = cursor.getString(typeCol) ?: "application/octet-stream",
                            size = 0L,
                            date = messageDates[msgId] ?: 0L,
                            body = null,
                            partId = partId,
                            msgId = msgId
                        )
                    )
                }
            }

            mediaItems.sortedByDescending { it.date }
        }

    fun getMmsText(contentResolver: ContentResolver, msgId: Long): String? {
        val selection =
            "${Telephony.Mms.Part.MSG_ID} = ? AND ${Telephony.Mms.Part.CONTENT_TYPE} = ?"
        val selectionArgs = arrayOf(msgId.toString(), "text/plain")
        contentResolver.query(
            Telephony.Mms.CONTENT_URI.buildUpon().appendPath("part").build(),
            arrayOf(Telephony.Mms.Part.TEXT),
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return null
    }

    /**
     * Loads body + conversation peers for one MMS (selection info panel only).
     * Contact name lookup is optional and only runs when READ_CONTACTS is granted.
     */
    suspend fun loadMmsMessageDetails(context: Context, msgId: Long): MmsMessageDetails =
        withContext(Dispatchers.IO) {
            if (msgId <= 0L) {
                return@withContext MmsMessageDetails(
                    msgId = msgId,
                    body = null,
                    conversationLabel = null,
                    threadId = null,
                    participantAddresses = emptyList()
                )
            }

            val cr = context.contentResolver
            val body = getMmsText(cr, msgId)
            val threadId = queryMmsThreadId(cr, msgId)
            val addresses = queryMmsAddresses(cr, msgId)
            val canReadContacts = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED

            val labels = addresses.map { addr ->
                if (canReadContacts) resolveContactDisplayName(cr, addr) ?: addr else addr
            }
            val conversationLabel = labels
                .distinct()
                .filter { it.isNotBlank() }
                .joinToString(", ")
                .ifBlank { null }

            MmsMessageDetails(
                msgId = msgId,
                body = body?.trim()?.takeIf { it.isNotEmpty() },
                conversationLabel = conversationLabel,
                threadId = threadId,
                participantAddresses = addresses
            )
        }

    private fun queryMmsThreadId(cr: ContentResolver, msgId: Long): Long? {
        return try {
            cr.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf(Telephony.Mms.THREAD_ID),
                "${Telephony.Mms._ID} = ?",
                arrayOf(msgId.toString()),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun queryMmsAddresses(cr: ContentResolver, msgId: Long): List<String> {
        val out = linkedSetOf<String>()
        try {
            val uri = "content://mms/$msgId/addr".toUri()
            cr.query(
                uri,
                arrayOf(Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE),
                null,
                null,
                null
            )?.use { cursor ->
                val addrCol = cursor.getColumnIndex(Telephony.Mms.Addr.ADDRESS)
                if (addrCol == -1) return@use
                while (cursor.moveToNext()) {
                    val raw = cursor.getString(addrCol)?.trim().orEmpty()
                    if (raw.isEmpty()) continue
                    if (raw.equals("insert-address-token", ignoreCase = true)) continue
                    out.add(raw)
                }
            }
        } catch (_: Exception) {
            // Some devices use alternate addr paths; fall through with empty list.
        }
        return out.toList()
    }

    private fun resolveContactDisplayName(cr: ContentResolver, address: String): String? {
        return try {
            if (address.contains("@")) {
                val uri = Uri.withAppendedPath(
                    ContactsContract.CommonDataKinds.Email.CONTENT_FILTER_URI,
                    Uri.encode(address)
                )
                cr.query(
                    uri,
                    arrayOf(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() }
                    else null
                }
            } else {
                val uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(address)
                )
                cr.query(
                    uri,
                    arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() }
                    else null
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
