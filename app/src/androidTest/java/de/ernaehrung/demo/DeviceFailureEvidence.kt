package de.ernaehrung.demo

import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Only the controlled synthetic CI device. Gradle uninstalls the target before
 * the workflow can pull app-private files, so retain failure evidence in a fixed
 * shell-owned diagnostic directory. No app storage permission is added.
 */
internal fun captureDeviceFailureEvidence(name: String): Boolean {
    // Refuse unknown paths without masking the failure being diagnosed.
    if (name !in setOf("picker", "camera-cancel", "camera-capture", "recipe-flow", "credential-flow",
        "shopping-flow", "plan-flow", "fitness-flow", "fitness-nutrition", "training-journal", "body-flow", "body-raw-history", "health-api-flow", "production-fefo-flow",
        "diagnostic-self-check")) return false
    // Diagnostics must never hide the original failure by blocking the runner.
    val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "synthetic-device-failure-evidence").apply { isDaemon = true }
    }
    val capture = executor.submit<Boolean> {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val directory = "/sdcard/Download/eg-synthetic-test-diagnostics"
        runCatching { device.executeShellCommand("mkdir -p $directory") }
        val picture = runCatching { device.executeShellCommand("screencap -p $directory/$name.png") }.isSuccess
        val hierarchy = Build.VERSION.SDK_INT < 31 || runCatching {
            val bytes = ByteArrayOutputStream().also { device.dumpWindowHierarchy(it) }.toByteArray()
            val pipes = automation.executeShellCommandRw("dd of=$directory/$name.xml")
            try {
                // Close the ParcelFileDescriptor itself BEFORE reading stdout.
                // A stream around its borrowed FileDescriptor does not transfer
                // ownership; without EOF, dd waits forever for more input.
                ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { it.write(bytes) }
                ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
            } finally { pipes.forEach { runCatching { it.close() } } }
        }.isSuccess
        picture && hierarchy
    }
    return try { capture.get(12, TimeUnit.SECONDS) }
    catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
    catch (_: Exception) { false }
    finally {
        capture.cancel(true)
        executor.shutdownNow()
    }
}
