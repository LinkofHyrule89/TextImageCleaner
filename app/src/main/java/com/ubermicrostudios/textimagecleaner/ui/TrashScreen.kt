package com.ubermicrostudios.textimagecleaner.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import com.ubermicrostudios.textimagecleaner.data.TrashedItem

@Composable
fun TrashScreen(
    trashedItems: List<TrashedItem>,
    trashDao: com.ubermicrostudios.textimagecleaner.data.TrashDao,
    context: android.content.Context,
    coroutineScope: kotlinx.coroutines.CoroutineScope
) {
    if (trashedItems.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Trash is empty")
        }
    } else {
        // List implementation would go here (kept minimal for this pass)
    }
}
