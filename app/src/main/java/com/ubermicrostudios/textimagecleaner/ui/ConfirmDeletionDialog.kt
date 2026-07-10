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
import androidx.compose.ui.Alignment
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
    if (deleteAction == null) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm Cleanup") },
        text = {
            Column {
                val message = when (deleteAction) {
                    is DeleteAction.BySelection ->
                        if (deleteAttachmentsOnly) {
                            "Permanently delete ${deleteAction.uris.size} attachment(s) and keep the text messages?"
                        } else {
                            "Move ${deleteAction.uris.size} item(s) to Trash and remove them from Messages?"
                        }
                    is DeleteAction.EmptyMessages ->
                        "Delete all SMS records with empty body text? This does not target MMS."
                }
                Text(message)

                if (deleteAction is DeleteAction.BySelection) {
                    Spacer(Modifier.height(12.dp))

                    if (deleteAttachmentsOnly) {
                        Text(
                            "Attachments are removed from message storage and are not placed in Trash. " +
                                "Text (if any) stays in Messages.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall
                        )
                    } else {
                        Text(
                            "Media is copied to in-app Trash first. If you selected every photo/video " +
                                "on a message, that whole message (including text) is removed from Messages. " +
                                "If you only selected some attachments on a multi-media message, only those " +
                                "parts are removed and the text/other media stay.",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

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
                colors = if (deleteAttachmentsOnly) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Text(if (deleteAttachmentsOnly) "Delete Permanently" else "Move to Trash")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
