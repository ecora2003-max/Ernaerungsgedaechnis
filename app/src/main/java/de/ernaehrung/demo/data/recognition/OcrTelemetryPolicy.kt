package de.ernaehrung.demo.data.recognition

import android.app.job.JobScheduler
import android.content.Context

/** The merged manifest removes DataTransport's backend registry and both upload
 * entry points. Also cancel this app's old SDK jobs when upgrading an install.
 * Does not alter FoodStore, photos, health data, or any user-created history.
 */
internal object OcrTelemetryPolicy {
    fun cancelLegacyUploads(context: Context) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        // Component removal is the independent barrier even if a restricted
        // platform cannot enumerate jobs during application startup.
        runCatching {
            scheduler.allPendingJobs.filter {
                it.service.className.startsWith("com.google.android.datatransport.")
            }.forEach { scheduler.cancel(it.id) }
        }
    }
}
