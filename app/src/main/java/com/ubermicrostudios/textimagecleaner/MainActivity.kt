package com.ubermicrostudios.textimagecleaner

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Telephony
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.ubermicrostudios.textimagecleaner.data.AppDatabase
import com.ubermicrostudios.textimagecleaner.data.TrashedItem
import com.ubermicrostudios.textimagecleaner.ui.theme.TextImageCleanerTheme
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** UI model for a single media item found in MMS. */
data class MediaItem(
    val uri: Uri, 
    val date: Long, 
    val mimeType: String, 
    val size: Long = 0, 
    val messageBody: String? = null,
)

/** Groups media items by month for the UI grid. */
data class GroupedMediaItems(val groupTitle: String, val items: List<MediaItem>, val uris: Set<Uri>)

/** Sealed class representing different types of cleanup operations. */
sealed class DeleteAction {
    data class BySelection(val uris: Set<Uri>) : DeleteAction()
    object EmptyMessages : DeleteAction()
}

/** Enum for top-level navigation tabs. */
enum class AppTab { CLEANER, TRASH }

/** Enum for filtering media types in the cleaner view. */
enum class MediaTypeFilter { ALL, IMAGES, VIDEOS }

class MainActivity : ComponentActivity() {

    // Tracks if the app is currently the system's default SMS app.
    private var isDefault by mutableStateOf(false)

