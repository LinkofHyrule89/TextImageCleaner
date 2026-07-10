package com.ubermicrostudios.textimagecleaner.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ubermicrostudios.textimagecleaner.data.TrashedItem
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@Composable
fun TrashScreen(
    trashedItems: List<TrashedItem>,
    trashDao: com.ubermicrostudios.textimagecleaner.data.TrashDao,
    context: android.content.Context,
    coroutineScope: kotlinx.coroutines.CoroutineScope
) {
    var pendingPermanentDelete by remember { mutableStateOf<TrashedItem?>(null) }
    val dateTimeFmt = remember { DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm") }
    val zone = remember { ZoneId.systemDefault() }

    if (trashedItems.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Trash is empty")
        }
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(trashedItems, key = { it.uriString }) { item ->
                val file = remember(item.fileName) { File(context.filesDir, "trash/${item.fileName}") }
                val originalLabel = remember(item.originalDate) {
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(item.originalDate), zone)
                        .format(dateTimeFmt)
                }
                val body = item.messageBody?.trim()?.takeIf { it.isNotEmpty() }

                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = file,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(64.dp)
                                .border(1.dp, Color.Gray)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.mimeType, style = MaterialTheme.typography.labelSmall)
                            Text(
                                text = "From: $originalLabel",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        IconButton(onClick = {
                            coroutineScope.launch {
                                com.ubermicrostudios.textimagecleaner.MediaUtils.restoreToGallery(context, item)
                                trashDao.delete(item)
                                file.delete()
                            }
                        }) { Icon(Icons.Default.Restore, contentDescription = "Restore") }
                        IconButton(onClick = { pendingPermanentDelete = item }) {
                            Icon(
                                Icons.Default.DeleteForever,
                                contentDescription = "Delete permanently",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    if (body != null) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = body,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }

    pendingPermanentDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingPermanentDelete = null },
            title = { Text("Delete permanently?") },
            text = { Text("This removes the file from Trash and cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    val target = item
                    pendingPermanentDelete = null
                    coroutineScope.launch {
                        val f = File(context.filesDir, "trash/${target.fileName}")
                        trashDao.delete(target)
                        f.delete()
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingPermanentDelete = null }) { Text("Cancel") }
            }
        )
    }
}
