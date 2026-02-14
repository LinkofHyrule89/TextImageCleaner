package com.ubermicrostudios.textimagecleaner

import android.app.role.RoleManager
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Telephony
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
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
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

data class MediaItem(val uri: Uri, val date: Long, val mimeType: String, val size: Long = 0)
data class GroupedMediaItems(val groupTitle: String, val items: List<MediaItem>, val uris: Set<Uri>)

sealed class DeleteAction {
    data class BySelection(val uris: Set<Uri>) : DeleteAction()
    object EmptyMessages : DeleteAction()
}

enum class AppTab { CLEANER, TRASH }

class MainActivity : ComponentActivity() {

    private var isDefault by mutableStateOf(false)

    private val roleRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { isDefault = isDefaultSmsApp()
        if (isDefault) {
            Toast.makeText(this, "Now default SMS app!", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Default SMS not granted", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        isDefault = isDefaultSmsApp()

        setContent {
            TextImageCleanerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SmsAppScreen(
                        onRequestDefaultSms = { requestDefaultSmsRole() },
                        onRequestSystemDefaultSms = { openDefaultSmsSettings() },
                        isDefault = isDefault
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
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            roleManager.isRoleHeld(RoleManager.ROLE_SMS)
        } else {
            Telephony.Sms.getDefaultSmsPackage(this) == packageName
        }
    }

    private fun requestDefaultSmsRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_SMS) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_SMS)
            ) {
                val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
                roleRequestLauncher.launch(intent)
            }
        } else {
            val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
            intent.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
            startActivity(intent)
        }
    }

    private fun openDefaultSmsSettings() {
        val intent = Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        startActivity(intent)
    }
}

