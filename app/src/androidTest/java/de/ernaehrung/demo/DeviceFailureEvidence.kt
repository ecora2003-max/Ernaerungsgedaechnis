package de.ernaehrung.demo

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream

/** Only the controlled synthetic CI device. Gradle uninstalls the target before
 * the workflow can pull app-private files, so retain failure evidence in a fixed
 * shell-owned diagnostic directory. No app storage permission is added.
 */
internal fun captureDeviceFailureEvidence(name: String) {
    require(name in setOf("picker", "camera-cancel", "camera-capture"))
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    val directory = "/sdcard/Download/eg-synthetic-test-diagnostics"
    runCatching { device.executeShellCommand("mkdir -p $directory") }
    runCatching { device.executeShellCommand("screencap -p $directory/$name.png") }
    if (Build.VERSION.SDK_INT >= 31) runCatching {
        val bytes = ByteArrayOutputStream().also { device.dumpWindowHierarchy(it) }.toByteArray()
        // dd writes only to the named file. Unlike tee, its stdout cannot fill
        // up and deadlock while a larger hierarchy is being written to stdin.
        val pipes = automation.executeShellCommandRw("dd of=$directory/$name.xml")
        try {
            FileOutputStream(pipes[1].fileDescriptor).use { it.write(bytes) }
            FileInputStream(pipes[0].fileDescriptor).use { it.readBytes() }
        } finally { pipes.forEach { runCatching { it.close() } } }
    }
}
