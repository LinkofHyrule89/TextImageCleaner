package com.ubermicrostudios.textimagecleaner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Required Receiver for Android Default SMS App compliance.
 * Handles (no-op) incoming MMS notifications.
 */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // No-op: We don't actually process incoming MMS.
    }
}