@OptIn(
    ExperimentalPermissionsApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class
)
@Composable
fun SmsAppScreen(
    onRequestDefaultSms: () -> Unit,
    onRequestSystemDefaultSms: () -> Unit,
    isDefault: Boolean
) {
    val context = LocalContext.current
    val contentResolver = context.contentResolver
    val coroutineScope = rememberCoroutineScope()
    val workManager = remember { WorkManager.getInstance(context) }
    
    val database = remember { AppDatabase.getDatabase(context) }
    val trashDao = database.trashDao()
    val trashedItems by trashDao.getAllTrashedItems().collectAsState(initial = emptyList())
    val totalTrashedSize by trashDao.getTotalTrashedSize().collectAsState(initial = 0L)

    val smsPermissionsState = rememberMultiplePermissionsState(
        listOf(
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.POST_NOTIFICATIONS
        )
    )

    var currentTab by remember { mutableStateOf(AppTab.CLEANER) }
    var mediaList by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var groupedMedia by remember { mutableStateOf<List<GroupedMediaItems>>(emptyList()) }
    var isRefreshing by remember { mutableStateOf(false) }

    var showDatePicker by remember { mutableStateOf(false) }
    var showConfirmDeleteDialog by remember { mutableStateOf(false) }
    var showReviewDialog by remember { mutableStateOf(false) }
    var itemsToDelete by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var selectedItemsForDeletion by remember { mutableStateOf<Set<Uri>>(emptySet()) }

    var deleteAction by remember { mutableStateOf<DeleteAction?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedItems by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    var deleteAttachmentsOnly by remember { mutableStateOf(false) }

    // State for deletion progress
    var showDeleteProgressScreen by remember { mutableStateOf(false) }
    var showCancelledDialog by remember { mutableStateOf(false) }
    var currentWorkId by remember { mutableStateOf<UUID?>(null) }
    
    // Log for progress screen
    val deletionLog = remember { mutableStateListOf<String>() }

    val workInfoState = remember(currentWorkId) {
        currentWorkId?.let { workManager.getWorkInfoByIdLiveData(it) }
    }?.observeAsState()
    
    val workInfo = workInfoState?.value

    var deletedCount by remember { mutableStateOf(0) }
    var totalToDelete by remember { mutableStateOf(0) }

    val imageLoader = remember {
        ImageLoader.Builder(context)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }

    fun updateMedia() {
        coroutineScope.launch {
            isRefreshing = true
            val list = loadMmsMedia(contentResolver)
            mediaList = list
            groupedMedia = list.groupBy {
                val date = LocalDateTime.ofInstant(Instant.ofEpochMilli(it.date), ZoneId.systemDefault())
                YearMonth.from(date)
            }.toSortedMap(Comparator.reverseOrder())
                .map { (yearMonth, items) ->
                    val title = yearMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
                    GroupedMediaItems(title, items, items.map { it.uri }.toSet())
                }
            isRefreshing = false
        }
    }

    LaunchedEffect(workInfo) {
        if (workInfo != null) {
            val progress = workInfo.progress
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

            if (workInfo.state == WorkInfo.State.RUNNING || workInfo.state == WorkInfo.State.ENQUEUED) {
                showDeleteProgressScreen = true
            }

            if (workInfo.state.isFinished) {
                showDeleteProgressScreen = false
                currentWorkId = null
                deletionLog.clear()
                
                if (workInfo.state == WorkInfo.State.CANCELLED) {
                    showCancelledDialog = true
                } else {
                    updateMedia()
                    Toast.makeText(context, "Items moved to Trash", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LaunchedEffect(smsPermissionsState.allPermissionsGranted, isDefault) {
        if (smsPermissionsState.allPermissionsGranted && isDefault) {
            updateMedia()
        }
    }

    if (showDeleteProgressScreen) {
        Scaffold { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Cleaning Up...",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.padding(top = 32.dp, bottom = 24.dp)
                    )
                    
                    LinearProgressIndicator(
                        progress = { if (totalToDelete > 0) deletedCount.toFloat() / totalToDelete.toFloat() else 0f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                    )
                    
                    Text(
                        text = "$deletedCount / $totalToDelete items processed",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
                    )

                    // Scrolling Log of deletions
                    val listState = rememberLazyListState()
                    LaunchedEffect(deletionLog.size) {
                        if (deletionLog.isNotEmpty()) {
                            listState.animateScrollToItem(deletionLog.size - 1)
                        }
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(bottom = 24.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.05f))
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().padding(8.dp)
                        ) {
                            items(deletionLog) { logEntry ->
                                Text(
                                    text = "Trashing: $logEntry",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.Gray,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Text(
                        text = "Items are being moved to the internal Trash Can for safety.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )

                    Button(
                        onClick = {
                            currentWorkId?.let { workManager.cancelWorkById(it) }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Cancel Operation")
                    }
                }
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        if (selectionMode) {
                            Text("${selectedItems.size} selected")
                        } else {
                            Text(if (currentTab == AppTab.CLEANER) "Cleaner" else "Trash Can")
                            if (currentTab == AppTab.CLEANER && mediaList.isNotEmpty()) {
                                val totalSizeMb = mediaList.sumOf { it.size } / (1024 * 1024)
                                Text("${mediaList.size} items • ${totalSizeMb}MB", style = MaterialTheme.typography.labelSmall)
                            } else if (currentTab == AppTab.TRASH) {
                                val trashSizeMb = (totalTrashedSize ?: 0L) / (1024 * 1024)
                                Text("${trashedItems.size} items • ${trashSizeMb}MB stored", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                },
                navigationIcon = {
                    if (selectionMode) {
                        IconButton(onClick = {
                            selectionMode = false
                            selectedItems = emptySet()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel Selection")
                        }
                    }
                },
                actions = {
                    if (isDefault && smsPermissionsState.allPermissionsGranted) {
                        if (selectionMode) {
                            IconButton(onClick = {
                                if (selectedItems.isNotEmpty()){
                                    deleteAction = DeleteAction.BySelection(selectedItems)
                                    showConfirmDeleteDialog = true
                                }
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Move to Trash")
                            }
                        } else if (currentTab == AppTab.CLEANER) {
                            IconButton(onClick = { showDatePicker = true }) {
                                Icon(Icons.Default.Event, contentDescription = "Filter by Date")
                            }
                            IconButton(onClick = { updateMedia() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                            }
                            IconButton(onClick = {
                                deleteAction = DeleteAction.EmptyMessages
                                showConfirmDeleteDialog = true
                            }) {
                                Icon(Icons.Outlined.CleaningServices, contentDescription = "Delete Empty Messages")
                            }
                        } else {
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
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentTab == AppTab.CLEANER,
                    onClick = { currentTab = AppTab.CLEANER },
                    icon = { Icon(Icons.Default.Refresh, null) },
                    label = { Text("Cleaner") }
                )
                NavigationBarItem(
                    selected = currentTab == AppTab.TRASH,
                    onClick = { currentTab = AppTab.TRASH },
                    icon = { Icon(Icons.Default.History, null) },
                    label = { Text("Trash") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!isDefault) {
                DefaultAppExplanation(onRequestDefaultSms)
            } else if (!smsPermissionsState.allPermissionsGranted) {
                PermissionRequestScreen(smsPermissionsState)
            } else {
                when (currentTab) {
                    AppTab.CLEANER -> CleanerScreen(
                        groupedMedia = groupedMedia,
                        selectionMode = selectionMode,
                        onSelectionModeChange = { selectionMode = it },
                        selectedItems = selectedItems,
                        onSelectedItemsChange = { selectedItems = it },
                        imageLoader = imageLoader
                    )
                    AppTab.TRASH -> TrashScreen(
                        trashedItems = trashedItems,
                        trashDao = trashDao,
                        context = context,
                        coroutineScope = coroutineScope
                    )
                }
            }
        }
    }

    // Dialogs...
    if (showDatePicker) {
        val dateRangePickerState = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDatePicker = false
                        val start = dateRangePickerState.selectedStartDateMillis
                        val end = dateRangePickerState.selectedEndDateMillis
                        if (start != null && end != null) {
                            coroutineScope.launch {
                                itemsToDelete = mediaList.filter { it.date in start..end }
                                if (itemsToDelete.isNotEmpty()) {
                                    selectedItemsForDeletion = itemsToDelete.map { it.uri }.toSet()
                                    showReviewDialog = true
                                } else {
                                    Toast.makeText(context, "No items found", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    enabled = dateRangePickerState.selectedStartDateMillis != null && dateRangePickerState.selectedEndDateMillis != null
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DateRangePicker(state = dateRangePickerState)
        }
    }

    if (showReviewDialog) {
        ReviewItemsDialog(
            items = itemsToDelete,
            selectedUris = selectedItemsForDeletion,
            onSelectedUrisChange = { selectedItemsForDeletion = it },
            onConfirm = {
                showReviewDialog = false
                deleteAction = DeleteAction.BySelection(selectedItemsForDeletion)
                showConfirmDeleteDialog = true
            },
            onDismiss = { showReviewDialog = false }
        )
    }

    if (showConfirmDeleteDialog) {
        ConfirmDeletionDialog(
            deleteAction = deleteAction,
            deleteAttachmentsOnly = deleteAttachmentsOnly,
            onDeleteAttachmentsOnlyChange = { deleteAttachmentsOnly = it },
            onConfirm = {
                showConfirmDeleteDialog = false
                startDeletion(context, workManager, deleteAction, deleteAttachmentsOnly, { currentWorkId = it }, { showDeleteProgressScreen = true })
                selectionMode = false
                selectedItems = emptySet()
                deleteAction = null
                deleteAttachmentsOnly = false
            },
            onDismiss = {
                showConfirmDeleteDialog = false
                deleteAction = null
            }
        )
    }

    if (showCancelledDialog) {
        AlertDialog(
            onDismissRequest = { showCancelledDialog = false },
            title = { Text("Cleanup Partial") },
            text = { Text("Operation cancelled. $deletedCount items were moved to Trash.") },
            confirmButton = { Button(onClick = { showCancelledDialog = false }) { Text("OK") } }
        )
    }
}

@Composable
fun DefaultAppExplanation(onRequestDefaultSms: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(32.dp)
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

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionRequestScreen(state: com.google.accompanist.permissions.MultiplePermissionsState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxSize().padding(32.dp)) {
        Icon(Icons.Default.Info, null, Modifier.size(64.dp).padding(bottom = 16.dp))
        Text("Permissions Required", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text("SMS permission is required to access media. Please grant it to continue.", textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = { state.launchMultiplePermissionRequest() }) { Text("Grant Permissions") }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CleanerScreen(
    groupedMedia: List<GroupedMediaItems>,
    selectionMode: Boolean,
    onSelectionModeChange: (Boolean) -> Unit,
    selectedItems: Set<Uri>,
    onSelectedItemsChange: (Set<Uri>) -> Unit,
    imageLoader: ImageLoader
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
            modifier = Modifier.fillMaxSize()
        ) {
            groupedMedia.forEach { group ->
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
                            }
                    )
                }
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
                                }
                            )
                            .border(3.dp, if (selectedItems.contains(item.uri)) MaterialTheme.colorScheme.primary else Color.Transparent),
                        elevation = CardDefaults.cardElevation(2.dp)
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            AsyncImage(model = request, imageLoader = imageLoader, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.aspectRatio(1f))
                            if (item.mimeType.startsWith("video/")) {
                                Icon(Icons.Default.PlayCircle, "Video", Modifier.align(Alignment.Center).size(48.dp), tint = Color.White.copy(alpha = 0.7f))
                            }
                            Text(
                                text = dateFormat.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(item.date), ZoneId.systemDefault())),
                                modifier = Modifier.align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black))).padding(4.dp),
                                color = Color.White, fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TrashScreen(
    trashedItems: List<TrashedItem>,
    trashDao: com.ubermicrostudios.textimagecleaner.data.TrashDao,
    context: Context,
    coroutineScope: kotlinx.coroutines.CoroutineScope
) {
    if (trashedItems.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Trash is empty")
        }
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(trashedItems, key = { it.uriString }) { item ->
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val file = File(context.filesDir, "trash/${item.fileName}")
                    AsyncImage(model = file, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).border(1.dp, Color.Gray))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.mimeType, style = MaterialTheme.typography.labelSmall)
                        val date = LocalDateTime.ofInstant(Instant.ofEpochMilli(item.trashedDate), ZoneId.systemDefault())
                        Text("Trashed: ${date.format(DateTimeFormatter.ofPattern("MMM dd, HH:mm"))}", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = {
                        coroutineScope.launch {
                            restoreToGallery(context, item)
                            trashDao.delete(item)
                            file.delete()
                            Toast.makeText(context, "Restored to Gallery", Toast.LENGTH_SHORT).show()
                        }
                    }) { Icon(Icons.Default.Restore, "Restore") }
                    IconButton(onClick = {
                        coroutineScope.launch {
                            trashDao.delete(item)
                            file.delete()
                        }
                    }) { Icon(Icons.Default.DeleteForever, "Delete Permanently", tint = MaterialTheme.colorScheme.error) }
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@Composable
fun ReviewItemsDialog(
    items: List<MediaItem>,
    selectedUris: Set<Uri>,
    onSelectedUrisChange: (Set<Uri>) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review Items") },
        text = {
            Column {
                Text("Select items to KEEP (remove from cleanup).")
                LazyVerticalGrid(columns = GridCells.Adaptive(100.dp), modifier = Modifier.height(300.dp)) {
                    items(items, key = { it.uri.toString() }) { item ->
                        Box(Modifier.padding(2.dp).clickable { 
                            onSelectedUrisChange(if (selectedUris.contains(item.uri)) selectedUris - item.uri else selectedUris + item.uri)
                        }) {
                            AsyncImage(model = item.uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.aspectRatio(1f))
                            Checkbox(checked = selectedUris.contains(item.uri), onCheckedChange = null, modifier = Modifier.align(Alignment.TopEnd))
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Trash Selected (${selectedUris.size})") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ConfirmDeletionDialog(
    deleteAction: DeleteAction?,
    deleteAttachmentsOnly: Boolean,
    onDeleteAttachmentsOnlyChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm Cleanup") },
        text = {
            Column {
                Text(when (deleteAction) {
                    is DeleteAction.BySelection -> "Move ${deleteAction.uris.size} items to Trash?"
                    is DeleteAction.EmptyMessages -> "Delete all empty text message threads?"
                    else -> ""
                })
                if (deleteAction is DeleteAction.BySelection) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(deleteAttachmentsOnly, onDeleteAttachmentsOnlyChange)
                        Text("Delete attachments only (keep text)")
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun startDeletion(
    context: Context,
    workManager: WorkManager,
    action: DeleteAction?,
    attachmentsOnly: Boolean,
    onId: (UUID) -> Unit,
    onShow: () -> Unit
) {
    if (action == null) return
    val workBuilder = OneTimeWorkRequestBuilder<DeletionWorker>()
    if (action is DeleteAction.BySelection) {
        val file = File(context.cacheDir, "uris_to_delete.txt")
        file.writeText(action.uris.joinToString("\n"))
        workBuilder.setInputData(workDataOf(
            DeletionWorker.KEY_URIS_FILE_PATH to file.absolutePath,
            DeletionWorker.KEY_DELETE_ATTACHMENTS_ONLY to attachmentsOnly
        ))
    } else {
        workBuilder.setInputData(workDataOf(DeletionWorker.KEY_DELETE_EMPTY_MESSAGES to true))
    }
    val request = workBuilder.build()
    workManager.enqueue(request)
    onId(request.id)
    onShow()
}

private suspend fun restoreToGallery(context: Context, item: TrashedItem) = withContext(Dispatchers.IO) {
    val file = File(context.filesDir, "trash/${item.fileName}")
    if (!file.exists()) return@withContext

    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "Restored_${item.fileName}")
        put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TextImageCleaner")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }

    val collection = if (item.mimeType.startsWith("video/")) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val uri = context.contentResolver.insert(collection, values)
    
    uri?.let { destUri ->
        context.contentResolver.openOutputStream(destUri)?.use { out ->
            FileInputStream(file).use { it.copyTo(out) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(destUri, values, null, null)
        }
    }
}

private suspend fun loadMmsMedia(contentResolver: ContentResolver): List<MediaItem> = withContext(Dispatchers.IO) {
    val messageDates = mutableMapOf<Long, Long>()
    contentResolver.query(Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE), null, null, null)?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(Telephony.Mms._ID)
        val dateCol = cursor.getColumnIndexOrThrow(Telephony.Mms.DATE)
        while (cursor.moveToNext()) {
            val rawDate = cursor.getLong(dateCol)
            messageDates[cursor.getLong(idCol)] = if (rawDate < 10_000_000_000L) rawDate * 1000 else rawDate
        }
    }

    val mediaItems = mutableListOf<MediaItem>()
    val selection = "${Telephony.Mms.Part.CONTENT_TYPE} LIKE 'image/%' OR ${Telephony.Mms.Part.CONTENT_TYPE} LIKE 'video/%'"
    contentResolver.query(Telephony.Mms.CONTENT_URI.buildUpon().appendPath("part").build(), arrayOf(Telephony.Mms.Part._ID, Telephony.Mms.Part.MSG_ID, Telephony.Mms.Part.CONTENT_TYPE, "_data"), selection, null, null)?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(Telephony.Mms.Part._ID)
        val msgIdCol = cursor.getColumnIndexOrThrow(Telephony.Mms.Part.MSG_ID)
        val typeCol = cursor.getColumnIndexOrThrow(Telephony.Mms.Part.CONTENT_TYPE)
        val dataCol = cursor.getColumnIndex("_data")
        while (cursor.moveToNext()) {
            val partId = cursor.getLong(idCol)
            val msgId = cursor.getLong(msgIdCol)
            val date = messageDates[msgId] ?: 0L
            val size = if (dataCol != -1) File(cursor.getString(dataCol) ?: "").length() else 0L
            val uri = Telephony.Mms.CONTENT_URI.buildUpon().appendPath("part").appendPath(partId.toString()).build()
            mediaItems.add(MediaItem(uri, date, cursor.getString(typeCol), size))
        }
    }
    mediaItems.sortedByDescending { it.date }
}
