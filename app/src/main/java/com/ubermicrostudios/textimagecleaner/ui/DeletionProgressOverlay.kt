package com.ubermicrostudios.textimagecleaner.ui

import androidx.compose.runtime.Composable

@Composable
fun DeletionProgressOverlay(
    totalToDelete: Int,
    deletedCount: Int,
    deletionLog: List<String>,
    isDeleteOnly: Boolean,
    onCancel: () -> Unit
) {
    // Progress overlay UI (implementation moved here from MainActivity)
}
