package com.ubermicrostudios.textimagecleaner

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.ubermicrostudios.textimagecleaner.data.AppDatabase
import com.ubermicrostudios.textimagecleaner.data.SettingsRepository
import java.io.File
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppTab { CLEANER, TRASH }
enum class MediaTypeFilter { ALL, IMAGES, VIDEOS }

data class MediaItem(
    val uri: Uri,
    val mimeType: String,
    val size: Long,
    val date: Long,
    val body: String? = null,
    val partId: Long = -1L,
    val msgId: Long = -1L
)

sealed class DeleteAction {
    data class BySelection(val uris: List<Uri>) : DeleteAction()
    data class EmptyMessages(val value: Boolean = true) : DeleteAction()
}

data class GroupedMediaItems(
    val groupTitle: String,
    val items: List<MediaItem>
) {
    val uris: List<Uri> get() = items.map { it.uri }
}

class MainViewModel(context: Context) : ViewModel() {

    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val settingsRepository = SettingsRepository(appContext)
    private val trashDao = AppDatabase.getDatabase(appContext).trashDao()

    val backupFolderName = settingsRepository.backupFolderName
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsRepository.DEFAULT_BACKUP_FOLDER
        )

    var currentTab: AppTab by mutableStateOf(AppTab.CLEANER)

    var mediaList: List<MediaItem> by mutableStateOf(emptyList())
        private set

    var mediaTypeFilter: MediaTypeFilter by mutableStateOf(MediaTypeFilter.ALL)

    var isLoadingMedia: Boolean by mutableStateOf(false)
        private set

    /** True after at least one successful load attempt finished. */
    var hasCompletedInitialLoad: Boolean by mutableStateOf(false)
        private set

    private var _selectedItems by mutableStateOf<Set<Uri>>(emptySet())
    var selectedItems: Set<Uri>
        get() = _selectedItems
        set(value) {
            val previous = _selectedItems
            _selectedItems = value
            if (value.size != 1) {
                clearDetailsPanel()
            } else if (showMessageOption && value != previous) {
                loadDetailsForSelected()
            }
        }

    var selectionMode: Boolean by mutableStateOf(false)
        private set

    var showMessageOption: Boolean by mutableStateOf(false)
        private set

    var selectedDetails: MmsMessageDetails? by mutableStateOf(null)
        private set

    var isLoadingDetails: Boolean by mutableStateOf(false)
        private set

    private var detailsJob: Job? = null
    private val detailsCache = mutableMapOf<Long, MmsMessageDetails>()

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

    /**
     * Plain getters (not derivedStateOf) so Compose always tracks [mediaList] /
     * [mediaTypeFilter] when these are read during composition.
     */
    val filteredMediaList: List<MediaItem>
        get() {
            val mime = { item: MediaItem -> item.mimeType.lowercase(Locale.US) }
            return when (mediaTypeFilter) {
                MediaTypeFilter.IMAGES -> mediaList.filter { mime(it).startsWith("image/") }
                MediaTypeFilter.VIDEOS -> mediaList.filter { mime(it).startsWith("video/") }
                MediaTypeFilter.ALL -> mediaList
            }
        }

    val groupedMedia: List<GroupedMediaItems>
        get() {
            val monthYear = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
            return filteredMediaList
                .groupBy { item ->
                    Instant.ofEpochMilli(item.date)
                        .atZone(ZoneId.systemDefault())
                        .format(monthYear)
                }
                .map { (title, items) -> GroupedMediaItems(title, items) }
        }

    val selectedSizeBytes: Long
        get() = mediaList.filter { it.uri in selectedItems }.sumOf { it.size }

    fun loadMedia() {
        if (isLoadingMedia) return
        viewModelScope.launch {
            isLoadingMedia = true
            try {
                // Exclude anything already in trash so cleaner + calendar never re-offer it.
                val trashUris = withContext(Dispatchers.IO) {
                    trashDao.getAllUriStrings().toHashSet()
                }
                val loaded = MediaUtils.loadMmsMedia(appContext.contentResolver)
                mediaList = if (trashUris.isEmpty()) {
                    loaded
                } else {
                    loaded.filter { it.uri.toString() !in trashUris }
                }
                // Drop selection for items that disappeared (e.g. just trashed).
                if (selectedItems.isNotEmpty()) {
                    val still = mediaList.map { it.uri }.toHashSet()
                    selectedItems = selectedItems.filter { it in still }.toSet()
                    if (selectedItems.isEmpty()) exitSelectionMode()
                }
            } finally {
                isLoadingMedia = false
                hasCompletedInitialLoad = true
            }
        }
    }

    fun toggleItemSelection(uri: Uri) {
        val newSet = selectedItems.toMutableSet()
        if (!newSet.add(uri)) newSet.remove(uri)
        selectedItems = newSet
        if (selectedItems.size != 1) clearDetailsPanel()
    }

    fun selectAllInGroup(uris: Collection<Uri>) {
        val newSet = selectedItems.toMutableSet()
        if (uris.all { it in newSet }) newSet.removeAll(uris.toSet())
        else newSet.addAll(uris)
        selectedItems = newSet
        if (selectedItems.size != 1) clearDetailsPanel()
    }

    fun enterSelectionMode() {
        selectionMode = true
    }

    fun exitSelectionMode() {
        selectionMode = false
        selectedItems = emptySet()
        clearDetailsPanel()
    }

    fun toggleShowMessageOption() {
        if (showMessageOption) {
            clearDetailsPanel()
            return
        }
        if (selectedItems.size != 1) return
        showMessageOption = true
        loadDetailsForSelected()
    }

    private fun clearDetailsPanel() {
        showMessageOption = false
        selectedDetails = null
        isLoadingDetails = false
        detailsJob?.cancel()
        detailsJob = null
    }

    /** Re-fetch open info panel (e.g. after Contacts permission is granted). */
    fun refreshSelectedDetails(clearCache: Boolean = true) {
        if (!showMessageOption || selectedItems.size != 1) return
        if (clearCache) {
            val uri = selectedItems.single()
            val msgId = mediaList.find { it.uri == uri }?.msgId
            if (msgId != null && msgId > 0L) detailsCache.remove(msgId)
            else detailsCache.clear()
        }
        loadDetailsForSelected(forceNetwork = true)
    }

    private fun loadDetailsForSelected(forceNetwork: Boolean = false) {
        val uri = selectedItems.singleOrNull() ?: return
        val item = mediaList.find { it.uri == uri }
        val msgId = item?.msgId ?: -1L
        if (msgId <= 0L) {
            selectedDetails = MmsMessageDetails(
                msgId = msgId,
                body = null,
                conversationLabel = null,
                threadId = null,
                participantAddresses = emptyList()
            )
            isLoadingDetails = false
            return
        }

        if (!forceNetwork) {
            detailsCache[msgId]?.let {
                selectedDetails = it
                isLoadingDetails = false
                return
            }
        }

        detailsJob?.cancel()
        detailsJob = viewModelScope.launch {
            isLoadingDetails = true
            if (forceNetwork) selectedDetails = null
            try {
                val details = MediaUtils.loadMmsMessageDetails(appContext, msgId)
                detailsCache[msgId] = details
                // Only apply if still viewing the same single selection.
                if (showMessageOption && selectedItems.singleOrNull() == uri) {
                    selectedDetails = details
                }
            } finally {
                isLoadingDetails = false
            }
        }
    }

    fun setDeleteOptions(attachmentsOnly: Boolean, backup: Boolean) {
        deleteAttachmentsOnly = attachmentsOnly
        backupBeforeDelete = backup
    }

    fun saveBackupFolderName(name: String) {
        viewModelScope.launch {
            settingsRepository.setBackupFolderName(name)
        }
    }

    /**
     * Snapshot of every filtered item in [startInclusiveMs, endInclusiveMs] (inclusive).
     * Single pass over a frozen list so date-range delete enqueues the full set at once.
     */
    fun itemsInDateRange(startInclusiveMs: Long, endInclusiveMs: Long): List<MediaItem> {
        val lo = minOf(startInclusiveMs, endInclusiveMs)
        val hi = maxOf(startInclusiveMs, endInclusiveMs)
        // Freeze list so concurrent loadMedia cannot shrink the snapshot mid-filter.
        val snapshot = filteredMediaList.toList()
        return snapshot.filter { it.date in lo..hi }
    }

    fun countInDateRange(startInclusiveMs: Long, endInclusiveMs: Long): Int =
        itemsInDateRange(startInclusiveMs, endInclusiveMs).size

    fun urisInDateRange(startInclusiveMs: Long, endInclusiveMs: Long): List<Uri> =
        itemsInDateRange(startInclusiveMs, endInclusiveMs).map { it.uri }.distinct()

    /**
     * Local calendar months that contain at least one item under the current type filter.
     * Used to gray out empty months in the date-range picker.
     */
    fun monthsWithMedia(): Set<YearMonth> {
        val zone = ZoneId.systemDefault()
        return filteredMediaList.mapTo(linkedSetOf()) { item ->
            YearMonth.from(Instant.ofEpochMilli(item.date).atZone(zone).toLocalDate())
        }
    }

    /** Year bounds for the date picker from media months (current year if empty). */
    fun mediaYearRange(): IntRange {
        val months = monthsWithMedia()
        if (months.isEmpty()) {
            val y = YearMonth.now().year
            return y..y
        }
        val years = months.map { it.year }
        return years.min()..years.max()
    }

    fun startDeletion(action: DeleteAction, onStarted: (UUID) -> Unit = {}) {
        viewModelScope.launch {
            val request = withContext(Dispatchers.IO) {
                val backupFolder = settingsRepository.getBackupFolderNameOnce()
                val builder = OneTimeWorkRequestBuilder<DeletionWorker>()
                if (action is DeleteAction.BySelection) {
                    val file = File(appContext.cacheDir, "uris_to_delete.txt")
                    // Distinct, complete URI list written in one pass before enqueue.
                    val lines = action.uris.map { it.toString() }.distinct()
                    file.writeText(lines.joinToString("\n"))
                    builder.setInputData(
                        workDataOf(
                            DeletionWorker.KEY_URIS_FILE_PATH to file.absolutePath,
                            DeletionWorker.KEY_DELETE_ATTACHMENTS_ONLY to deleteAttachmentsOnly,
                            DeletionWorker.KEY_BACKUP_BEFORE_DELETE to backupBeforeDelete,
                            DeletionWorker.KEY_BACKUP_FOLDER_NAME to backupFolder
                        )
                    )
                } else {
                    builder.setInputData(
                        workDataOf(DeletionWorker.KEY_DELETE_EMPTY_MESSAGES to true)
                    )
                }
                builder.build()
            }

            workManager.enqueue(request)
            currentWorkId = request.id
            totalToDelete = if (action is DeleteAction.BySelection) action.uris.size else 0
            deletedCount = 0
            deletionLog.clear()
            showDeleteProgressScreen = true
            onStarted(request.id)
        }
    }

    fun cancelWork() {
        currentWorkId?.let { workManager.cancelWorkById(it) }
    }

    fun onWorkFinished(wasCancelled: Boolean) {
        showDeleteProgressScreen = false
        currentWorkId = null
        deletionLog.clear()
        deletedCount = 0
        totalToDelete = 0
        if (!wasCancelled) {
            // caller refreshes media
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
