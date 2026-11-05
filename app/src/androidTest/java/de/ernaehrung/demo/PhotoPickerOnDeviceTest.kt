package de.ernaehrung.demo

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import de.ernaehrung.demo.data.local.FoodDatabase
import de.ernaehrung.demo.data.recognition.ReceiptParser
import java.util.UUID
import java.util.regex.Pattern
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Fresh CI emulator, synthetic MediaStore image, actual OS picker/result/OCR.
 * No intent interception, synthetic Activity result or direct analyzer call.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class PhotoPickerOnDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun unlockFixture() = prepareUnlockedTestDevice()

    @Test fun realSystemPhotoSelectionReturnsToDefaultScanAndOcrCreatesNoActualEvents() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        // Read only the system's network state, then immediately drop the test
        // permission. App permissions/credential validation are not changed.
        instrumentation.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.ACCESS_NETWORK_STATE)
        try {
            val connectivity = target.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val active = connectivity.activeNetwork
            val capabilities = active?.let(connectivity::getNetworkCapabilities)
            assertNull("Offline acceptance requires no active default network " +
                "(wifi=${capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)}, " +
                "cellular=${capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)}, " +
                "ethernet=${capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)})", active)
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
        val resolver = target.contentResolver
        val db = FoodDatabase.localSandbox(target)
        val operations = db.foodDao().operationCount()
        val purchases = db.foodDao().purchases().size
        val meals = db.foodDao().activeConsumptionCount()
        val batches = db.foodDao().planningBatches().size
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "EG-synthetic-receipt-${UUID.randomUUID()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/EG-instrumentation")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }))
        try {
            requireNotNull(resolver.openOutputStream(uri)).use { output ->
                instrumentation.context.assets.open("recognition/receipt-test.png").use { it.copyTo(output) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            compose.onNodeWithTag("workspace_tab_SCAN").performClick()
            compose.onNodeWithTag("recognition_mode_Bon & OCR").performScrollTo().performClick()
            compose.onNodeWithText("Bonbild auswählen").performScrollTo().performClick()
            compose.waitUntil(20_000) {
                device.currentPackageName?.let { it != target.packageName } == true
            }
            // The controlled emulator has no user's gallery. The only inserted
            // MediaStore photo is this synthetic receipt, selected via OS UI.
            val thumbnail = device.wait(Until.findObject(By.res(Pattern.compile(".*:id/icon_thumbnail"))), 15_000L)
                ?: device.wait(Until.findObject(By.desc(Pattern.compile("(?i)photo taken.*"))), 5_000L)
            assertNotNull("System picker did not expose the synthetic receipt thumbnail", thumbnail)
            requireNotNull(thumbnail).click()
            val deadline = SystemClock.uptimeMillis() + 20_000L
            while (device.currentPackageName != target.packageName && SystemClock.uptimeMillis() < deadline) {
                device.findObject(By.text(Pattern.compile("(?i)(Add|Open|Select|Auswählen|Hinzufügen|Öffnen)")))?.click()
                SystemClock.sleep(100L)
            }
            assertEquals("Actual system picker did not return to the app", target.packageName, device.currentPackageName)
            compose.waitUntil(40_000) {
                compose.onAllNodesWithText("Erkannten Bontext ansehen").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Erkannten Bontext ansehen").performScrollTo().performClick()
            val raw = compose.onNodeWithText("TESTMARKT", substring = true)
                .fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("\n") { it.text }
            val parsed = ReceiptParser.parse(raw)
            assertEquals("TESTMARKT", parsed.merchant)
            assertEquals("2026-10-30", parsed.purchasedOn)
            assertEquals(497L, parsed.totalCents)
            assertEquals(2, parsed.lines.size)
            compose.onNodeWithText("Erkannten Text als Bonentwurf speichern").assertExists()
            assertEquals(operations, db.foodDao().operationCount())
            assertEquals(purchases, db.foodDao().purchases().size)
            assertEquals(meals, db.foodDao().activeConsumptionCount())
            assertEquals(batches, db.foodDao().planningBatches().size)
        } catch (failure: Throwable) {
            captureDeviceFailureEvidence("picker")
            throw failure
        } finally {
            resolver.delete(uri, null, null) // Only this test's own synthetic image.
        }
    }
}
