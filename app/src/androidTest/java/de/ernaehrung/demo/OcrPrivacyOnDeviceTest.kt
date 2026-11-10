package de.ernaehrung.demo

import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import de.ernaehrung.demo.data.local.FoodDatabase
import de.ernaehrung.demo.data.recognition.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Separate from the offline suite: real validated connectivity, real bundled
 * OCR and app-UID traffic counters. Only a disposable synthetic CI emulator.
 */
@RunWith(AndroidJUnit4::class)
class OcrPrivacyOnDeviceTest {
    @Before fun prepare() {
        assertTrue("Synthetic online privacy probe requires a disposable emulator",
            Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        prepareUnlockedTestDevice()
    }
    @Test fun bundledReceiptOcrDoesNotQueueSdkTelemetryOrSendAppTrafficOnAnAvailableNetwork(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val connectivity = target.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val jobs = target.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val uid = target.applicationInfo.uid
        val db = FoodDatabase.localSandbox(target)
        val operationsBefore = db.foodDao().operationCount()
        val files = CaptureFiles(target)
        val image = files.create()
        fun noUploadComponents() {
            for (name in listOf(
                "com.google.android.datatransport.runtime.backends.TransportBackendDiscovery",
                "com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoSchedulerService",
                "com.google.android.datatransport.runtime.scheduling.jobscheduling.AlarmManagerSchedulerBroadcastReceiver")) {
                try {
                    val component = ComponentName(target, name)
                    if (name.endsWith("Receiver")) target.packageManager.getReceiverInfo(component, 0)
                    else target.packageManager.getServiceInfo(component, PackageManager.GET_META_DATA)
                    fail("Telemetry component must be absent before enabling connectivity: $name")
                } catch (_: PackageManager.NameNotFoundException) { /* expected on the actual installed APK */ }
            }
        }
        fun noQueuedUpload() {
            assertTrue(jobs.allPendingJobs.none { it.service.className.startsWith("com.google.android.datatransport.") })
            val file = target.getDatabasePath("com.google.android.datatransport.events")
            if (file.exists()) SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { sdk ->
                sdk.rawQuery("SELECT COUNT(*) FROM events", null).use { rows ->
                    assertTrue(rows.moveToFirst()); assertEquals("No SDK telemetry may be persisted", 0L, rows.getLong(0))
                }
            }
        }
        try {
            noUploadComponents() // Do not run this probe against an unprotected SDK.
            // Observe the app UID before connectivity, including any SDK that
            // might try to flush immediately when a network becomes usable.
            val rx = TrafficStats.getUidRxBytes(uid); val tx = TrafficStats.getUidTxBytes(uid)
            assertTrue("Unknown receive counters cannot prove privacy", rx >= 0L)
            assertTrue("Unknown transmit counters cannot prove privacy", tx >= 0L)
            device.executeShellCommand("cmd connectivity airplane-mode disable")
            device.executeShellCommand("svc wifi enable")
            device.executeShellCommand("cmd wifi start-scan")
            // A freshly enabled Wi-Fi interface can have no auto-join network
            // (observed: disconnectedstate throughout the failed 45s wait).
            // Select the actually advertised open network of this emulator;
            // enabling a radio alone never establishes the privacy precondition.
            withTimeout(15_000L) {
                while (!device.executeShellCommand("cmd wifi list-scan-results").contains("VirtWifi")) delay(250L)
            }
            device.executeShellCommand("cmd wifi connect-network VirtWifi open")
            withTimeout(45_000L) {
                while (true) {
                    val caps = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
                    if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) break
                    delay(250L)
                }
            }
            target.contentResolver.openOutputStream(files.uri(image))!!.use { output ->
                instrumentation.context.assets.open("recognition/receipt-test.png").use { it.copyTo(output) }
            }
            repeat(2) {
                val result = AndroidPhotoRecognition(target).receiptText(files.uri(image))
                val receipt = ReceiptParser.parse(result.text)
                assertEquals("TESTMARKT", receipt.merchant)
                assertEquals(497L, receipt.totalCents)
            }
            // The reproduced default SDK job was delayed by 30 seconds. Keep
            // validated connectivity and observe beyond that actual interval.
            repeat(8) {
                delay(5_000L)
                val caps = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
                assertTrue("Connectivity must remain validated during the probe",
                    caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
                assertEquals("No unsolicited app-UID receive traffic", rx, TrafficStats.getUidRxBytes(uid))
                assertEquals("No unsolicited app-UID transmit traffic", tx, TrafficStats.getUidTxBytes(uid))
                noQueuedUpload()
            }
            assertEquals(operationsBefore, db.foodDao().operationCount())
            Log.i("EG_SYNTHETIC_PRIVACY", "validatedNetwork=true observationMs=40000 ocrCalls=2 uidRxDelta=0 uidTxDelta=0 queuedSdkEvents=0")
        } catch (failure: Throwable) {
            captureDeviceFailureEvidence("ocr-privacy-online")
            throw failure
        } finally {
            files.remove(image)
            device.executeShellCommand("cmd connectivity airplane-mode enable")
            device.executeShellCommand("svc wifi disable")
            device.executeShellCommand("svc data disable")
            withTimeout(10_000L) { while (connectivity.activeNetwork != null) delay(200L) }
        }
    }
}
