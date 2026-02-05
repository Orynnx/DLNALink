package com.orynnx.dlnalink

import android.app.Application
import android.util.Log

class DlnaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.d("DLNALink", "Application created - Native SSDP implementation")
    }
}
