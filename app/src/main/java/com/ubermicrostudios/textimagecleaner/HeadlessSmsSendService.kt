package com.ubermicrostudios.textimagecleaner

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Required Service for Android Default SMS App compliance.
 * Allows other apps to request this app to send an SMS in the background.
 */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? {
        // No-op: We don't actually send SMS.
        return null
    }
}
