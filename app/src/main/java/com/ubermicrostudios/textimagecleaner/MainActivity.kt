package com.ubermicrostudios.textimagecleaner

import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkInfo
import androidx.work.WorkManager
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.ubermicrostudios.textimagecleaner.data.AppDatabase
import com.ubermicrostudios.textimagecleaner.ui.CleanerScreen
import com.ubermicrostudios.textimagecleaner.ui.ConfirmDeletionDialog
import com.ubermicrostudios.textimagecleaner.ui.DateRangeDeleteDialog
import com.ubermicrostudios.textimagecleaner.ui.DefaultAppExplanation
import com.ubermicrostudios.textimagecleaner.ui.DeletionProgressOverlay
import com.ubermicrostudios.textimagecleaner.ui.PermissionRequestScreen
import com.ubermicrostudios.textimagecleaner.ui.SelectionActionBar
import com.ubermicrostudios.textimagecleaner.ui.SelectionDetailsPanel
import com.ubermicrostudios.textimagecleaner.ui.TrashScreen
import com.ubermicrostudios.textimagecleaner.ui.settings.SettingsScreen
import com.ubermicrostudios.textimagecleaner.ui.theme.TextImageCleanerTheme
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var isDefault by mutableStateOf(false)

    private val roleRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        isDefault = isDefaultSmsApp()
        Toast.makeText(
            this,
            if (isDefault) "Now default SMS app!" else "Default SMS not granted",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        isDefault = isDefaultSmsApp()

        setContent {
            TextImageCleanerTheme {
                Surface(Modifier.fillMaxSize()) {
                    val viewModel: MainViewModel = viewModel {
                        MainViewModel(applicationContext)
                    }
                    SmsAppScreen(
                        viewModel = viewModel,
                        isDefault = isDefault,
                        onRequestDefaultSms = { requestDefaultSmsRole() },
                        onRequestSystemDefaultSms = { openDefaultSmsSettings() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isDefault = isDefaultSmsApp()
    }

    private fun isDefaultSmsApp(): Boolean {
        val roleManager = getSystemService(RoleManager::class.java)
        return roleManager?.isRoleHeld(RoleManager.ROLE_SMS) ?: false
    }

    private fun requestDefaultSmsRole() {
        val roleManager = getSystemService(RoleManager::class.java)
        if (roleManager != null &&
            roleManager.isRoleAvailable(RoleManager.ROLE_SMS) &&
            !roleManager.isRoleHeld(RoleManager.ROLE_SMS)
        ) {
            roleRequestLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS))
        }
    }

    private fun openDefaultSmsSettings() {
        startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
    }
}

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SmsAppScreen(
    viewModel: MainViewModel,
    isDefault: Boolean,
    onRequestDefaultSms: () -> Unit,
    onRequestSystemDefaultSms: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val workManager = remember { WorkManager.getInstance(context) }
    val database = remember { AppDatabase.getDatabase(context) }
    val trashDao = database.trashDao()

    val trashedItems by trashDao.getAllTrashedItems().collectAsState(initial = emptyList())
    val totalTrashedSize by trashDao.getTotalTrashedSize().collectAsState(initial = 0L)

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

    // Optional: only used to resolve phone/email → contact names in the info panel.
    val contactsPermission = rememberPermissionState(android.Manifest.permission.READ_CONTACTS)
    var contactsRequestAttempted by remember { mutableStateOf(false) }

    // After Contacts is granted, reload open info panel so names replace numbers.
    LaunchedEffect(contactsPermission.status.isGranted) {
        if (contactsPermission.status.isGranted && viewModel.showMessageOption) {
            viewModel.refreshSelectedDetails(clearCache = true)
        }
    }

    val imageLoader = remember {
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .memoryCache {
                MemoryCache.Builder(context).maxSizePercent(0.25).build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(64L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
    }

    val canBrowseMedia = isDefault && permissionsState.allPermissionsGranted
    var pendingDeleteAction by remember { mutableStateOf<DeleteAction?>(null) }
    var showEmptyTrashConfirm by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showDateRangeDelete by remember { mutableStateOf(false) }
    val backupFolderName by viewModel.backupFolderName.collectAsState()

    LaunchedEffect(canBrowseMedia) {
        if (canBrowseMedia) viewModel.loadMedia()
    }

    // Observe deletion work progress / completion.
    LaunchedEffect(viewModel.currentWorkId) {
        val id = viewModel.currentWorkId ?: return@LaunchedEffect
        workManager.getWorkInfoByIdFlow(id).collect { info ->
            if (info == null) return@collect
            val progress = info.progress
            viewModel.updateProgress(
                total = progress.getInt(DeletionWorker.KEY_TOTAL_COUNT, 0),
                deleted = progress.getInt(DeletionWorker.KEY_DELETED_COUNT, 0),
                lastItem = progress.getString(DeletionWorker.KEY_LAST_ITEM_INFO)
            )
            if (info.state.isFinished) {
                val cancelled = info.state == WorkInfo.State.CANCELLED
                viewModel.onWorkFinished(wasCancelled = cancelled)
                viewModel.exitSelectionMode()
                viewModel.loadMedia()
                if (!cancelled) {
                    Toast.makeText(context, "Cleanup finished", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    if (viewModel.showDeleteProgressScreen) {
        DeletionProgressOverlay(
            totalToDelete = viewModel.totalToDelete,
            deletedCount = viewModel.deletedCount,
            deletionLog = viewModel.deletionLog,
            isDeleteOnly = viewModel.deleteAttachmentsOnly,
            onCancel = { viewModel.cancelWork() }
        )
        return
    }

    // System back / predictive back stack (dialogs → selection → settings).
    BackHandler(enabled = pendingDeleteAction != null) { pendingDeleteAction = null }
    BackHandler(enabled = showEmptyTrashConfirm) { showEmptyTrashConfirm = false }
    BackHandler(enabled = showDateRangeDelete) { showDateRangeDelete = false }
    BackHandler(enabled = viewModel.selectionMode && !showSettings) {
        viewModel.exitSelectionMode()
    }

    var settingsBackProgress by remember { mutableFloatStateOf(0f) }
    var settingsBackFromLeft by remember { mutableStateOf(true) }

    PredictiveBackHandler(enabled = showSettings) { progress ->
        try {
            progress.collect { event ->
                settingsBackProgress = event.progress
                settingsBackFromLeft = event.swipeEdge == BackEventCompat.EDGE_LEFT
            }
            showSettings = false
            settingsBackProgress = 0f
        } catch (e: CancellationException) {
            settingsBackProgress = 0f
            throw e
        }
    }

    val mediaCount = viewModel.mediaList.size
    val filterForStats = viewModel.mediaTypeFilter
    val shownCount = viewModel.filteredMediaList.size
    val statsLabel = when (viewModel.currentTab) {
        AppTab.CLEANER -> {
            val imgs = viewModel.mediaList.count {
                it.mimeType.lowercase(Locale.US).startsWith("image/")
            }
            val vids = viewModel.mediaList.count {
                it.mimeType.lowercase(Locale.US).startsWith("video/")
            }
            val filterHint = when (filterForStats) {
                MediaTypeFilter.ALL -> "showing all"
                MediaTypeFilter.IMAGES -> "images only"
                MediaTypeFilter.VIDEOS -> "videos only"
            }
            "$shownCount shown ($filterHint) · $mediaCount total · $imgs img · $vids vid"
        }
        AppTab.TRASH -> formatBytes(totalTrashedSize) + " in trash"
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                if (viewModel.currentTab == AppTab.CLEANER) "Cleaner" else "Trash Can"
                            )
                            if (canBrowseMedia) {
                                Text(
                                    statsLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    actions = {
                        if (canBrowseMedia && !viewModel.selectionMode) {
                            if (viewModel.currentTab == AppTab.CLEANER) {
                                val filter = viewModel.mediaTypeFilter
                                val active = MaterialTheme.colorScheme.primary
                                val inactive = MaterialTheme.colorScheme.onSurfaceVariant
                                IconButton(onClick = { viewModel.mediaTypeFilter = MediaTypeFilter.ALL }) {
                                    Icon(
                                        Icons.Default.PhotoLibrary,
                                        contentDescription = "Show all media",
                                        tint = if (filter == MediaTypeFilter.ALL) active else inactive
                                    )
                                }
                                IconButton(onClick = { viewModel.mediaTypeFilter = MediaTypeFilter.IMAGES }) {
                                    Icon(
                                        Icons.Default.Image,
                                        contentDescription = "Images only",
                                        tint = if (filter == MediaTypeFilter.IMAGES) active else inactive
                                    )
                                }
                                IconButton(onClick = { viewModel.mediaTypeFilter = MediaTypeFilter.VIDEOS }) {
                                    Icon(
                                        Icons.Default.Videocam,
                                        contentDescription = "Videos only",
                                        tint = if (filter == MediaTypeFilter.VIDEOS) active else inactive
                                    )
                                }
                                // Calendar before Refresh
                                IconButton(
                                    onClick = { showDateRangeDelete = true },
                                    enabled = viewModel.mediaList.isNotEmpty()
                                ) {
                                    Icon(
                                        Icons.Filled.CalendarMonth,
                                        contentDescription = "Delete by date range"
                                    )
                                }
                                IconButton(
                                    onClick = { viewModel.loadMedia() },
                                    enabled = !viewModel.isLoadingMedia
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                                }
                            } else {
                                IconButton(
                                    onClick = { showEmptyTrashConfirm = true },
                                    enabled = trashedItems.isNotEmpty()
                                ) {
                                    Icon(Icons.Default.DeleteSweep, contentDescription = "Empty trash")
                                }
                            }
                            IconButton(onClick = {
                                settingsBackProgress = 0f
                                showSettings = true
                            }) {
                                Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                            }
                        } else if (!viewModel.selectionMode) {
                            IconButton(onClick = {
                                settingsBackProgress = 0f
                                showSettings = true
                            }) {
                                Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                            }
                        }
                    }
                )

                if (viewModel.selectionMode && viewModel.currentTab == AppTab.CLEANER) {
                    SelectionActionBar(
                        selectedCount = viewModel.selectedItems.size,
                        selectedSizeLabel = null,
                        showMessageToggle = viewModel.selectedItems.size == 1,
                        messageVisible = viewModel.showMessageOption,
                        onClose = { viewModel.exitSelectionMode() },
                        onToggleMessage = { viewModel.toggleShowMessageOption() },
                        onDelete = {
                            if (viewModel.selectedItems.isNotEmpty()) {
                                pendingDeleteAction =
                                    DeleteAction.BySelection(viewModel.selectedItems.toList())
                            }
                        }
                    )
                }

                if (viewModel.isLoadingMedia && viewModel.mediaList.isNotEmpty()) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = viewModel.currentTab == AppTab.CLEANER,
                    onClick = { viewModel.currentTab = AppTab.CLEANER },
                    icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                    label = { Text("Cleaner") }
                )
                NavigationBarItem(
                    selected = viewModel.currentTab == AppTab.TRASH,
                    onClick = { viewModel.currentTab = AppTab.TRASH },
                    icon = { Icon(Icons.Default.History, contentDescription = null) },
                    label = { Text("Trash") }
                )
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                !isDefault -> DefaultAppExplanation(onRequestDefaultSms)
                !permissionsState.allPermissionsGranted -> PermissionRequestScreen(permissionsState)
                viewModel.currentTab == AppTab.TRASH -> TrashScreen(
                    trashedItems = trashedItems,
                    trashDao = trashDao,
                    context = context,
                    coroutineScope = coroutineScope
                )
                viewModel.isLoadingMedia && viewModel.mediaList.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text(
                                "Scanning MMS media…",
                                modifier = Modifier.padding(top = 16.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                else -> {
                    Column(Modifier.fillMaxSize()) {
                        if (viewModel.showMessageOption && viewModel.selectedItems.size == 1) {
                            val contactsGranted = contactsPermission.status.isGranted
                            val permanentlyDenied = contactsRequestAttempted &&
                                !contactsGranted &&
                                !contactsPermission.status.shouldShowRationale
                            SelectionDetailsPanel(
                                isLoading = viewModel.isLoadingDetails,
                                details = viewModel.selectedDetails,
                                contactsPermissionGranted = contactsGranted,
                                onRequestContactsPermission = {
                                    contactsRequestAttempted = true
                                    contactsPermission.launchPermissionRequest()
                                },
                                onOpenAppSettings = {
                                    context.startActivity(
                                        Intent(
                                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            android.net.Uri.fromParts(
                                                "package",
                                                context.packageName,
                                                null
                                            )
                                        )
                                    )
                                },
                                showOpenSettingsForContacts = permanentlyDenied
                            )
                        }
                        // Read filter + list state here so Compose invalidates on filter taps.
                        val filter = viewModel.mediaTypeFilter
                        val mediaSnapshot = viewModel.mediaList
                        CleanerScreen(
                            groupedMedia = viewModel.groupedMedia,
                            selectionMode = viewModel.selectionMode,
                            onSelectionModeChange = {
                                if (it) viewModel.enterSelectionMode() else viewModel.exitSelectionMode()
                            },
                            selectedItems = viewModel.selectedItems,
                            onSelectedItemsChange = { viewModel.selectedItems = it },
                            imageLoader = imageLoader,
                            // unused but forces recomposition if list/filter identity changes
                            contentKey = "${filter.name}-${mediaSnapshot.size}"
                        )
                    }
                }
            }
        }
    }

    // Settings as overlay so predictive back can peek the main UI underneath.
    if (showSettings) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val width = size.width.coerceAtLeast(1f)
                    val dir = if (settingsBackFromLeft) 1f else -1f
                    translationX = dir * width * settingsBackProgress
                    alpha = 1f - (settingsBackProgress * 0.15f)
                    val scale = 1f - (settingsBackProgress * 0.05f)
                    scaleX = scale
                    scaleY = scale
                },
            color = MaterialTheme.colorScheme.background
        ) {
            SettingsScreen(
                isDefaultSms = isDefault,
                backupFolderName = backupFolderName,
                contactsPermissionGranted = contactsPermission.status.isGranted,
                onBack = {
                    settingsBackProgress = 0f
                    showSettings = false
                },
                onRequestDefaultSmsRole = onRequestDefaultSms,
                onOpenSystemDefaultApps = onRequestSystemDefaultSms,
                onRequestContactsPermission = {
                    contactsRequestAttempted = true
                    contactsPermission.launchPermissionRequest()
                },
                onOpenAppSettings = {
                    context.startActivity(
                        Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.fromParts("package", context.packageName, null)
                        )
                    )
                },
                onSaveBackupFolder = { name ->
                    viewModel.saveBackupFolderName(name)
                    Toast.makeText(context, "Backup folder saved", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
    } // Box

    if (showDateRangeDelete) {
        // Recompute when list/filter changes so only months with media are selectable.
        val months = remember(viewModel.mediaList, viewModel.mediaTypeFilter) {
            viewModel.monthsWithMedia()
        }
        val years = remember(months) { viewModel.mediaYearRange() }
        DateRangeDeleteDialog(
            monthsWithMedia = months,
            yearRange = years,
            matchCountForRange = { start, end -> viewModel.countInDateRange(start, end) },
            onDismiss = { showDateRangeDelete = false },
            onConfirmRange = { start, end ->
                showDateRangeDelete = false
                // Single snapshot of every matching URI for this range (first pass is complete).
                val items = viewModel.itemsInDateRange(start, end)
                val uris = items.map { it.uri }.distinct()
                if (uris.isEmpty()) {
                    Toast.makeText(
                        context,
                        "No media in that date range (with current filter)",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        context,
                        "${uris.size} item(s) ready to delete",
                        Toast.LENGTH_SHORT
                    ).show()
                    pendingDeleteAction = DeleteAction.BySelection(uris)
                }
            }
        )
    }

    pendingDeleteAction?.let { action ->
        ConfirmDeletionDialog(
            deleteAction = action,
            deleteAttachmentsOnly = viewModel.deleteAttachmentsOnly,
            onDeleteAttachmentsOnlyChange = {
                viewModel.setDeleteOptions(it, viewModel.backupBeforeDelete)
            },
            backupBeforeDelete = viewModel.backupBeforeDelete,
            onBackupBeforeDeleteChange = {
                viewModel.setDeleteOptions(viewModel.deleteAttachmentsOnly, it)
            },
            onConfirm = {
                val toRun = pendingDeleteAction
                pendingDeleteAction = null
                if (toRun != null) viewModel.startDeletion(toRun)
            },
            onDismiss = { pendingDeleteAction = null }
        )
    }

    if (showEmptyTrashConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showEmptyTrashConfirm = false },
            title = { Text("Empty trash?") },
            text = { Text("Permanently delete all ${trashedItems.size} items in Trash? This cannot be undone.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showEmptyTrashConfirm = false
                    coroutineScope.launch {
                        withContext(Dispatchers.IO) {
                            val dir = java.io.File(context.filesDir, "trash")
                            dir.listFiles()?.forEach { it.delete() }
                            trashDao.deleteAll()
                        }
                        Toast.makeText(context, "Trash emptied", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Empty trash") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showEmptyTrashConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}
