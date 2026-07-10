package com.ubermicrostudios.textimagecleaner.ui.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ubermicrostudios.textimagecleaner.data.SettingsRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    isDefaultSms: Boolean,
    backupFolderName: String,
    contactsPermissionGranted: Boolean,
    onBack: () -> Unit,
    onRequestDefaultSmsRole: () -> Unit,
    onOpenSystemDefaultApps: () -> Unit,
    onRequestContactsPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onSaveBackupFolder: (String) -> Unit
) {
    val context = LocalContext.current
    var folderDraft by remember(backupFolderName) { mutableStateOf(backupFolderName) }
    val versionName = remember {
        try {
            val pi = if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            pi.versionName ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            SectionTitle("Default SMS app")
            Text(
                "This app must be the default SMS app to read and delete MMS attachments. " +
                    "It does not deliver SMS/MMS — while it is default, Google Messages (and others) " +
                    "will not receive texts. Switch back when you finish cleaning.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (isDefaultSms) "Status: This app is the default SMS app"
                else "Status: Not the default SMS app",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            if (!isDefaultSms) {
                Button(
                    onClick = onRequestDefaultSmsRole,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Set as default SMS app") }
            } else {
                Button(
                    onClick = {
                        // Prefer change-default intent when available.
                        try {
                            val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                                putExtra("package", context.packageName)
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            onOpenSystemDefaultApps()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Change default SMS app") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onOpenSystemDefaultApps,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Open system default apps settings") }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            SectionTitle("Backup location")
            Text(
                "When “Backup to Gallery before delete” is enabled, copies go under these albums:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Pictures / $folderDraft\nMovies / $folderDraft",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = folderDraft,
                onValueChange = { folderDraft = it },
                label = { Text("Album / folder name") },
                singleLine = true,
                supportingText = {
                    Text("Letters, numbers, spaces. Slashes are not allowed.")
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val clean = SettingsRepository.sanitizeFolderName(folderDraft)
                    folderDraft = clean
                    onSaveBackupFolder(clean)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save backup folder name") }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            SectionTitle("Contacts (optional)")
            Text(
                "Used only to show names instead of phone numbers in the media info panel. " +
                    "Not required to scan or delete media.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (contactsPermissionGranted) "Contacts access: granted"
                else "Contacts access: not granted",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            if (!contactsPermissionGranted) {
                Button(
                    onClick = onRequestContactsPermission,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Allow contacts access") }
                Spacer(Modifier.height(8.dp))
            }
            OutlinedButton(
                onClick = onOpenAppSettings,
                modifier = Modifier.fillMaxWidth()
            ) { Text("App permission settings") }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            SectionTitle("About")
            Text("TextImageCleaner $versionName", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Licensed under AGPL-3.0. Pre-alpha — use at your own risk.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}