    // Result launcher for the 'Set as Default SMS' system dialog.
    private val roleRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { 
        isDefault = isDefaultSmsApp()
        if (isDefault) {
            Toast.makeText(this, "Now default SMS app!", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Default SMS not granted", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Modern edge-to-edge support for API 35+
        enableEdgeToEdge()

        isDefault = isDefaultSmsApp()

        setContent {
            TextImageCleanerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SmsAppScreen(
                        onRequestDefaultSms = { requestDefaultSmsRole() },
                        onRequestSystemDefaultSms = { openDefaultSmsSettings() },
                        isDefault = isDefault,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check status when user returns to the app
        isDefault = isDefaultSmsApp()
    }

    /** Checks if this app is the current default SMS provider. */
    private fun isDefaultSmsApp(): Boolean {
        // Assume API 35+ (Android 15+)
        val roleManager = getSystemService(RoleManager::class.java)
        return roleManager?.isRoleHeld(RoleManager.ROLE_SMS) ?: false
    }

    /** Launches the system request to become the default SMS app. */
    private fun requestDefaultSmsRole() {
        // Assume API 35+ (Android 15+)
        val roleManager = getSystemService(RoleManager::class.java)
        if (roleManager != null && 
            roleManager.isRoleAvailable(RoleManager.ROLE_SMS) &&
            !roleManager.isRoleHeld(RoleManager.ROLE_SMS)
        ) {
            val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
            roleRequestLauncher.launch(intent)
        }
    }

    /** Opens the system settings for default app management. */
    private fun openDefaultSmsSettings() {
        val intent = Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        startActivity(intent)
    }
}

/** Main entry point for the Compose UI. */
@OptIn(
    ExperimentalPermissionsApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class,
)
@Composable
fun SmsAppScreen(
    onRequestDefaultSms: () -> Unit,
    onRequestSystemDefaultSms: () -> Unit,
    isDefault: Boolean,
) {
    val context = LocalContext.current
    val contentResolver = context.contentResolver
    val coroutineScope = rememberCoroutineScope()
    val workManager = remember { WorkManager.getInstance(context) }
    
    // Data layer handles
    val database = remember { AppDatabase.getDatabase(context) }
    val trashDao = database.trashDao()
    val trashedItems by trashDao.getAllTrashedItems().collectAsState(initial = emptyList())
    val totalTrashedSize by trashDao.getTotalTrashedSize().collectAsState(initial = 0L)

    // Modern Permissions logic (Android 15, 16, 17 Beta Ready)
    // - READ_SMS is required even for Default SMS apps when targeting higher SDKs 
    //   to perform direct ContentResolver queries on MmsProvider.
    val permissionsToRequest = remember {
        listOf(
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.POST_NOTIFICATIONS,
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.READ_MEDIA_VIDEO,
            android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
    }

    val permissionsState = rememberMultiplePermissionsState(permissionsToRequest)

    // UI State
    var currentTab by remember { mutableStateOf(AppTab.CLEANER) }
    var mediaList by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var mediaTypeFilter by remember { mutableStateOf(MediaTypeFilter.ALL) }

    // Logic to filter the media list based on user selection
    val filteredMediaList = remember(mediaList, mediaTypeFilter) {
        when (mediaTypeFilter) {
            MediaTypeFilter.IMAGES -> mediaList.filter { it.mimeType.startsWith("image/") }
            MediaTypeFilter.VIDEOS -> mediaList.filter { it.mimeType.startsWith("video/") }
            MediaTypeFilter.ALL -> mediaList
        }
    }

    // Logic to group media by month for the grid headers
    val groupedMedia = remember(filteredMediaList) {
        filteredMediaList.asSequence()
            .groupBy {
                val date = LocalDateTime.ofInstant(Instant.ofEpochMilli(it.date), ZoneId.systemDefault())
                YearMonth.from(date)
            }.toSortedMap(Comparator.reverseOrder())
            .map { (yearMonth, items) ->
                val title = yearMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
                GroupedMediaItems(title, items, items.asSequence().map { it.uri }.toSet())
            }
    }

    // Interaction State
    var showConfirmDeleteDialog by remember { mutableStateOf(false) }
    var deleteAction by remember { mutableStateOf<DeleteAction?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedItems by remember { mutableStateOf(value = emptySet<Uri>()) }
    var deleteAttachmentsOnly by remember { mutableStateOf(false) }
    var backupBeforeDelete by remember { mutableStateOf(false) }
    var showMessageOption by remember { mutableStateOf(false) } // Toggle for viewing text message context

    // Background Work State
    var showDeleteProgressScreen by remember { mutableStateOf(false) }
    var showCancelledDialog by remember { mutableStateOf(false) }
    var currentWorkId by remember { mutableStateOf<UUID?>(null) }
    val deletionLog = remember { mutableStateListOf<String>() }

    // Observe WorkManager progress for the real-time overlay
    val workInfoState = remember(currentWorkId) {
        currentWorkId?.let { workManager.getWorkInfoByIdLiveData(it) }
    }?.observeAsState()
    
    val workInfo = workInfoState?.value
    var deletedCount by remember { mutableIntStateOf(0) }
    var totalToDelete by remember { mutableIntStateOf(0) }

    // Specialized ImageLoader for Video Thumbnails
    val imageLoader = remember {
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            .build()
    }

    /** Refresh media items from system storage. */
    fun updateMedia() {
        coroutineScope.launch {
            mediaList = MediaUtils.loadMmsMedia(contentResolver)
        }
    }

    // Handle background work updates and completion logic
    LaunchedEffect(workInfo) {
        val currentWorkInfo = workInfo
        if (currentWorkInfo != null) {
            val progress = currentWorkInfo.progress
            val total = progress.getInt(DeletionWorker.KEY_TOTAL_COUNT, 0)
            val deleted = progress.getInt(DeletionWorker.KEY_DELETED_COUNT, 0)
            val lastItem = progress.getString(DeletionWorker.KEY_LAST_ITEM_INFO)
            
            if (total > 0) {
                totalToDelete = total
                deletedCount = deleted
            }

            if (lastItem != null && (deletionLog.isEmpty() || deletionLog.last() != lastItem)) {
                deletionLog.add(lastItem)
            }

            if (currentWorkInfo.state == WorkInfo.State.RUNNING || currentWorkInfo.state == WorkInfo.State.ENQUEUED) {
                showDeleteProgressScreen = true
            }

            if (currentWorkInfo.state.isFinished) {
                showDeleteProgressScreen = false
                
                // Determine if this was a "delete only" operation from input data
                val wasDeleteOnly = currentWorkInfo.outputData.getBoolean(DeletionWorker.KEY_DELETE_ATTACHMENTS_ONLY, false)
                
                currentWorkId = null
                deletionLog.clear()
                
                if (currentWorkInfo.state == WorkInfo.State.CANCELLED) {
                    showCancelledDialog = true
                } else {
                    updateMedia()
                    val message = if (wasDeleteOnly) "Attachments deleted permanently" else "Items moved to Trash"
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Auto-refresh when permissions and default role are granted
    LaunchedEffect(permissionsState.allPermissionsGranted, isDefault) {
        if (permissionsState.allPermissionsGranted && isDefault) {
            updateMedia()
        }
    }

    // Full screen overlay for trashing/deletion progress
    if (showDeleteProgressScreen) {
        DeletionProgressOverlay(
            totalToDelete = totalToDelete,
            deletedCount = deletedCount,
            deletionLog = deletionLog,
            isDeleteOnly = deleteAttachmentsOnly,
            onCancel = { currentWorkId?.let { workManager.cancelWorkById(it) } },
        )
        return
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { 
                        Column {
                            Text(if (currentTab == AppTab.CLEANER) "Cleaner" else "Trash Can")
                            if (currentTab == AppTab.CLEANER) {
                                // Dynamic Subtitle with statistics
                                val images = mediaList.filter { it.mimeType.startsWith("image/") }
                                val videos = mediaList.filter { it.mimeType.startsWith("video/") }
                                val imgSize = images.sumOf { it.size } / (1024 * 1024)
                                val vidSize = videos.sumOf { it.size } / (1024 * 1024)
                                
                                val subtitle = when(mediaTypeFilter) {
                                    MediaTypeFilter.ALL -> "Images: ${images.size} (${imgSize}MB) • Videos: ${videos.size} (${vidSize}MB)"
                                    MediaTypeFilter.IMAGES -> "${images.size} Images • ${imgSize}MB"
                                    MediaTypeFilter.VIDEOS -> "${videos.size} Videos • ${vidSize}MB"
                                }
                                Text(subtitle, style = MaterialTheme.typography.labelSmall)
                            } else if (currentTab == AppTab.TRASH) {
                                val trashSizeMb = (totalTrashedSize ?: 0L) / (1024 * 1024)
                                Text("${trashedItems.size} items • ${trashSizeMb}MB stored", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    },
                    actions = {
                        if (isDefault && permissionsState.allPermissionsGranted) {
                            if (currentTab == AppTab.CLEANER && !selectionMode) {
                                // Filtering Buttons
                                IconButton(onClick = { mediaTypeFilter = MediaTypeFilter.ALL }) {
                                    Icon(Icons.Default.PhotoLibrary, "All Media", tint = if (mediaTypeFilter == MediaTypeFilter.ALL) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { mediaTypeFilter = MediaTypeFilter.IMAGES }) {
                                    Icon(Icons.Default.Image, "Images Only", tint = if (mediaTypeFilter == MediaTypeFilter.IMAGES) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { mediaTypeFilter = MediaTypeFilter.VIDEOS }) {
                                    Icon(Icons.Default.Videocam, "Videos Only", tint = if (mediaTypeFilter == MediaTypeFilter.VIDEOS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { updateMedia() }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                                }
                            } else if (currentTab == AppTab.TRASH) {
                                // Empty Trash Button
                                IconButton(onClick = {
                                    coroutineScope.launch {
                                        val trashDir = File(context.filesDir, "trash")
                                        trashDir.deleteRecursively()
                                        trashDao.deleteAll()
                                        Toast.makeText(context, "Trash Emptied", Toast.LENGTH_SHORT).show()
                                    }
                                }) {
                                    Icon(Icons.Default.DeleteForever, contentDescription = "Empty Trash")
                                }
                            }
                            
                            if (!selectionMode) {
                                IconButton(onClick = onRequestSystemDefaultSms) {
                                    Icon(Icons.Default.Settings, contentDescription = "Change Default App")
                                }
                            }
                        }
                    },
                )

                // Selection Action Bar - appears when user selects one or more items
                AnimatedVisibility(
                    visible = selectionMode && currentTab == AppTab.CLEANER,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    val selectedItemsList = remember(selectedItems, mediaList) {
                        mediaList.filter { it.uri in selectedItems }
                    }
                    val selectedSizeMb = selectedItemsList.sumOf { it.size } / (1024.0 * 1024.0)

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shadowElevation = 8.dp,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = {
                                    selectionMode = false
                                    selectedItems = emptySet()
                                    showMessageOption = false
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Cancel Selection")
                                }
                                Column {
                                    Text(
                                        "${selectedItems.size} selected",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        String.format(Locale.getDefault(), "%.2f MB to be freed", selectedSizeMb),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // INFO OPTION: Shows the text message content for the selected item
                                if (selectedItems.size == 1) {
                                    IconButton(onClick = { showMessageOption = !showMessageOption }) {
                                        Icon(
                                            Icons.Default.Info, 
                                            contentDescription = "Show Message Context",
                                            tint = if (showMessageOption) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                
                                IconButton(onClick = {
                                    if (selectedItems.isNotEmpty()) {
                                        deleteAction = DeleteAction.BySelection(selectedItems)
                                        showConfirmDeleteDialog = true
                                    }
                                }) {
                                    Icon(
                                        Icons.Default.Delete, 
                                        contentDescription = "Delete or Trash", 
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(32.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                
                // Individual Message Preview Drawer
                AnimatedVisibility(
                    visible = selectionMode && showMessageOption && selectedItems.size == 1 && currentTab == AppTab.CLEANER,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    val selectedItem = remember(selectedItems, mediaList) {
                        mediaList.find { it.uri == selectedItems.first() }
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Associated text message:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = selectedItem?.messageBody ?: "(No text message found for this media item)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentTab == AppTab.CLEANER,
                    onClick = { currentTab = AppTab.CLEANER },
                    icon = { Icon(Icons.Default.Refresh, null) },
                    label = { Text("Cleaner") },
                )
                NavigationBarItem(
                    selected = currentTab == AppTab.TRASH,
                    onClick = { currentTab = AppTab.TRASH },
                    icon = { Icon(Icons.Default.History, null) },
                    label = { Text("Trash") },
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!isDefault) {
                // Feature gating: Requires Default SMS role
                DefaultAppExplanation(onRequestDefaultSms)
            } else if (!permissionsState.allPermissionsGranted) {
                // Feature gating: Requires SMS and Media storage permissions
                PermissionRequestScreen(permissionsState)
            } else {
                // Main content navigation
                when (currentTab) {
                    AppTab.CLEANER -> CleanerScreen(
                        groupedMedia = groupedMedia,
                        selectionMode = selectionMode,
                        onSelectionModeChange = { selectionMode = it },
                        selectedItems = selectedItems,
                        onSelectedItemsChange = { 
                            selectedItems = it
                            // Hide message body if user selects multiple items
                            if (it.size != 1) showMessageOption = false
                        },
                        imageLoader = imageLoader,
                    )
                    AppTab.TRASH -> TrashScreen(
                        trashedItems = trashedItems,
                        trashDao = trashDao,
                        context = context,
                        coroutineScope = coroutineScope,
                    )
                }
            }
        }
    }

    // Confirmation dialog for cleanup actions (Delete vs Trash, Backup option)
    if (showConfirmDeleteDialog) {
        ConfirmDeletionDialog(
            deleteAction = deleteAction,
            deleteAttachmentsOnly = deleteAttachmentsOnly,
            onDeleteAttachmentsOnlyChange = { deleteAttachmentsOnly = it },
            backupBeforeDelete = backupBeforeDelete,
            onBackupBeforeDeleteChange = { backupBeforeDelete = it },
            onConfirm = {
                showConfirmDeleteDialog = false
                startDeletion(
                    context, 
                    workManager, 
                    deleteAction, 
                    deleteAttachmentsOnly, 
                    backupBeforeDelete,
                    { currentWorkId = it }, 
                    { showDeleteProgressScreen = true },
                )
                selectionMode = false
                selectedItems = emptySet()
                deleteAction = null
                backupBeforeDelete = false
                showMessageOption = false
            },
            onDismiss = {
                showConfirmDeleteDialog = false
                deleteAction = null
            },
        )
    }

    // Alert for partial cancellations (e.g. user stops background work midway)
    if (showCancelledDialog) {
        AlertDialog(
            onDismissRequest = { showCancelledDialog = false },
            title = { Text("Cleanup Partial") },
            text = { Text("Operation cancelled. ${deletedCount} items were processed.") },
            confirmButton = { Button(onClick = { showCancelledDialog = false }) { Text("OK") } },
        )
    }
}

/** Component showing real-time progress of moving files to trash or deleting. */
@Composable
fun DeletionProgressOverlay(
    totalToDelete: Int,
    deletedCount: Int,
    deletionLog: List<String>,
    isDeleteOnly: Boolean,
    onCancel: () -> Unit,
) {
    Scaffold { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = if (isDeleteOnly) "Deleting Permanently..." else "Cleaning Up...",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = 32.dp, bottom = 24.dp),
                )
                
                LinearProgressIndicator(
                    progress = { if (totalToDelete > 0) deletedCount.toFloat() / totalToDelete.toFloat() else 0f },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                )
                
                Text(
                    text = "$deletedCount / $totalToDelete items processed",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
                )

                val listState = rememberLazyListState()
                LaunchedEffect(deletionLog.size) {
                    if (deletionLog.isNotEmpty()) {
                        listState.animateScrollToItem(deletionLog.size - 1)
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.05f)),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                    ) {
                        items(deletionLog) { logEntry ->
                            val prefix = if (isDeleteOnly) "Deleting: " else "Trashing: "
                            Text(
                                text = "$prefix$logEntry",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }

                Text(
                    text = if (isDeleteOnly) "Attachments are being removed directly from message storage." else "Items are being moved to the internal Trash Can for safety.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 24.dp),
                )

                Button(
                    onClick = onCancel,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Cancel Operation")
                }
            }
        }
    }
}

/** Onboarding screen explaining why default SMS role is needed. */
@Composable
fun DefaultAppExplanation(onRequestDefaultSms: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(32.dp),
    ) {
        Icon(Icons.Default.Settings, null, Modifier.size(64.dp).padding(bottom = 16.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Set as Default SMS App", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text("To safely move messages to the Trash, Android requires this app to be your default SMS app.", textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("IMPORTANT: While this app is active, Google Messages and other apps cannot send or receive texts. You should switch back once finished.", 
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequestDefaultSms) { Text("Set as Default") }
    }
}

/** Onboarding screen for runtime permissions. */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionRequestScreen(state: com.google.accompanist.permissions.MultiplePermissionsState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxSize().padding(32.dp)) {
        Icon(Icons.Default.Info, null, Modifier.size(64.dp).padding(bottom = 16.dp))
        Text("Permissions Required", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text("Storage permissions are required to scan and backup media. Please grant them to continue.", textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = { state.launchMultiplePermissionRequest() }) { Text("Grant Permissions") }
    }
}

/** Grid view showing all media items found in MMS messages. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CleanerScreen(
    groupedMedia: List<GroupedMediaItems>,
    selectionMode: Boolean,
    onSelectionModeChange: (Boolean) -> Unit,
    selectedItems: Set<Uri>,
    onSelectedItemsChange: (Set<Uri>) -> Unit,
    imageLoader: ImageLoader,
) {
    val context = LocalContext.current
    if (groupedMedia.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No media found in MMS messages.")
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 128.dp),
            contentPadding = PaddingValues(4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            groupedMedia.forEach { group ->
                // Group Header (Month/Year) - Clickable for bulk selection
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = group.groupTitle,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier
                            .padding(8.dp)
                            .clickable {
                                if (!selectionMode) onSelectionModeChange(true)
                                val allSelected = selectedItems.containsAll(group.uris)
                                onSelectedItemsChange(if (allSelected) selectedItems - group.uris else selectedItems + group.uris)
                            },
                    )
                }
                // Media Grid Items
                items(group.items, key = { it.uri.toString() }) { item ->
                    val dateFormat = remember { DateTimeFormatter.ofPattern("MM/dd HH:mm", Locale.getDefault()) }
                    val request = ImageRequest.Builder(context).data(item.uri).size(300, 300).build()
                    
                    Card(
                        modifier = Modifier
                            .padding(4.dp)
                            .combinedClickable(
                                onClick = {
                                    if (selectionMode) {
                                        onSelectedItemsChange(if (selectedItems.contains(item.uri)) selectedItems - item.uri else selectedItems + item.uri)
                                    }
                                },
                                onLongClick = {
                                    if (!selectionMode) {
                                        onSelectionModeChange(true)
                                        onSelectedItemsChange(selectedItems + item.uri)
                                    }
                                },
                            )
                            .border(3.dp, if (selectedItems.contains(item.uri)) MaterialTheme.colorScheme.primary else Color.Transparent),
                        elevation = CardDefaults.cardElevation(2.dp),
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            AsyncImage(model = request, imageLoader = imageLoader, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.aspectRatio(1f))
                            if (item.mimeType.startsWith("video/")) {
                                Icon(Icons.Default.PlayCircle, "Video", Modifier.align(Alignment.Center).size(48.dp), tint = Color.White.copy(alpha = 0.7f))
                            }
                            Text(
                                text = dateFormat.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(item.date), ZoneId.systemDefault())),
                                modifier = Modifier.align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black))).padding(4.dp),
                                color = Color.White, fontSize = 10.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** List view showing items currently in the app's internal trash. */
@Composable
fun TrashScreen(
    trashedItems: List<TrashedItem>,
    trashDao: com.ubermicrostudios.textimagecleaner.data.TrashDao,
    context: Context,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
) {
    if (trashedItems.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Trash is empty")
        }
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(trashedItems, key = { it.uriString }) { item ->
                var showMessageBody by remember { mutableStateOf(false) }
                
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                            .clickable { showMessageBody = !showMessageBody }, 
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val file = File(context.filesDir, "trash/${item.fileName}")
                        AsyncImage(model = file, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).border(1.dp, Color.Gray))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.mimeType, style = MaterialTheme.typography.labelSmall)
                            val date = LocalDateTime.ofInstant(Instant.ofEpochMilli(item.trashedDate), ZoneId.systemDefault())
                            Text("Trashed: ${date.format(DateTimeFormatter.ofPattern("MMM dd, HH:mm"))}", style = MaterialTheme.typography.bodySmall)
                            if (item.messageBody != null) {
                                Text("Click to view message", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        // Restore to Gallery Button
                        IconButton(onClick = {
                            coroutineScope.launch {
                                MediaUtils.restoreToGallery(context, item)
                                trashDao.delete(item)
                                file.delete()
                                Toast.makeText(context, "Restored to Gallery", Toast.LENGTH_SHORT).show()
                            }
                        }) { Icon(Icons.Default.Restore, "Restore") }
                        // Delete Permanently Button
                        IconButton(onClick = {
                            coroutineScope.launch {
                                trashDao.delete(item)
                                file.delete()
                            }
                        }) { Icon(Icons.Default.DeleteForever, "Delete Permanently", tint = MaterialTheme.colorScheme.error) }
                    }
                    
                    // Expands to show captured text message context
                    AnimatedVisibility(visible = showMessageBody) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                text = item.messageBody ?: "(No message text found)",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

/** Dialog for confirming cleanup actions. Includes safety warnings for permanent deletion. */
@Composable
fun ConfirmDeletionDialog(
    deleteAction: DeleteAction?,
    deleteAttachmentsOnly: Boolean,
    onDeleteAttachmentsOnlyChange: (Boolean) -> Unit,
    backupBeforeDelete: Boolean,
    onBackupBeforeDeleteChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm Cleanup") },
        text = {
            Column {
                val message = when (deleteAction) {
                    is DeleteAction.BySelection -> {
                        if (deleteAttachmentsOnly) "Permanently delete ${deleteAction.uris.size} attachments?"
                        else "Move ${deleteAction.uris.size} items to Trash?"
                    }
                    is DeleteAction.EmptyMessages -> "Delete all empty text message threads?"
                    else -> ""
                }
                Text(message)
                
                // Safety warning when bypassing the internal trash
                if (deleteAction is DeleteAction.BySelection && deleteAttachmentsOnly) {
                    Text(
                        "Warning: These will NOT be moved to Trash and cannot be restored by this app.", 
                        color = MaterialTheme.colorScheme.error, 
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                if (deleteAction is DeleteAction.BySelection) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(deleteAttachmentsOnly, onDeleteAttachmentsOnlyChange)
                        Text("Delete attachments only (keep text)")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(backupBeforeDelete, onBackupBeforeDeleteChange)
                        Text("Backup to Gallery before delete")
                    }
                }
            }
        },
        confirmButton = { 
            Button(
                onClick = onConfirm,
                colors = if (deleteAttachmentsOnly) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
            ) { 
                Text(if (deleteAttachmentsOnly) "Delete Permanently" else "Move to Trash") 
            } 
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Helper function to prepare selection data and enqueue a background DeletionWorker. */
private fun startDeletion(
    context: Context,
    workManager: WorkManager,
    action: DeleteAction?,
    attachmentsOnly: Boolean,
    backupBeforeDelete: Boolean,
    onId: (UUID) -> Unit,
    onShow: () -> Unit,
) {
    if (action == null) return
    val workBuilder = OneTimeWorkRequestBuilder<DeletionWorker>()
    if (action is DeleteAction.BySelection) {
        val file = File(context.cacheDir, "uris_to_delete.txt")
        file.writeText(action.uris.joinToString("\n"))
        workBuilder.setInputData(workDataOf(
            DeletionWorker.KEY_URIS_FILE_PATH to file.absolutePath,
            DeletionWorker.KEY_DELETE_ATTACHMENTS_ONLY to attachmentsOnly,
            DeletionWorker.KEY_BACKUP_BEFORE_DELETE to backupBeforeDelete,
        ))
    } else {
        workBuilder.setInputData(workDataOf(DeletionWorker.KEY_DELETE_EMPTY_MESSAGES to true))
    }
    val request = workBuilder.build()
    workManager.enqueue(request)
    onId(request.id)
    onShow()
}
