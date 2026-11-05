package de.ernaehrung.demo

import android.app.Application
import de.ernaehrung.demo.data.recognition.OcrTelemetryPolicy

class ErnaehrungApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        OcrTelemetryPolicy.cancelLegacyUploads(this)
    }
}
