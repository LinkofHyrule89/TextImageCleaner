package com.ubermicrostudios.textimagecleaner.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun DeletionProgressOverlay(
    totalToDelete: Int,
    deletedCount: Int,
    deletionLog: List<String>,
    isDeleteOnly: Boolean,
    onCancel: () -> Unit
) {
    Scaffold { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isDeleteOnly) "Deleting Permanently..." else "Cleaning Up...",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = 32.dp, bottom = 24.dp)
                )

                LinearProgressIndicator(
                    progress = { if (totalToDelete > 0) deletedCount.toFloat() / totalToDelete.toFloat() else 0f },
                    modifier = Modifier.fillMaxWidth().height(8.dp)
                )

                Text(
                    text = "$deletedCount / $totalToDelete items processed",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
                )

                val listState = rememberLazyListState()
                LaunchedEffect(deletionLog.size) {
                    if (deletionLog.isNotEmpty()) listState.animateScrollToItem(deletionLog.size - 1)
                }

                Card(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.05f))
                ) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(8.dp)) {
                        items(deletionLog) { logEntry ->
                            val prefix = if (isDeleteOnly) "Deleting: " else "Trashing: "
                            Text(
                                text = "$prefix$logEntry",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }

                Text(
                    text = if (isDeleteOnly) "Attachments are being removed directly from message storage." else "Items are being moved to the internal Trash Can for safety.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                Button(
                    onClick = onCancel,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Cancel Operation") }
            }
        }
    }
}
