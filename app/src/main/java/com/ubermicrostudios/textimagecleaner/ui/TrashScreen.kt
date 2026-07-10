package com.ubermicrostudios.textimagecleaner.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import java.util.Locale
import kotlinx.coroutines.launch

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
        LazyColumn(Modifier.fillMaxSize()) {
            items(trashedItems, key = { it.uriString }) { item ->
                var showMessageBody by remember { mutableStateOf(false) }

                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(8.dp).clickable { showMessageBody = !showMessageBody },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val file = File(context.filesDir, "trash/${item.fileName}")
                        AsyncImage(
                            model = file,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(64.dp).border(1.dp, Color.Gray)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.mimeType, style = MaterialTheme.typography.labelSmall)
                            val date = LocalDateTime.ofInstant(Instant.ofEpochMilli(item.trashedDate), ZoneId.systemDefault())
                            Text("Trashed: ${date.format(DateTimeFormatter.ofPattern("MMM dd, HH:mm"))}", style = MaterialTheme.typography.bodySmall)
                            if (item.messageBody != null) {
                                Text("Click to view message", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        IconButton(onClick = {
                            coroutineScope.launch {
                                com.ubermicrostudios.textimagecleaner.MediaUtils.restoreToGallery(context, item)
                                trashDao.delete(item)
                                file.delete()
                            }
                        }) { Icon(Icons.Default.Restore, "Restore") }
                        IconButton(onClick = {
                            coroutineScope.launch {
                                trashDao.delete(item)
                                file.delete()
                            }
                        }) { Icon(Icons.Default.DeleteForever, "Delete Permanently", tint = MaterialTheme.colorScheme.error) }
                    }

                    if (showMessageBody) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = item.messageBody ?: "(No message text found)",
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
}
