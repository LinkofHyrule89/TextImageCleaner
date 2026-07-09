package com.ubermicrostudios.textimagecleaner

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.ubermicrostudios.textimagecleaner.data.AppDatabase
import java.io.File
import java.util.UUID

// Types moved here for visibility
enum class AppTab { CLEANER, TRASH }
enum class MediaTypeFilter { ALL, IMAGES, VIDEOS }

data class MediaItem(
    val uri: Uri,
    val mimeType: String,
    val size: Long,
    val date: Long
)

sealed class DeleteAction {
    data class BySelection(val uris: List<Uri>) : DeleteAction()
    data class EmptyMessages(val value: Boolean = true) : DeleteAction()
}

data class GroupedMediaItems(
    val groupTitle: String,
    val uris: List<Uri>
)

class MainViewModel(private val context: Context) : ViewModel() {

    private val workManager = WorkManager.getInstance(context)
    private val database = AppDatabase.getDatabase(context)
    private val trashDao = database.trashDao()

    // State - using proper delegates
    var currentTab: AppTab by mutableStateOf(AppTab.CLEANER)
        private set

    var mediaList: List<MediaItem> by mutableStateOf(emptyList())
        private set

    var mediaTypeFilter: MediaTypeFilter by mutableStateOf(MediaTypeFilter.ALL)
        private set

    var selectedItems: Set<Uri> by mutableStateOf(emptySet())

    var selectionMode: Boolean by mutableStateOf(false)
        private set

    var showMessageOption: Boolean by mutableStateOf(false)
        private set

    var deleteAttachmentsOnly: Boolean by mutableStateOf(false)
        private set

    var backupBeforeDelete: Boolean by mutableStateOf(false)
        private set

    var showDeleteProgressScreen: Boolean by mutableStateOf(false)
        private set

    var deletedCount: Int by mutableIntStateOf(0)
        private set

    var totalToDelete: Int by mutableIntStateOf(0)
        private set

    var currentWorkId: UUID? by mutableStateOf(null)
        private set

    val deletionLog = mutableStateListOf<String>()

    val filteredMediaList: List<MediaItem>
        get() = when (mediaTypeFilter) {
            MediaTypeFilter.IMAGES -> mediaList.filter { it.mimeType.startsWith("image/") }
            MediaTypeFilter.VIDEOS -> mediaList.filter { it.mimeType.startsWith("video/") }
            else -> mediaList
        }

    fun setCurrentTab(tab: AppTab) { currentTab = tab }
    fun setMediaTypeFilter(filter: MediaTypeFilter) { mediaTypeFilter = filter }

    fun toggleItemSelection(uri: Uri) {
        val newSet = selectedItems.toMutableSet()
        if (newSet.contains(uri)) newSet.remove(uri) else newSet.add(uri)
        selectedItems = newSet
        if (selectedItems.size != 1) showMessageOption = false
    }

    fun selectAllInGroup(uris: Set<Uri>) {
        val newSet = selectedItems.toMutableSet()
        if (uris.all { newSet.contains(it) }) {
            newSet.removeAll(uris)
        } else {
            newSet.addAll(uris)
        }
        selectedItems = newSet
    }

    fun enterSelectionMode() { selectionMode = true }
    fun exitSelectionMode() {
        selectionMode = false
        selectedItems = emptySet()
        showMessageOption = false
    }

    fun toggleShowMessageOption() { showMessageOption = !showMessageOption }

    fun setDeleteOptions(attachmentsOnly: Boolean, backup: Boolean) {
        deleteAttachmentsOnly = attachmentsOnly
        backupBeforeDelete = backup
    }

    fun updateMedia(newList: List<MediaItem>) {
        mediaList = newList
    }

    fun startDeletion(action: DeleteAction, onStarted: (UUID) -> Unit) {
        val builder = OneTimeWorkRequestBuilder<DeletionWorker>()

        if (action is DeleteAction.BySelection) {
            val file = File(context.cacheDir, "uris_to_delete.txt")
            file.writeText(action.uris.joinToString("\n"))
            builder.setInputData(workDataOf(
                DeletionWorker.KEY_URIS_FILE_PATH to file.absolutePath,
                DeletionWorker.KEY_DELETE_ATTACHMENTS_ONLY to deleteAttachmentsOnly,
                DeletionWorker.KEY_BACKUP_BEFORE_DELETE to backupBeforeDelete
            ))
        } else {
            builder.setInputData(workDataOf(DeletionWorker.KEY_DELETE_EMPTY_MESSAGES to true))
        }

        val request = builder.build()
        workManager.enqueue(request)
        currentWorkId = request.id
        showDeleteProgressScreen = true
        onStarted(request.id)
    }

    fun cancelWork() {
        currentWorkId?.let { workManager.cancelWorkById(it) }
    }

    fun onWorkFinished(wasCancelled: Boolean) {
        showDeleteProgressScreen = false
        currentWorkId = null
        deletionLog.clear()
        if (!wasCancelled) {
            // caller should refresh media
        }
    }

    fun updateProgress(total: Int, deleted: Int, lastItem: String?) {
        if (total > 0) {
            totalToDelete = total
            deletedCount = deleted
        }
        lastItem?.let {
            if (deletionLog.isEmpty() || deletionLog.last() != it) deletionLog.add(it)
        }
    }
}