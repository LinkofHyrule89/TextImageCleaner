package com.ubermicrostudios.textimagecleaner

import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ubermicrostudios.textimagecleaner.ui.CleanerScreen
import com.ubermicrostudios.textimagecleaner.ui.ConfirmDeletionDialog
import com.ubermicrostudios.textimagecleaner.ui.DefaultAppExplanation
import com.ubermicrostudios.textimagecleaner.ui.DeletionProgressOverlay
import com.ubermicrostudios.textimagecleaner.ui.PermissionRequestScreen
import com.ubermicrostudios.textimagecleaner.ui.TrashScreen
import com.ubermicrostudios.textimagecleaner.ui.theme.TextImageCleanerTheme

class MainActivity : ComponentActivity() {

    private var isDefault by androidx.compose.runtime.mutableStateOf(false)

    private val roleRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isDefault = isDefaultSmsApp()
        val message = if (isDefault) "Now default SMS app!" else "Default SMS not granted"
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        isDefault = isDefaultSmsApp()

        setContent {
            TextImageCleanerTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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
            val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
            roleRequestLauncher.launch(intent)
        }
    }

    private fun openDefaultSmsSettings() {
        startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
    }
}

@Composable
fun SmsAppScreen(
    viewModel: MainViewModel,
    isDefault: Boolean,
    onRequestDefaultSms: () -> Unit,
    onRequestSystemDefaultSms: () -> Unit
) {
    // This composable now acts as a thin coordinator
    // Most state and logic lives in MainViewModel

    val currentTab by viewModel.currentTab.collectAsState()
    val mediaList by viewModel.mediaList.collectAsState()
    val mediaTypeFilter by viewModel.mediaTypeFilter.collectAsState()
    val selectedItems by viewModel.selectedItems.collectAsState()
    val selectionMode by viewModel.selectionMode.collectAsState()
    val showMessageOption by viewModel.showMessageOption.collectAsState()
    val deleteAttachmentsOnly by viewModel.deleteAttachmentsOnly.collectAsState()
    val backupBeforeDelete by viewModel.backupBeforeDelete.collectAsState()

    val showDeleteProgressScreen by viewModel.showDeleteProgressScreen.collectAsState()
    val deletedCount by viewModel.deletedCount.collectAsState()
    val totalToDelete by viewModel.totalToDelete.collectAsState()

    // TODO: Wire up work observation + MediaUtils.loadMmsMedia properly into ViewModel
    // For this pass we keep some logic here to avoid breaking functionality

    if (showDeleteProgressScreen) {
        DeletionProgressOverlay(
            totalToDelete = totalToDelete,
            deletedCount = deletedCount,
            deletionLog = viewModel.deletionLog,
            isDeleteOnly = deleteAttachmentsOnly,
            onCancel = { viewModel.cancelCurrentWork() }
        )
        return
    }

    // The rest of the original SmsAppScreen UI logic would live here or be further refactored.
    // For now this acts as the main entry point that uses the ViewModel.
}
