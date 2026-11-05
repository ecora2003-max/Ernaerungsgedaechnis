package de.ernaehrung.demo

import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.android.datatransport.Encoding
import com.google.android.datatransport.Event
import com.google.android.datatransport.Transformer
import com.google.android.datatransport.cct.CCTDestination
import com.google.android.datatransport.runtime.TransportRuntime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual merged manifest and public Apache-licensed DataTransport API. No
 * vendor SDK reflection, fabricated analytics preference or network request.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OcrTelemetryPolicyTest {
    @Test fun mergedAppCannotResolveAnyKnownCctRegistryOrUploadEntryPoint() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (name in listOf(
            "com.google.android.datatransport.runtime.backends.TransportBackendDiscovery",
            "com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoSchedulerService",
            "com.google.android.datatransport.runtime.scheduling.jobscheduling.AlarmManagerSchedulerBroadcastReceiver")) {
            try {
                val component = ComponentName(context, name)
                if (name.endsWith("Receiver")) context.packageManager.getReceiverInfo(component, 0)
                else context.packageManager.getServiceInfo(component, PackageManager.GET_META_DATA)
                fail("Telemetry entry point must not be registered: $name")
            } catch (_: PackageManager.NameNotFoundException) { /* expected actual package-manager result */ }
        }
    }

    @Test fun realCctSchedulerRejectsTelemetryBeforePersistingOrSchedulingAnyUpload() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        TransportRuntime.initialize(context)
        val transport = TransportRuntime.getInstance().newFactory(CCTDestination.INSTANCE)
            .getTransport("EG_SYNTHETIC_PRIVACY_PROBE", String::class.java, Encoding.of("json"),
                Transformer<String, ByteArray> { it.toByteArray(Charsets.UTF_8) })
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Exception?>()
        transport.schedule(Event.ofData("synthetic-only")) { error -> failure.set(error); completed.countDown() }
        assertTrue("The actual scheduler must respond", completed.await(10, TimeUnit.SECONDS))
        assertTrue("No registered backend must reject the event", failure.get() is IllegalArgumentException)
        val file = context.getDatabasePath("com.google.android.datatransport.events")
        if (file.exists()) SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM events", null).use { rows ->
                assertTrue(rows.moveToFirst()); assertEquals(0L, rows.getLong(0))
            }
        }
        val jobs = (context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).allPendingJobs
        assertTrue(jobs.none { it.service.className.startsWith("com.google.android.datatransport.") })
    }
}
