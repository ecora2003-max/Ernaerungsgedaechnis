package de.ernaehrung.demo

import android.app.KeyguardManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import android.os.Build
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.util.Log
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/** Native IME show/hide requests can finish after a Compose text replacement.
 * Repeat only the idempotent hide request, never a user gesture or booking.
 * Continue only after actual WindowInsets reports the keyboard gone.
 */
internal fun finishDeviceTextEditing(
    compose: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>,
    clearComposeFocus: () -> Unit = {}
) {
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    compose.waitForIdle()
    device.waitForIdle(1_500L)
    var lastHide = 0L
    fun hide() {
        compose.runOnUiThread {
            clearComposeFocus()
            val window = compose.activity.window
            window.currentFocus?.clearFocus()
            if (Build.VERSION.SDK_INT >= 30) window.insetsController?.hide(WindowInsets.Type.ime())
            (compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(window.decorView.windowToken, 0)
        }
        lastHide = SystemClock.uptimeMillis()
    }
    hide()
    compose.waitUntil(10_000) {
        val visible = Build.VERSION.SDK_INT >= 30 && compose.activity.window.decorView.rootWindowInsets
            ?.isVisible(WindowInsets.Type.ime()) == true
        if (visible && SystemClock.uptimeMillis() - lastHide >= 250L) hide()
        !visible
    }
    compose.waitForIdle()
    device.waitForIdle(1_500L)
}

/** CI's ephemeral emulator is provisioned with PIN 2468. Never forge a credential
 * result or remove its secure lock: the subsequent auth-bound Keystore tests
 * must still use Android's real credential Activity and real signatures.
 */
internal fun prepareUnlockedTestDevice() {
    // @Before executes before each test's own fixture guard. Refuse a physical
    // phone before touching its lock screen or typing the synthetic CI PIN.
    assertTrue("Synthetic CI preparation requires a disposable emulator",
        Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val target = instrumentation.targetContext
    val keyguard = target.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    val device = UiDevice.getInstance(instrumentation)
    // A boot-time Pixel Launcher ANR can cover otherwise healthy app/credential
    // Activities on a busy software emulator. Close only that identified CI
    // launcher dialog; an ANR of our app must still fail the device tests.
    if (device.findObject(By.pkg("android").text("Pixel Launcher isn't responding")) != null) {
        val close = device.findObject(By.res("android", "aerr_close"))
            ?: device.findObject(By.pkg("android").text("Close app"))
        assertNotNull("CI Pixel Launcher ANR could not be closed", close)
        requireNotNull(close).click()
        device.waitForIdle()
        Log.w("EG_DEVICE_TEST", "Closed the CI Pixel Launcher ANR before the app test")
    }
    if (Build.VERSION.SDK_INT >= 30) {
        device.executeShellCommand("cmd connectivity airplane-mode enable")
        device.executeShellCommand("svc wifi disable")
        device.executeShellCommand("svc data disable")
    }
    Log.i("EG_DEVICE_TEST", "before: keyguard=${keyguard.isKeyguardLocked}, foreground=${device.currentPackageName}")
    if (!device.isScreenOn) device.wakeUp()
    if (keyguard.isKeyguardLocked) {
        // Ask WindowManager to show the real secure bouncer. MENU/swipe gestures
        // depend on the current shade/launcher state and can hide it again.
        device.executeShellCommand("wm dismiss-keyguard")
        val editorDeadline = SystemClock.uptimeMillis() + 15_000L
        var editor = device.findObject(By.res("com.android.systemui", "pinEntry"))
            ?: device.findObject(By.res("com.android.systemui", "lockPassword"))
            ?: device.findObject(By.clazz("android.widget.EditText"))
        while (editor == null && keyguard.isKeyguardLocked && SystemClock.uptimeMillis() < editorDeadline) {
            SystemClock.sleep(100L)
            editor = device.findObject(By.res("com.android.systemui", "pinEntry"))
                ?: device.findObject(By.res("com.android.systemui", "lockPassword"))
                ?: device.findObject(By.clazz("android.widget.EditText"))
        }
        // Dismissal can finish asynchronously when a previous test has already
        // authenticated. No PIN editor is needed once the actual lock is gone.
        if (!keyguard.isKeyguardLocked) {
            device.waitForIdle()
            if (Build.VERSION.SDK_INT == 26) prepareApi26OfflineDevice(device,target)
            return
        }
        assertNotNull("CI test device remained locked without an accessible PIN editor", editor)
        if (editor?.resourceName == "com.android.systemui:id/pinEntry") {
            // The lockscreen PIN area is not clickable and can remain unfocused.
            // Shell typing then silently drops every digit. Press the actual OS
            // keypad instead; this also works without an IME/input connection.
            for (digit in "2468") {
                val key = device.wait(Until.findObject(By.res("com.android.systemui", "key$digit")), 5_000L)
                assertNotNull("System PIN keypad digit unavailable", key)
                requireNotNull(key).click()
            }
            val enter = device.wait(Until.findObject(By.res("com.android.systemui", "key_enter")), 5_000L)
            assertNotNull("System PIN keypad enter control unavailable", enter)
            requireNotNull(enter).click()
        } else {
            requireNotNull(editor).click()
            device.waitForIdle()
            device.executeShellCommand("input text 2468")
            device.pressEnter()
        }
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (keyguard.isKeyguardLocked && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100L)
        }
        assertFalse("CI fixture PIN did not unlock the device", keyguard.isKeyguardLocked)
    }
    device.waitForIdle()
    if (Build.VERSION.SDK_INT == 26) prepareApi26OfflineDevice(device,target)
    Log.i("EG_DEVICE_TEST", "after: keyguard=${keyguard.isKeyguardLocked}, foreground=${device.currentPackageName}")
}

/** API 26 denies the protected broadcast and its svc Java process exits 137.
 * Use ordinary real Settings gestures, without root or fabricating connectivity.
 */
private fun prepareApi26OfflineDevice(device: UiDevice,target: Context) {
    val connectivity = target.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    if (connectivity.activeNetwork == null) return
    try {
        device.executeShellCommand("am start -a android.settings.AIRPLANE_MODE_SETTINGS")
        val title = device.wait(Until.findObject(By.pkg("com.android.settings").text("Airplane mode")),10_000L)
        assertNotNull("Actual API26 airplane-mode preference unavailable",title)
        fun airplaneSwitch(): UiObject2? {
            var row = device.findObject(By.pkg("com.android.settings").text("Airplane mode"))
            repeat(3) {
                val toggle = row?.findObject(By.checkable(true))
                if (toggle != null) return toggle
                row = row?.parent
            }
            return null
        }
        val airplane = airplaneSwitch()
        assertNotNull("Actual API26 airplane-mode switch unavailable",airplane)
        if (airplane?.isChecked == false) requireNotNull(title).click()
        val airplaneDeadline = SystemClock.uptimeMillis() + 10_000L
        while (airplaneSwitch()?.isChecked != true && SystemClock.uptimeMillis() < airplaneDeadline) SystemClock.sleep(100L)
        assertTrue("Actual API26 airplane-mode switch did not enable",airplaneSwitch()?.isChecked == true)
        // Android permits Wi-Fi while airplane mode is on. Inspect and disable
        // its actual switch separately rather than treating the icon as proof.
        device.executeShellCommand("am start -a android.settings.WIFI_SETTINGS")
        val wifi = device.wait(Until.findObject(By.pkg("com.android.settings").clazz("android.widget.Switch")),10_000L)
        assertNotNull("Actual API26 Wi-Fi switch unavailable",wifi)
        if (wifi?.isChecked == true) wifi.click()
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (connectivity.activeNetwork != null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100L)
        org.junit.Assert.assertNull("API26 still has an actual active network",connectivity.activeNetwork)
        device.pressBack(); device.pressBack(); device.waitForIdle()
    } catch (failure: Throwable) {
        captureDeviceFailureEvidence("api26-network"); throw failure
    }
}
