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
import com.ubermicrostudios.textimagecleaner.data.TrashedItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class MainViewModel(private val context: Context) : ViewModel() {

    private val workManager = WorkManager.getInstance(context)
    private val database = AppDatabase.getDatabase(context)
    private val trashDao = database.trashDao()

    // UI State
    private val _currentTab = MutableStateFlow(AppTab.CLEANER)
    val currentTab: StateFlow<AppTab> = _currentTab.asStateFlow()

    private val _mediaList = MutableStateFlow<List<MediaItem>>(emptyList())
    val mediaList: StateFlow<List<MediaItem>> = _mediaList.asStateFlow()

    private val _mediaTypeFilter = MutableStateFlow(MediaTypeFilter.ALL)
    val mediaTypeFilter: StateFlow<MediaTypeFilter> = _mediaTypeFilter.asStateFlow()

    private val _selectedItems = MutableStateFlow<Set<Uri>>(emptySet())
    val selectedItems: StateFlow<Set<Uri>> = _selectedItems.asStateFlow()

    private val _selectionMode = MutableStateFlow(false)
    val selectionMode: StateFlow<Boolean> = _selectionMode.asStateFlow()

    private val _showMessageOption = MutableStateFlow(false)
    val showMessageOption: StateFlow<Boolean> = _showMessageOption.asStateFlow()

    private val _deleteAttachmentsOnly = MutableStateFlow(false)
    val deleteAttachmentsOnly: StateFlow<Boolean> = _deleteAttachmentsOnly.asStateFlow()

    private val _backupBeforeDelete = MutableStateFlow(false)
    val backupBeforeDelete: StateFlow<Boolean> = _backupBeforeDelete.asStateFlow()

    // Work / Deletion State
    private val _currentWorkId = MutableStateFlow<UUID?>(null)
    val currentWorkId: StateFlow<UUID?> = _currentWorkId.asStateFlow()

    private val _showDeleteProgressScreen = MutableStateFlow(false)
    val showDeleteProgressScreen: StateFlow<Boolean> = _showDeleteProgressScreen.asStateFlow()

    private val _deletedCount = MutableStateFlow(0)
    val deletedCount: StateFlow<Int> = _deletedCount.asStateFlow()

    private val _totalToDelete = MutableStateFlow(0)
    val totalToDelete: StateFlow<Int> = _totalToDelete.asStateFlow()

    val deletionLog = mutableStateListOf<String>()

    // Computed / Derived (simplified for now - can be improved with derivedStateOf later)
    val filteredMediaList: List<MediaItem>
        get() = when (_mediaTypeFilter.value) {
            MediaTypeFilter.IMAGES -> _mediaList.value.filter { it.mimeType.startsWith("image/") }
            MediaTypeFilter.VIDEOS -> _mediaList.value.filter { it.mimeType.startsWith("video/") }
            MediaTypeFilter.ALL -> _mediaList.value
        }

    fun setCurrentTab(tab: AppTab) {
        _currentTab.value = tab
    }

    fun setMediaTypeFilter(filter: MediaTypeFilter) {
        _mediaTypeFilter.value = filter
    }

    fun toggleSelection(uri: Uri) {
        val current = _selectedItems.value.toMutableSet()
        if (current.contains(uri)) current.remove(uri) else current.add(uri)
        _selectedItems.value = current
        if (current.size != 1) _showMessageOption.value = false
    }

    fun selectAllInGroup(uris: Set<Uri>) {
        val current = _selectedItems.value.toMutableSet()
        val allSelected = uris.all { current.contains(it) }
        if (allSelected) {
            current.removeAll(uris)
        } else {
            current.addAll(uris)
        }
        _selectedItems.value = current
        if (_selectedItems.value.size != 1) _showMessageOption.value = false
    }

    fun enterSelectionMode() {
        _selectionMode.value = true
    }

    fun exitSelectionMode() {
        _selectionMode.value = false
        _selectedItems.value = emptySet()
        _showMessageOption.value = false
    }

    fun toggleShowMessageOption() {
        _showMessageOption.value = !_showMessageOption.value
    }

    fun setDeleteOptions(attachmentsOnly: Boolean, backup: Boolean) {
        _deleteAttachmentsOnly.value = attachmentsOnly
        _backupBeforeDelete.value = backup
    }

    fun updateMedia() {
        viewModelScope.launch {
            // In real implementation this would call MediaUtils.loadMmsMedia
            // For now we keep the original call site in the composable for minimal breakage
        }
    }

    fun startDeletion(
        action: DeleteAction,
        onWorkStarted: (UUID) -> Unit
    ) {
        val workBuilder = OneTimeWorkRequestBuilder<DeletionWorker>()

        if (action is DeleteAction.BySelection) {
            val file = File(context.cacheDir, "uris_to_delete.txt")
            file.writeText(action.uris.joinToString("\n"))
            workBuilder.setInputData(
                workDataOf(
                    DeletionWorker.KEY_URIS_FILE_PATH to file.absolutePath,
                    DeletionWorker.KEY_DELETE_ATTACHMENTS_ONLY to _deleteAttachmentsOnly.value,
                    DeletionWorker.KEY_BACKUP_BEFORE_DELETE to _backupBeforeDelete.value,
                )
            )
        } else {
            workBuilder.setInputData(workDataOf(DeletionWorker.KEY_DELETE_EMPTY_MESSAGES to true))
        }

        val request = workBuilder.build()
        workManager.enqueue(request)
        _currentWorkId.value = request.id
        _showDeleteProgressScreen.value = true
        onWorkStarted(request.id)
    }

    fun cancelCurrentWork() {
        _currentWorkId.value?.let { workManager.cancelWorkById(it) }
    }

    fun onWorkFinished(wasCancelled: Boolean) {
        _showDeleteProgressScreen.value = false
        _currentWorkId.value = null
        deletionLog.clear()
        _selectedItems.value = emptySet()
        _selectionMode.value = false
        _showMessageOption.value = false

        if (!wasCancelled) {
            // Trigger refresh from outside (or expose a refresh trigger)
        }
    }

    fun updateWorkProgress(total: Int, deleted: Int, lastItem: String?) {
        if (total > 0) {
            _totalToDelete.value = total
            _deletedCount.value = deleted
        }
        if (lastItem != null && (deletionLog.isEmpty() || deletionLog.last() != lastItem)) {
            deletionLog.add(lastItem)
        }
    }
}
