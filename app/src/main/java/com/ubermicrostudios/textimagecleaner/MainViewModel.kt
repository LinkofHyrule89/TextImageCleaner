package com.ubermicrostudios.textimagecleaner

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.ubermicrostudios.textimagecleaner.data.AppDatabase
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class MainViewModel(private val context: Context) : ViewModel() {

    private val workManager = WorkManager.getInstance(context)
    private val database = AppDatabase.getDatabase(context)
    private val trashDao = database.trashDao()

    // State
    var currentTab by androidx.compose.runtime.mutableStateOf(AppTab.CLEANER)
        private set

    var mediaList by androidx.compose.runtime.mutableStateOf<List<MediaItem>>(emptyList())
        private set

    var mediaTypeFilter by androidx.compose.runtime.mutableStateOf(MediaTypeFilter.ALL)
        private set

    var selectedItems by androidx.compose.runtime.mutableStateOf<Set<Uri>>(emptySet())
        private set

    var selectionMode by androidx.compose.runtime.mutableStateOf(false)
        private set

    var showMessageOption by androidx.compose.runtime.mutableStateOf(false)
        private set

    var deleteAttachmentsOnly by androidx.compose.runtime.mutableStateOf(false)
        private set

    var backupBeforeDelete by androidx.compose.runtime.mutableStateOf(false)
        private set

    var showDeleteProgressScreen by androidx.compose.runtime.mutableStateOf(false)
        private set

    var deletedCount by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    var totalToDelete by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    var currentWorkId by androidx.compose.runtime.mutableStateOf<UUID?>(null)
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
