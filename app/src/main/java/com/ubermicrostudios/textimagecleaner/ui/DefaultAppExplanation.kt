package com.ubermicrostudios.textimagecleaner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun DefaultAppExplanation(onRequestDefaultSms: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(32.dp)
    ) {
        Icon(Icons.Default.Settings, null, Modifier.size(64.dp).padding(bottom = 16.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Set as Default SMS App", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text("To safely move messages to the Trash, Android requires this app to be your default SMS app.", textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("IMPORTANT: While this app is active, Google Messages and other apps cannot send or receive texts. You should switch back once finished.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequestDefaultSms) { Text("Set as Default") }
    }
}
