package com.ubermicrostudios.textimagecleaner

import android.app.role.RoleManager
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import coil.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.ubermicrostudios.textimagecleaner.ui.theme.TextImageCleanerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class MediaItem(val uri: Uri, val date: Long, val mimeType: String)
data class GroupedMediaItems(val groupTitle: String, val items: List<MediaItem>)

sealed class DeleteAction {
    data class BySelection(val uris: Set<Uri>) : DeleteAction()
    object EmptyMessages : DeleteAction()
}

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
}

@OptIn(
    ExperimentalPermissionsApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class
)
@Composable
fun SmsAppScreen(
    onRequestDefaultSms: () -> Unit,
    isDefault: Boolean
) {
    val context = LocalContext.current
    val contentResolver = context.contentResolver
    val coroutineScope = rememberCoroutineScope()
    val workManager = remember { WorkManager.getInstance(context) }

    val smsPermissionsState = rememberMultiplePermissionsState(
        listOf(
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.POST_NOTIFICATIONS
        )
    )

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

    // State for deletion progress (can also observe Worker)
    var showDeleteProgress by remember { mutableStateOf(false) }
    var currentWorkId by remember { mutableStateOf<java.util.UUID?>(null) }
    
    val workInfo = currentWorkId?.let { 
        workManager.getWorkInfoByIdLiveData(it).observeAsState() 
    }?.value

    var deletedCount by remember { mutableStateOf(0) }
    var totalToDelete by remember { mutableStateOf(0) }

    LaunchedEffect(workInfo) {
        if (workInfo != null) {
            if (workInfo.state.isFinished) {
                showDeleteProgress = false
                currentWorkId = null
                // Refresh list
                isRefreshing = true
                val list = loadMmsMedia(contentResolver)
                mediaList = list
                groupedMedia = list.groupBy {
                    val cal = Calendar.getInstance().apply { timeInMillis = it.date }
                    Pair(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH))
                }.toSortedMap(Comparator { p1, p2 ->
                    val c = p2.first.compareTo(p1.first)
                    if (c != 0) c else p2.second.compareTo(p1.second)
                })
                    .map { (datePair, items) ->
                        val (year, month) = datePair
                        val title = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(
                            Calendar.getInstance().apply { set(year, month, 1) }.time
                        )
                        GroupedMediaItems(title, items)
                    }
                isRefreshing = false
                Toast.makeText(context, "Deletion Complete", Toast.LENGTH_SHORT).show()
            } else {
                val progress = workInfo.progress
                val total = progress.getInt(DeletionWorker.KEY_TOTAL_COUNT, 0)
                val deleted = progress.getInt(DeletionWorker.KEY_DELETED_COUNT, 0)
                if (total > 0) {
                    totalToDelete = total
                    deletedCount = deleted
                }
            }
        }
    }


    fun updateMedia() {
        coroutineScope.launch {
            isRefreshing = true
            val list = loadMmsMedia(contentResolver)
            mediaList = list
            groupedMedia = list.groupBy {
                val cal = Calendar.getInstance().apply { timeInMillis = it.date }
                Pair(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH))
            }.toSortedMap(Comparator { p1, p2 ->
                val c = p2.first.compareTo(p1.first)
                if (c != 0) c else p2.second.compareTo(p1.second)
            })
                .map { (datePair, items) ->
                    val (year, month) = datePair
                    val title = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(
                        Calendar.getInstance().apply { set(year, month, 1) }.time
                    )
                    GroupedMediaItems(title, items)
                }
            isRefreshing = false
        }
    }

    LaunchedEffect(smsPermissionsState.allPermissionsGranted, isDefault) {
        if (smsPermissionsState.allPermissionsGranted && isDefault) {
            updateMedia()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Media Message Manager") },
                actions = {
                    if (isDefault && smsPermissionsState.allPermissionsGranted) {
                        IconButton(onClick = { updateMedia() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                        IconButton(onClick = {
                            deleteAction = DeleteAction.EmptyMessages
                            showConfirmDeleteDialog = true
                        }) {
                            Icon(Icons.Outlined.CleaningServices, contentDescription = "Delete Empty Messages")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (isDefault && smsPermissionsState.allPermissionsGranted) {
                BottomAppBar {
                    if (selectionMode) {
                        IconButton(onClick = {
                            selectionMode = false
                            selectedItems = emptySet()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel Selection")
                        }
                        Spacer(Modifier.weight(1f))
                        Text("${selectedItems.size} selected")
                        Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = {
                                if (selectedItems.isNotEmpty()){
                                    deleteAction = DeleteAction.BySelection(selectedItems)
                                    showConfirmDeleteDialog = true
                                }
                            },
                            enabled = selectedItems.isNotEmpty()
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Selected")
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { showDatePicker = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete by date range")
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (!isDefault) {
                Button(onClick = onRequestDefaultSms) {
                    Text("Set as Default SMS App", modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (!isDefault) {
                        Text(
                            "Please set this app as the default SMS app to continue.",
                            modifier = Modifier.padding(16.dp)
                        )
                    } else if (!smsPermissionsState.allPermissionsGranted) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                            val textToShow = if (smsPermissionsState.shouldShowRationale) {
                                "The SMS permission is important for this app. Please grant the permission."
                            } else {
                                "SMS permission is required to access MMS media. Please grant it in settings."
                            }
                            Text(textToShow)
                            Button(onClick = { smsPermissionsState.launchMultiplePermissionRequest() }) {
                                Text("Request permission")
                            }
                        }
                    } else {
                        if (groupedMedia.isEmpty()) {
                            Text(
                                "No media found in MMS messages.",
                                modifier = Modifier.padding(16.dp)
                            )
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
                                                    // Trigger selection mode if not active
                                                    if (!selectionMode) {
                                                        selectionMode = true
                                                    }
                                                    
                                                    val groupUris = group.items.map { it.uri }.toSet()
                                                    val allSelected = selectedItems.containsAll(groupUris)
                                                    selectedItems = if (allSelected) {
                                                        selectedItems - groupUris
                                                    } else {
                                                        selectedItems + groupUris
                                                    }
                                                }
                                        )
                                    }
                                    items(group.items, key = { it.uri }) { item ->
                                        val dateFormat = remember { SimpleDateFormat("MM/dd/yy HH:mm", Locale.getDefault()) }
                                        Card(
                                            modifier = Modifier
                                                .padding(4.dp)
                                                .combinedClickable(
                                                    onClick = {
                                                        if (selectionMode) {
                                                            selectedItems =
                                                                if (selectedItems.contains(item.uri)) {
                                                                    selectedItems - item.uri
                                                                } else {
                                                                    selectedItems + item.uri
                                                                }
                                                        } else {
                                                            // Handle click when not in selection mode
                                                        }
                                                    },
                                                    onLongClick = {
                                                        if (!selectionMode) {
                                                            selectionMode = true
                                                            selectedItems = selectedItems + item.uri
                                                        }
                                                    }
                                                )
                                                .border(
                                                    width = 3.dp,
                                                    color = if (selectedItems.contains(item.uri)) MaterialTheme.colorScheme.primary else Color.Transparent
                                                ),
                                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                                        ) {
                                            Box {
                                                AsyncImage(
                                                    model = item.uri,
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.aspectRatio(1f)
                                                )
                                                Text(
                                                    text = dateFormat.format(Date(item.date)),
                                                    modifier = Modifier
                                                        .align(Alignment.BottomCenter)
                                                        .background(
                                                            Brush.verticalGradient(
                                                                colors = listOf(
                                                                    Color.Transparent,
                                                                    Color.Black
                                                                )
                                                            )
                                                        )
                                                        .padding(4.dp),
                                                    color = Color.White,
                                                    fontSize = 12.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

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
                                    Toast.makeText(context, "No items found in selected range", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else {
                            Toast.makeText(context, "Please select a date range", Toast.LENGTH_SHORT).show()
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
        AlertDialog(
            onDismissRequest = { showReviewDialog = false },
            title = { Text("Review Items for Deletion") },
            text = {
                Column {
                    Text("Found ${itemsToDelete.size} items in date range.")
                    Text("Select items to remove from deletion.")
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 100.dp),
                        contentPadding = PaddingValues(4.dp)
                    ) {
                        items(itemsToDelete, key = { it.uri }) { item ->
                            Card(
                                modifier = Modifier
                                    .padding(4.dp)
                                    .clickable {
                                        selectedItemsForDeletion =
                                            if (selectedItemsForDeletion.contains(item.uri)) {
                                                selectedItemsForDeletion - item.uri
                                            } else {
                                                selectedItemsForDeletion + item.uri
                                            }
                                    }
                                    .border(
                                        width = 2.dp,
                                        color = if (selectedItemsForDeletion.contains(item.uri)) MaterialTheme.colorScheme.primary else Color.Transparent
                                    )
                            ) {
                                AsyncImage(
                                    model = item.uri,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.aspectRatio(1f)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (selectedItemsForDeletion.isNotEmpty()) {
                            showReviewDialog = false
                            deleteAction = DeleteAction.BySelection(selectedItemsForDeletion)
                            showConfirmDeleteDialog = true
                        }
                    },
                    enabled = selectedItemsForDeletion.isNotEmpty()
                ) { Text("Confirm (${selectedItemsForDeletion.size})") }
            },
            dismissButton = {
                Button(onClick = { showReviewDialog = false }) { Text("Cancel") }
            }
        )
    }


    if (showConfirmDeleteDialog) {
        val text = when (val action = deleteAction) {
            is DeleteAction.BySelection -> "Are you sure you want to delete ${action.uris.size} items?"
            is DeleteAction.EmptyMessages -> "Are you sure you want to delete all empty/expired text messages? This can free up space but may delete saved draft messages."
            null -> ""
        }

        AlertDialog(
            onDismissRequest = { showConfirmDeleteDialog = false },
            title = { Text("Confirm Deletion") },
            text = {
                Column {
                    Text(text)
                    if (deleteAction is DeleteAction.BySelection) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = deleteAttachmentsOnly,
                                onCheckedChange = { deleteAttachmentsOnly = it }
                            )
                            Text("Delete attachments only (keep text)")
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmDeleteDialog = false
                        
                        deleteAction?.let { action ->
                            val workData = workDataOf(
                                DeletionWorker.KEY_DELETE_ATTACHMENTS_ONLY to deleteAttachmentsOnly,
                                DeletionWorker.KEY_DELETE_EMPTY_MESSAGES to (action is DeleteAction.EmptyMessages)
                            )
                            
                            val workBuilder = OneTimeWorkRequestBuilder<DeletionWorker>()
                                .setInputData(workData)

                            if (action is DeleteAction.BySelection) {
                                totalToDelete = action.uris.size
                                // Save URIs to file
                                val file = File(context.cacheDir, "uris_to_delete.txt")
                                file.writeText(action.uris.joinToString("\n") { it.toString() })
                                val dataWithFile = workDataOf(
                                    DeletionWorker.KEY_URIS_FILE_PATH to file.absolutePath,
                                    DeletionWorker.KEY_DELETE_ATTACHMENTS_ONLY to deleteAttachmentsOnly,
                                    DeletionWorker.KEY_DELETE_EMPTY_MESSAGES to false
                                )
                                workBuilder.setInputData(dataWithFile)
                            }

                            val workRequest = workBuilder.build()
                            workManager.enqueue(workRequest)
                            currentWorkId = workRequest.id
                            showDeleteProgress = true
                            
                            // Clear selections immediately
                            selectionMode = false
                            selectedItems = emptySet()
                            deleteAction = null
                            deleteAttachmentsOnly = false
                        }
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                Button(onClick = {
                    showConfirmDeleteDialog = false
                    deleteAction = null
                }) { Text("Cancel") }
            }
        )
    }

    if (showDeleteProgress) {
        AlertDialog(
            onDismissRequest = { 
                // Allow dismissing dialog, background work continues
                showDeleteProgress = false 
            },
            title = { Text("Deleting In Progress") },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("Deleted $deletedCount of $totalToDelete items...")
                    Text("You can close this dialog, deletion will continue in background.")
                }
            },
            confirmButton = {
                TextButton(onClick = { showDeleteProgress = false }) {
                    Text("Background")
                }
            }
        )
    }
}

private suspend fun loadMmsMedia(contentResolver: ContentResolver): List<MediaItem> = withContext(Dispatchers.IO) {
    val messageDates = mutableMapOf<Long, Long>()
    val msgProjection = arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE)
    contentResolver.query(
        Telephony.Mms.CONTENT_URI,
        msgProjection,
        null,
        null,
        null
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(Telephony.Mms._ID)
        val dateColumn = cursor.getColumnIndexOrThrow(Telephony.Mms.DATE)
        while (cursor.moveToNext()) {
            val msgId = cursor.getLong(idColumn)
            val date = cursor.getLong(dateColumn) * 1000 // Convert to milliseconds
            messageDates[msgId] = date
        }
    }

    val mediaItems = mutableListOf<MediaItem>()
    val partProjection = arrayOf(
        Telephony.Mms.Part._ID,
        Telephony.Mms.Part.MSG_ID,
        Telephony.Mms.Part.CONTENT_TYPE
    )
    val selection = "${Telephony.Mms.Part.CONTENT_TYPE} LIKE 'image/%' OR ${Telephony.Mms.Part.CONTENT_TYPE} LIKE 'video/%'"

    contentResolver.query(
        Telephony.Mms.CONTENT_URI.buildUpon().appendPath("part").build(),
        partProjection,
        selection,
        null,
        null
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(Telephony.Mms.Part._ID)
        val msgIdColumn = cursor.getColumnIndexOrThrow(Telephony.Mms.Part.MSG_ID)
        val contentTypeColumn = cursor.getColumnIndexOrThrow(Telephony.Mms.Part.CONTENT_TYPE)

        while (cursor.moveToNext()) {
            val partId = cursor.getLong(idColumn)
            val msgId = cursor.getLong(msgIdColumn)
            val mimeType = cursor.getString(contentTypeColumn)
            val date = messageDates[msgId]
            if (date != null) {
                val uri = Telephony.Mms.CONTENT_URI.buildUpon()
                    .appendPath("part").appendPath(partId.toString()).build()
                mediaItems.add(MediaItem(uri, date, mimeType))
            }
        }
    }
    mediaItems.sortedByDescending { it.date }
}
