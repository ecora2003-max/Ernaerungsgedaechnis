package de.ernaehrung.demo

import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.*
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.runner.RunWith

/** Reproduces the actual shell-stdin/EOF regression, on a disposable CI emulator. */
@RunWith(AndroidJUnit4::class)
class DeviceFailureEvidenceOnDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun unlock() {
        assertTrue("Synthetic diagnostics require a disposable emulator",
            Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        prepareUnlockedTestDevice()
    }
    @Test fun actualWindowCaptureClosesShellStdinWritesNewEvidenceAndReturns() {
        assertFalse("Unknown names must neither write a path nor mask an original failure",
            captureDeviceFailureEvidence("../not-a-diagnostic"))
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val path = "/sdcard/Download/eg-synthetic-test-diagnostics/diagnostic-self-check"
        device.executeShellCommand("rm -f $path.png $path.xml")
        assertTrue("Bounded diagnostic capture must finish successfully", captureDeviceFailureEvidence("diagnostic-self-check"))
        fun size(extension: String): Long = device.executeShellCommand("wc -c $path.$extension")
            .trim().substringBefore(' ').toLong()
        assertTrue("Fresh real PNG must exist", size("png") > 100L)
        if (Build.VERSION.SDK_INT >= 31) assertTrue("Fresh real hierarchy XML must exist", size("xml") > 100L)
    }
}
