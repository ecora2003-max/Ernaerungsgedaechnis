package de.ernaehrung.demo

import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import de.ernaehrung.demo.data.local.FoodDatabase
import java.io.File
import java.util.regex.Pattern
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The ephemeral CI emulator renders a synthetic camera pattern. Actual camera
 * Activity, shutter, review and result; no interception, media replacement or
 * fabricated Activity result. This does not prove recognition of a paper receipt.
 */
@RunWith(AndroidJUnit4::class)
class SystemCameraOnDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun unlockFixture() = prepareUnlockedTestDevice()

    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun control(vararg names: String): UiObject2? = names.firstNotNullOfOrNull {
        device.findObject(By.res(Pattern.compile(".*:id/" + Pattern.quote(it))))
    }
    private fun temporaryNames(): Set<String> = File(target.cacheDir, "recognition")
        .listFiles().orEmpty().mapTo(mutableSetOf()) { it.name }

    private fun openCamera(): UiObject2 {
        compose.onNodeWithTag("workspace_tab_SCAN").performClick()
        compose.onNodeWithTag("recognition_mode_Bon & OCR").performScrollTo().performClick()
        compose.onNodeWithText("Bon fotografieren · lokal").performScrollTo().performClick()
        val deadline = SystemClock.uptimeMillis() + 30_000L
        while (SystemClock.uptimeMillis() < deadline) {
            control("shutter_button", "shutter_button_touch_area")?.let { return it }
            // Camera permissions belong to the external camera app; our app
            // still has neither CAMERA nor broad gallery permissions.
            val allow = control("permission_allow_foreground_only_button", "permission_allow_button")
            val permissionMessage = control("permission_message")?.text.orEmpty()
            if (permissionMessage.contains("location", ignoreCase = true) ||
                permissionMessage.contains("Standort", ignoreCase = true)) {
                control("permission_deny_button")?.click()
            } else if (allow != null) allow.click()
            else if (device.currentPackageName?.contains("camera", ignoreCase = true) == true) {
                // Decline location tagging if the AOSP first-use prompt appears.
                device.findObject(By.text(Pattern.compile("(?i)(No thanks|Nein danke|Skip|Überspringen)")))?.click()
            }
            SystemClock.sleep(150L)
        }
        fail("Actual system camera did not expose its shutter; foreground=${device.currentPackageName}")
        error("unreachable")
    }

    private fun diagnose(name: String, failure: Throwable): Nothing {
        runCatching { device.dumpWindowHierarchy(File(target.filesDir, "camera-$name-diagnostic.xml")) }
        runCatching { device.takeScreenshot(File(target.filesDir, "camera-$name-diagnostic.png")) }
        throw failure
    }

    @Test fun realCameraCancellationDeletesOnlyItsTemporaryImageAndBooksNothing() = runBlocking {
        val db = FoodDatabase.localSandbox(target)
        val operations = db.foodDao().operationCount()
        val before = temporaryNames()
        try {
            openCamera()
            assertEquals(1, (temporaryNames() - before).size)
            device.pressBack()
            compose.waitUntil(20_000) {
                compose.onAllNodesWithText("Aufnahme abgebrochen oder kein Bild gespeichert.", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(before, temporaryNames())
            assertEquals(operations, db.foodDao().operationCount())
            compose.onNodeWithText("Bontext manuell eingeben / einfügen").assertExists()
        } catch (failure: Throwable) { diagnose("cancel", failure) }
        Unit // JUnit4 requires a void method, not SemanticsNodeInteraction.
    }

    @Test fun realCameraCaptureThenActivityRecreationAndOcrOrUnreadableFallbackNeverBooks() = runBlocking {
        val db = FoodDatabase.localSandbox(target)
        val operations = db.foodDao().operationCount()
        val purchases = db.foodDao().purchases().size
        val meals = db.foodDao().activeConsumptionCount()
        val batches = db.foodDao().planningBatches().size
        val before = temporaryNames()
        try {
            val shutter = openCamera()
            assertEquals(1, (temporaryNames() - before).size)
            shutter.click()
            val deadline = SystemClock.uptimeMillis() + 20_000L
            while (device.currentPackageName != target.packageName && SystemClock.uptimeMillis() < deadline) {
                control("done_button", "btn_done", "review_done")?.click()
                    ?: device.findObject(By.desc(Pattern.compile("(?i)(Done|OK|Fertig|Use photo|Foto verwenden)")))?.click()
                SystemClock.sleep(150L)
            }
            assertEquals("System camera did not return a real result", target.packageName, device.currentPackageName)
            // Recreate after the real camera result. The Activity-owned model
            // retains its result or in-flight processing/cleanup across restart.
            compose.activityRule.scenario.recreate()
            compose.waitUntil(45_000) {
                compose.onAllNodesWithText("Erkannten Bontext ansehen").fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithText("Bilderkennung: Kein Bontext lesbar.", substring = true)
                        .fetchSemanticsNodes().isNotEmpty()
            }
            // A synthetic camera pattern need not contain receipt text. Both
            // actual OCR text and its explicit unreadable-image fallback are
            // valid; decoder/permission errors and cancelled capture are not.
            compose.waitUntil(10_000) { temporaryNames() == before }
            assertEquals(operations, db.foodDao().operationCount())
            assertEquals(purchases, db.foodDao().purchases().size)
            assertEquals(meals, db.foodDao().activeConsumptionCount())
            assertEquals(batches, db.foodDao().planningBatches().size)
            compose.onNodeWithText("Bontext manuell eingeben / einfügen").assertExists()
        } catch (failure: Throwable) { diagnose("capture", failure) }
        Unit
    }
}
