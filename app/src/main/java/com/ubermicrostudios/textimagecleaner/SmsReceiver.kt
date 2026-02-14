package com.ubermicrostudios.textimagecleaner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Required Receiver for Android Default SMS App compliance.
 * Even though this app doesn't actually handle incoming SMS, it must have this receiver
 * declared in the manifest and implemented to be eligible for the ROLE_SMS.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // No-op: We don't actually process incoming messages.
    }
}
