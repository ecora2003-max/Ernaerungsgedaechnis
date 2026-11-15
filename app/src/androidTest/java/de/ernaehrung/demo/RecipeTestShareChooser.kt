package de.ernaehrung.demo

import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

/** The actual Android 15 chooser can initially show only one row of apps.
 * Expand its real sheet instead of bypassing the chooser or changing the
 * production intent/consent. The recipient belongs solely to the TEST APK. */
internal fun chooseSyntheticRecipeShareReceiver(device: UiDevice) {
    val selector = By.textContains("Synthetische Rezept-KI")
    var receiver = device.wait(Until.findObject(selector), 15_000L)
    if (receiver == null) {
        require(device.currentPackageName in setOf("android", "com.android.intentresolver")) {
            "Actual Android share chooser must be foreground before expanding"
        }
        val handle = requireNotNull(device.findObject(By.res("android", "drag"))) {
            "Actual chooser recipient absent and no sheet handle available"
        }.visibleCenter
        check(device.swipe(handle.x, handle.y, handle.x, device.displayHeight / 8, 35))
        receiver = device.wait(Until.findObject(selector), 15_000L)
    }
    requireNotNull(receiver) { "Expanded actual Android chooser must offer the synthetic TEST APK recipient" }.click()
}
