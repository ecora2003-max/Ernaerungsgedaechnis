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
        device.pressMenu()
        device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5,
            device.displayWidth / 2, device.displayHeight / 5, 20)
        val editor = device.wait(Until.findObject(By.res("com.android.systemui", "pinEntry")), 5_000L)
            ?: device.wait(Until.findObject(By.res("com.android.systemui", "lockPassword")), 5_000L)
            ?: device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000L)
        assertNotNull("CI test device remained locked without an accessible PIN editor", editor)
        requireNotNull(editor).click()
        device.executeShellCommand("input text 2468")
        device.pressEnter()
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (keyguard.isKeyguardLocked && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100L)
        }
        assertFalse("CI fixture PIN did not unlock the device", keyguard.isKeyguardLocked)
    }
    device.waitForIdle()
    Log.i("EG_DEVICE_TEST", "after: keyguard=${keyguard.isKeyguardLocked}, foreground=${device.currentPackageName}")
}
