package de.ernaehrung.demo

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import de.ernaehrung.demo.data.backup.*
import de.ernaehrung.demo.data.fitness.*
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.BackupViewModel
import de.ernaehrung.demo.ui.DemoTheme
import de.ernaehrung.demo.ui.screens.BackupScreen
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Real Android document provider, real encryption, actual UI and Room writes.
 * Only synthetic named databases on a disposable offline emulator. No Watch claim.
 */
@RunWith(AndroidJUnit4::class)
class BackupOnDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun unlock() = prepareUnlockedTestDevice()
    private val password = "synthetisch-äöü🔐-backup"
    private fun waitTag(tag: String) = compose.waitUntil(25_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
    private fun tap(tag: String) {
        finishDeviceTextEditing(compose); waitTag(tag)
        compose.waitUntil(25_000) { runCatching { compose.onNodeWithTag(tag).assertIsEnabled() }.isSuccess }
        if (tag.startsWith("workspace_tab_")) compose.onNodeWithTag(tag).performClick()
        else compose.onNodeWithTag(tag).performScrollTo().performClick()
    }
    private fun text(value: String) {
        finishDeviceTextEditing(compose)
        compose.waitUntil(25_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText(value).performScrollTo().performClick()
    }
    private fun input(tag: String, value: String) { waitTag(tag); compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value) }
    private fun result(value: String) {
        compose.waitUntil(25_000) { compose.onAllNodes(hasTestTag("backup_result") and hasText(value, substring = true)).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("backup_result").performScrollTo().assertTextContains(value, substring = true)
    }
    private fun saveDocument(name: String, model: BackupViewModel) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val documentPackages = setOf("com.google.android.documentsui", "com.android.documentsui")
        // Encryption completes off the UI thread, then a Compose LaunchedEffect
        // starts the picker. A native-only wait does not advance Compose's test
        // clock, leaving that recomposition/effect frozen. Wait for the actual
        // platform Activity while driving frames; never repeat the export click.
        compose.waitUntil(90_000) {
            model.state.value.error != null || device.currentPackageName in documentPackages
        }
        assertNull("Backup preparation failed", model.state.value.error)
        val editor = device.wait(Until.findObject(By.res("android:id/title").clazz("android.widget.EditText")), 15_000L)
        assertNotNull("Real DocumentsUI filename editor required; backup preparation busy=${model.state.value.busy}, error=${model.state.value.error}", editor)
        assertTrue(device.currentPackageName in documentPackages)
        requireNotNull(editor).text = name
        device.waitForIdle(1_500L)
        val save = device.wait(Until.findObject(By.res("android:id/button1").enabled(true)), 15_000L)
        assertNotNull("Real DocumentsUI save button required", save)
        requireNotNull(save).click()
    }
    private fun openDocument(name: String) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertTrue(device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 10_000L) ||
            device.wait(Until.hasObject(By.pkg("com.android.documentsui")), 10_000L))
        var file = device.wait(Until.findObject(By.text(name)), 5_000L)
        if (file == null) {
            val roots = device.findObject(By.desc("Show roots"))
            assertNotNull("DocumentsUI root navigation required", roots)
            requireNotNull(roots).click()
            val downloads = device.wait(Until.findObject(By.text("Downloads")), 10_000L)
            assertNotNull("Local Downloads root required", downloads)
            requireNotNull(downloads).click()
            file = device.wait(Until.findObject(By.text(name)), 15_000L)
        }
        assertNotNull("Actual saved encrypted document required", file)
        requireNotNull(file).click()
    }

    @Test fun offlineRealSafExportAndRestoreKeepTypedHistoryResetConsentAndNeverDuplicateOrOverwriteOtherData() = runBlocking {
        assertTrue("Fixture only on disposable emulator", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val connectivity = target.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertNull("Actual network absence required", connectivity.activeNetwork)
        // Independent Python hashlib/cryptography vector on the real Android crypto provider.
        val golden = "4547424b5030303100000001000927c0000102030405060708090a0b0c0d0e0f101112131415161718191a1b8e00669049c1e26f30087ae7678ea2f45b560eade30e458a16290c283161fa3e1072a7ebd89d30dd6edbfd146e12574d6daf003197ca0216ce5419b688"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        var entropy = 0
        val crypto = BackupEncryption { bytes -> bytes.indices.forEach { bytes[it] = (entropy++).toByte() } }
        val plain = "Nur synthetische Werte: null ≠ 0; 123 Cent.".toByteArray(Charsets.UTF_8)
        assertArrayEquals(golden, crypto.seal(plain, password.toCharArray()))
        assertArrayEquals(plain, crypto.open(golden, password.toCharArray()))
        val global = FoodDatabase.localSandbox(target)
        val globalOperations = global.foodDao().operationCount()
        val globalBatches = global.foodDao().planningBatches()
        val globalBody = global.bodyDao().versions()
        val globalHealth = global.healthDao().history(FitnessHealthPolicy.SAMSUNG_PACKAGE)
        val token = UUID.randomUUID().toString()
        val sourceName = "synthetic-backup-source-$token.db"
        val targetName = "synthetic-backup-target-$token.db"
        val source = Room.databaseBuilder(target, FoodDatabase::class.java, sourceName).build()
        var restored = Room.databaseBuilder(target, FoodDatabase::class.java, targetName).build()
        val models = mutableListOf<ViewModelStore>()
        val written = AtomicReference<Uri?>()
        val fileName = "synthetic-eg-backup-$token.egbackup"
        fun model(store: FoodStore): BackupViewModel {
            val owner = ViewModelStore().also(models::add)
            return ViewModelProvider(owner, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    BackupViewModel(store, AndroidBackupDocuments(target.contentResolver), target.packageName) as T
            })[BackupViewModel::class.java]
        }
        fun mount(model: BackupViewModel, label: String) {
            compose.runOnUiThread { compose.activity.setContent {
                DemoTheme { Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState())
                    .testTag("backup_fixture_$label")) { key(model) { BackupScreen(model) { written.set(it) } } } }
            } }
            waitTag("backup_fixture_$label"); compose.waitForIdle()
        }
        try {
            // Default navigation is connected; subsequent named stores keep other regression fixtures intact.
            tap("workspace_tab_MORE"); tap("workspace_go_BACKUP"); waitTag("workspace_page_BACKUP")
            val store = FoodStore(source)
            val day = AppCalendar.today().toString()
            val version = store.rememberProduct("source-product", ProductInput("Synthetische sichere Ware", energyKcal = 0.0))
            val location = store.addLocation("source-location", "Synthetischer Vorrat", true)
            val batch = store.receive("source-receive", BatchInput(version, location, 100_000, day), true)
            store.saveBodyMeasurement("source-body", BodyMeasurementInput(BodyMetric.WEIGHT, "73,0400", day), null, true)
            val sourceCount = source.foodDao().operationCount()
            // Diagnose the real Android SQLite representation independently of
            // the external chooser; this is a read-only synthetic snapshot.
            val androidSnapshot = store.captureBackup(target.packageName, true, true)
            assertEquals(45, androidSnapshot.tables.size)
            assertEquals(sourceCount, source.foodDao().operationCount())
            val sourceModel = model(store)
            mount(sourceModel, "source")
            text("Gesundheits- und Fitnesshistorie ausdrücklich einschließen")
            input("backup_export_password", password); input("backup_export_repeat", password)
            text("Diesen genauen Datenumfang verschlüsselt in einer von mir gewählten Datei speichern")
            tap("backup_export"); saveDocument(fileName, sourceModel)
            result("Verschlüsselte Sicherung gespeichert")
            compose.waitUntil(25_000) { written.get() != null }
            val uri = requireNotNull(written.get())
            assertTrue("Require actual local document provider", uri.authority in setOf("com.android.providers.downloads.documents", "com.android.externalstorage.documents"))
            val encrypted = AndroidBackupDocuments(target.contentResolver).read(uri)
            assertEquals("EGBKP001", encrypted.copyOfRange(0, 8).toString(Charsets.US_ASCII))
            val archive = BackupArchiveCodec.decode(BackupEncryption().open(encrypted, password.toCharArray()))
            assertTrue(archive.fitnessIncluded)
            assertEquals(sourceCount, source.foodDao().operationCount())
            val targetModel = model(FoodStore(restored))
            mount(targetModel, "target")
            tap("backup_choose_file"); openDocument(fileName); waitTag("backup_file_selected")
            input("backup_import_password", "wrong-backup-password"); tap("backup_preview"); waitTag("backup_error")
            assertEquals(0, restored.foodDao().operationCount())
            input("backup_import_password", password); tap("backup_preview"); waitTag("backup_preview_ready")
            assertEquals(0, restored.foodDao().operationCount())
            text("Genau diese geprüfte Sicherung ausdrücklich wiederherstellen")
            text("Enthaltene Gesundheits-/Fitnesshistorie ausdrücklich lokal wiederherstellen")
            compose.onNodeWithTag("backup_restore").performScrollTo().assertIsEnabled()
            compose.activityRule.scenario.recreate(); mount(targetModel, "target-recreated")
            waitTag("backup_preview_ready"); compose.onNodeWithTag("backup_restore").performScrollTo().assertIsNotEnabled()
            assertEquals(0, restored.foodDao().operationCount())
            text("Genau diese geprüfte Sicherung ausdrücklich wiederherstellen")
            text("Enthaltene Gesundheits-/Fitnesshistorie ausdrücklich lokal wiederherstellen")
            tap("backup_restore"); result("vollständig wiederhergestellt")
            assertEquals(sourceCount + 1, restored.foodDao().operationCount())
            assertEquals(source.foodDao().allVersions(), restored.foodDao().allVersions())
            assertEquals(source.bodyDao().versions(), restored.bodyDao().versions())
            assertEquals(100_000L, restored.foodDao().balance(batch))
            assertNull(restored.foodDao().version(version)!!.proteinGrams)
            assertEquals(0.0, restored.foodDao().version(version)!!.energyKcal!!, 0.0)
            tap("backup_choose_file"); openDocument(fileName); waitTag("backup_file_selected")
            input("backup_import_password", password); tap("backup_preview"); waitTag("backup_preview_ready")
            text("Genau diese geprüfte Sicherung ausdrücklich wiederherstellen")
            text("Enthaltene Gesundheits-/Fitnesshistorie ausdrücklich lokal wiederherstellen")
            tap("backup_restore"); result("bereits vorhanden")
            assertEquals(sourceCount + 1, restored.foodDao().operationCount())
            models.forEach { it.clear() }
            restored.close()
            restored = Room.databaseBuilder(target, FoodDatabase::class.java, targetName).addMigrations(FoodMigrations.MIGRATION_12_13).build()
            assertEquals(source.foodDao().allVersions(), restored.foodDao().allVersions())
            assertEquals(source.bodyDao().versions(), restored.bodyDao().versions())
            assertEquals(100_000L, restored.foodDao().balance(batch))
            assertEquals(globalOperations, global.foodDao().operationCount())
            assertEquals(globalBatches, global.foodDao().planningBatches()); assertEquals(globalBody, global.bodyDao().versions())
            assertEquals(globalHealth, global.healthDao().history(FitnessHealthPolicy.SAMSUNG_PACKAGE))
            assertNull(connectivity.activeNetwork)
        } catch (failure: Throwable) {
            captureDeviceFailureEvidence("backup-flow"); throw failure
        } finally {
            models.forEach { it.clear() }; source.close(); restored.close()
            target.deleteDatabase(sourceName); target.deleteDatabase(targetName)
            written.get()?.let { runCatching { DocumentsContract.deleteDocument(target.contentResolver, it) } }
        }
    }
}
