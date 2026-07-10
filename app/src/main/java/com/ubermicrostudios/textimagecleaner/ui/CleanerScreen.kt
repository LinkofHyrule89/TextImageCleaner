package com.ubermicrostudios.textimagecleaner.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.ubermicrostudios.textimagecleaner.GroupedMediaItems
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CleanerScreen(
    groupedMedia: List<GroupedMediaItems>,
    selectionMode: Boolean,
    onSelectionModeChange: (Boolean) -> Unit,
    selectedItems: Set<android.net.Uri>,
    onSelectedItemsChange: (Set<android.net.Uri>) -> Unit,
    imageLoader: coil.ImageLoader,
    /** Optional key so parents can force grid refresh when filters change. */
    contentKey: String = ""
) {
    // Touch contentKey so the parameter is part of composition identity.
    @Suppress("UNUSED_EXPRESSION")
    contentKey
    val context = LocalContext.current
    val dateFormat = remember { DateTimeFormatter.ofPattern("MM/dd HH:mm", Locale.getDefault()) }
    val placeholderColor = MaterialTheme.colorScheme.surfaceVariant

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
                item(span = { GridItemSpan(maxLineSpan) }, key = "header-${group.groupTitle}") {
                    Text(
                        text = group.groupTitle,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier
                            .padding(8.dp)
                            .clickable {
                                if (!selectionMode) onSelectionModeChange(true)
                                val allSelected = selectedItems.containsAll(group.uris)
                                onSelectedItemsChange(
                                    if (allSelected) selectedItems - group.uris.toSet()
                                    else selectedItems + group.uris
                                )
                            }
                    )
                }
                items(group.items, key = { it.uri.toString() }) { item ->
                    val request = remember(item.uri) {
                        ImageRequest.Builder(context)
                            .data(item.uri)
                            .size(300, 300)
                            .crossfade(true)
                            .build()
                    }

                    Card(
                        modifier = Modifier
                            .padding(4.dp)
                            .combinedClickable(
                                onClick = {
                                    if (selectionMode) {
                                        onSelectedItemsChange(
                                            if (item.uri in selectedItems) selectedItems - item.uri
                                            else selectedItems + item.uri
                                        )
                                    }
                                },
                                onLongClick = {
                                    if (!selectionMode) {
                                        onSelectionModeChange(true)
                                        onSelectedItemsChange(selectedItems + item.uri)
                                    }
                                }
                            )
                            .border(
                                3.dp,
                                if (item.uri in selectedItems) MaterialTheme.colorScheme.primary
                                else Color.Transparent
                            ),
                        elevation = CardDefaults.cardElevation(2.dp)
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            var showSpinner by remember(item.uri) { mutableStateOf(true) }
                            AsyncImage(
                                model = request,
                                imageLoader = imageLoader,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .background(placeholderColor),
                                onState = { state ->
                                    showSpinner = state is AsyncImagePainter.State.Loading ||
                                        state is AsyncImagePainter.State.Empty
                                }
                            )
                            if (showSpinner) {
                                CircularProgressIndicator(
                                    Modifier
                                        .align(Alignment.Center)
                                        .size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                            if (item.mimeType.startsWith("video/")) {
                                androidx.compose.material3.Icon(
                                    Icons.Default.PlayCircle,
                                    contentDescription = "Video",
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(48.dp),
                                    tint = Color.White.copy(alpha = 0.7f)
                                )
                            }
                            Text(
                                text = dateFormat.format(
                                    LocalDateTime.ofInstant(
                                        Instant.ofEpochMilli(item.date),
                                        ZoneId.systemDefault()
                                    )
                                ),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(Color.Transparent, Color.Black)
                                        )
                                    )
                                    .padding(4.dp),
                                color = Color.White,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
