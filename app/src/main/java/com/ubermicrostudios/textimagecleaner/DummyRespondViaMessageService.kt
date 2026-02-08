package com.ubermicrostudios.textimagecleaner

// DummyRespondViaMessageService.kt
import android.app.Service
import android.content.Intent
import android.os.IBinder

class DummyRespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}