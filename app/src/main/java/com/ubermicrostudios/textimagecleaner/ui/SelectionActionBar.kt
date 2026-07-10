package com.ubermicrostudios.textimagecleaner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SelectionActionBar(
    selectedCount: Int,
    selectedSizeLabel: String?,
    showMessageToggle: Boolean,
    messageVisible: Boolean,
    onClose: () -> Unit,
    onToggleMessage: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                }
                Text(
                    text = buildString {
                        append("$selectedCount selected")
                        if (!selectedSizeLabel.isNullOrBlank()) append(" · $selectedSizeLabel")
                    },
                    style = MaterialTheme.typography.titleSmall
                )
            }
            Row {
                if (showMessageToggle) {
                    IconButton(onClick = onToggleMessage) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = if (messageVisible) "Hide message" else "Show message"
                        )
                    }
                }
                IconButton(onClick = onDelete, enabled = selectedCount > 0) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete selected",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
