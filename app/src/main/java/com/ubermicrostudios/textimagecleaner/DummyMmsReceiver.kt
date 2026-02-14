package com.ubermicrostudios.textimagecleaner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Additional no-op receiver to ensure system compatibility for SMS roles.
 */
class DummyMmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        // Required for manifest declaration
    }
}
