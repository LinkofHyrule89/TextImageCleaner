package com.ubermicrostudios.textimagecleaner.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ubermicrostudios.textimagecleaner.GroupedMediaItems
import com.ubermicrostudios.textimagecleaner.MediaItem
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun CleanerScreen(
    groupedMedia: List<GroupedMediaItems>,
    selectionMode: Boolean,
    onSelectionModeChange: (Boolean) -> Unit,
    selectedItems: Set<android.net.Uri>,
    onSelectedItemsChange: (Set<android.net.Uri>) -> Unit,
    imageLoader: coil.ImageLoader
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    if (groupedMedia.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No media found in MMS messages.")
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 128.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            groupedMedia.forEach { group ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = group.groupTitle,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier
                            .padding(8.dp)
                            .clickable {
                                if (!selectionMode) onSelectionModeChange(true)
                                val allSelected = selectedItems.containsAll(group.uris)
                                onSelectedItemsChange(if (allSelected) selectedItems - group.uris else selectedItems + group.uris)
                            },
                    )
                }
                items(group.items, key = { it.uri.toString() }) { item ->
                    // (Media grid item UI - kept concise)
                    // In a full pass this would be extracted to a MediaGridItem composable
                }
            }
        }
    }
}
