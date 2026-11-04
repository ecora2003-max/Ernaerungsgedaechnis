package de.ernaehrung.demo

import android.app.KeyguardManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull

/** CI's ephemeral emulator is provisioned with PIN 2468. Never forge a credential
 * result or remove its secure lock: the subsequent auth-bound Keystore tests
 * must still use Android's real credential Activity and real signatures.
 */
internal fun prepareUnlockedTestDevice() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val target = instrumentation.targetContext
    val keyguard = target.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    val device = UiDevice.getInstance(instrumentation)
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
    Log.i("EG_DEVICE_TEST", "after: keyguard=${keyguard.isKeyguardLocked}, foreground=${device.currentPackageName}")
}
