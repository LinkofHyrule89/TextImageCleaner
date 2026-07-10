package com.ubermicrostudios.textimagecleaner

import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.ubermicrostudios.textimagecleaner.ui.*
import com.ubermicrostudios.textimagecleaner.ui.theme.TextImageCleanerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var isDefault by mutableStateOf(false)

    private val roleRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { 
        isDefault = isDefaultSmsApp()
        Toast.makeText(this, if (isDefault) "Now default SMS app!" else "Default SMS not granted", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        isDefault = isDefaultSmsApp()

        setContent {
            TextImageCleanerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val viewModel: MainViewModel = viewModel { MainViewModel(this@MainActivity) }
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
        if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_SMS) && !roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val workManager = remember { androidx.work.WorkManager.getInstance(context) }
    val database = remember { com.ubermicrostudios.textimagecleaner.data.AppDatabase.getDatabase(context) }
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

    val imageLoader = remember {
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            .build()
    }

    val canBrowseMedia = isDefault && permissionsState.allPermissionsGranted

    // Load MMS media once default-SMS + permissions are ready; reloads when those change.
    LaunchedEffect(canBrowseMedia) {
        if (canBrowseMedia) viewModel.loadMedia()
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

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(if (viewModel.currentTab == AppTab.CLEANER) "Cleaner" else "Trash Can") },
                    actions = {
                        if (canBrowseMedia) {
                            if (viewModel.currentTab == AppTab.CLEANER && !viewModel.selectionMode) {
                                IconButton(onClick = { viewModel.mediaTypeFilter = MediaTypeFilter.ALL }) {
                                    Icon(Icons.Default.PhotoLibrary, contentDescription = "All")
                                }
                                IconButton(onClick = { viewModel.mediaTypeFilter = MediaTypeFilter.IMAGES }) {
                                    Icon(Icons.Default.Image, contentDescription = "Images")
                                }
                                IconButton(onClick = { viewModel.mediaTypeFilter = MediaTypeFilter.VIDEOS }) {
                                    Icon(Icons.Default.Videocam, contentDescription = "Videos")
                                }
                                IconButton(
                                    onClick = { viewModel.loadMedia() },
                                    enabled = !viewModel.isLoadingMedia
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                                }
                            }
                            if (!viewModel.selectionMode) {
                                IconButton(onClick = onRequestSystemDefaultSms) {
                                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                                }
                            }
                        }
                    }
                )
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = viewModel.currentTab == AppTab.CLEANER,
                    onClick = { viewModel.currentTab = AppTab.CLEANER },
                    icon = { Icon(Icons.Default.PhotoLibrary, null) },
                    label = { Text("Cleaner") }
                )
                NavigationBarItem(
                    selected = viewModel.currentTab == AppTab.TRASH,
                    onClick = { viewModel.currentTab = AppTab.TRASH },
                    icon = { Icon(Icons.Default.History, null) },
                    label = { Text("Trash") }
                )
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (!isDefault) {
                DefaultAppExplanation(onRequestDefaultSms)
            } else if (!permissionsState.allPermissionsGranted) {
                PermissionRequestScreen(permissionsState)
            } else {
                when (viewModel.currentTab) {
                    AppTab.CLEANER -> {
                        if (viewModel.isLoadingMedia && viewModel.mediaList.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else {
                            CleanerScreen(
                                groupedMedia = viewModel.groupedMedia,
                                selectionMode = viewModel.selectionMode,
                                onSelectionModeChange = {
                                    if (it) viewModel.enterSelectionMode() else viewModel.exitSelectionMode()
                                },
                                selectedItems = viewModel.selectedItems,
                                onSelectedItemsChange = { viewModel.selectedItems = it },
                                imageLoader = imageLoader
                            )
                        }
                    }
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
}