package com.ubermicrostudios.textimagecleaner.ui

import androidx.compose.runtime.Composable

@Composable
fun ConfirmDeletionDialog(
    deleteAction: com.ubermicrostudios.textimagecleaner.DeleteAction?,
    deleteAttachmentsOnly: Boolean,
    onDeleteAttachmentsOnlyChange: (Boolean) -> Unit,
    backupBeforeDelete: Boolean,
    onBackupBeforeDeleteChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    // Dialog implementation moved here
}
