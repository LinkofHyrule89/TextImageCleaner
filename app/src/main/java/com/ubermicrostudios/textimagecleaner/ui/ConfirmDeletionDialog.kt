package com.ubermicrostudios.textimagecleaner.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

import com.ubermicrostudios.textimagecleaner.DeleteAction

@Composable
fun ConfirmDeletionDialog(
    deleteAction: DeleteAction?,
    deleteAttachmentsOnly: Boolean,
    onDeleteAttachmentsOnlyChange: (Boolean) -> Unit,
    backupBeforeDelete: Boolean,
    onBackupBeforeDeleteChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm Cleanup") },
        text = {
            Column {
                val message = when (deleteAction) {
                    is DeleteAction.BySelection -> if (deleteAttachmentsOnly) "Permanently delete ${deleteAction.uris.size} attachments?" else "Move ${deleteAction.uris.size} items to Trash?"
                    is DeleteAction.EmptyMessages -> "Delete all empty text message threads?"
                    else -> ""
                }
                Text(message)

                if (deleteAction is DeleteAction.BySelection && deleteAttachmentsOnly) {
                    Text("Warning: These will NOT be moved to Trash and cannot be restored by this app.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }

                if (deleteAction is DeleteAction.BySelection) {
                    Spacer(Modifier.height(8.dp))
                    Row { Checkbox(deleteAttachmentsOnly, onDeleteAttachmentsOnlyChange); Text("Delete attachments only (keep text)") }
                    Row { Checkbox(backupBeforeDelete, onBackupBeforeDeleteChange); Text("Backup to Gallery before delete") }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (deleteAttachmentsOnly) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()
            ) { Text(if (deleteAttachmentsOnly) "Delete Permanently" else "Move to Trash") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
