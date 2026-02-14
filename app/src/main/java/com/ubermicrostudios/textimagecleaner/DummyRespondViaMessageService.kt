package com.ubermicrostudios.textimagecleaner

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Required Service for Default SMS app compliance.
 * Handles the 'Respond via message' feature for incoming calls.
 */
class DummyRespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? {
        // No-op
        return null
    }
}
