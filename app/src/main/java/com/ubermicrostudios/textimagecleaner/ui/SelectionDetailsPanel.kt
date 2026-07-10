package com.ubermicrostudios.textimagecleaner.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ubermicrostudios.textimagecleaner.MmsMessageDetails

@Composable
fun SelectionDetailsPanel(
    isLoading: Boolean,
    details: MmsMessageDetails?,
    contactsPermissionGranted: Boolean,
    onRequestContactsPermission: () -> Unit,
    onOpenAppSettings: (() -> Unit)? = null,
    showOpenSettingsForContacts: Boolean = false,
    modifier: Modifier = Modifier
) {
    Surface(
        tonalElevation = 1.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        when {
            isLoading -> {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Loading conversation…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        text = "Conversation",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = details?.conversationLabel
                            ?: "Unknown conversation",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
                    )
                    Text(
                        text = "Message",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = details?.body?.takeIf { it.isNotBlank() }
                            ?: "No message text",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp)
                    )

                    if (!contactsPermissionGranted) {
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    text = "Show contact names (optional)",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = "Looks up display names from your Contacts so numbers " +
                                        "appear as people or groups. This is not required to scan " +
                                        "or delete media.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                                )
                                if (showOpenSettingsForContacts && onOpenAppSettings != null) {
                                    OutlinedButton(onClick = onOpenAppSettings) {
                                        Text("Open settings")
                                    }
                                } else {
                                    Button(onClick = onRequestContactsPermission) {
                                        Text("Allow contacts access")
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
