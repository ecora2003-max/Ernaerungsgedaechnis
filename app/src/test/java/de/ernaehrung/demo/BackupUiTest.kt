package de.ernaehrung.demo

import android.app.Activity
import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import de.ernaehrung.demo.data.backup.*
import de.ernaehrung.demo.data.fitness.*
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.BackupViewModel
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackupUiTest {
    @get:Rule(order = 0) val database: org.junit.rules.TestRule = IsolatedWorkspaceDatabase()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val password = "synthetic-backup-password"
    private fun waitTag(tag: String) = compose.waitUntil(25_000) {
        compose.waitForIdle(); compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1
    }
    private fun tap(tag: String) {
        waitTag(tag)
        if (tag.startsWith("workspace_tab_")) compose.onNodeWithTag(tag).performClick()
        else compose.onNodeWithTag(tag).performScrollTo().performClick()
    }
    private fun text(value: String) = compose.onNodeWithText(value).performScrollTo().performClick()
    private suspend fun source(): Pair<FoodDatabase, BackupArchive> {
        val context = compose.activity.applicationContext
        val db = Room.inMemoryDatabaseBuilder(context, FoodDatabase::class.java).build()
        val store = FoodStore(db)
        val day = AppCalendar.today().minusDays(1).toString()
        val version = store.rememberProduct("source-product", ProductInput("Synthetische Sicherungsware", energyKcal = 0.0))
        val location = store.addLocation("source-location", "Synthetischer Vorrat", true)
        store.receive("source-stock", BatchInput(version, location, 100_000, day), true)
        store.saveBodyMeasurement("source-body", BodyMeasurementInput(BodyMetric.WEIGHT, "73,0400", day), null, true)
        return db to store.captureBackup(context.packageName, true, true)
    }

    @Test fun actualDefaultBackupPageRequiresFreshScopeConfirmationAfterRecreationAndRestoresOnlyItsDecryptedPreview() = runBlocking {
        val context = compose.activity.applicationContext
        val target = FoodDatabase.localSandbox(context)
        assertEquals(0, target.foodDao().operationCount())
        val (source, archive) = source()
        val encrypted = BackupEncryption().seal(BackupArchiveCodec.encode(archive), password.toCharArray())
        val file = File(context.cacheDir, "synthetic-ui-${UUID.randomUUID()}.egbackup").apply { writeBytes(encrypted) }
        val authority = "synthetic.eg.backup"
        val uri = Uri.parse("content://$authority/archive")
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "application/octet-stream"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                require(uri.path == "/archive" && mode == "r")
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }
        provider.attachInfo(context, ProviderInfo().apply { this.authority = authority })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        try {
            tap("workspace_tab_MORE"); tap("workspace_go_BACKUP")
            compose.onNodeWithTag("backup_export").performScrollTo().assertIsNotEnabled()
            tap("backup_choose_file")
            val started = requireNotNull(shadowOf(compose.activity).nextStartedActivityForResult)
            compose.runOnUiThread { shadowOf(compose.activity).receiveResult(started.intent, Activity.RESULT_OK, Intent().setData(uri)) }
            waitTag("backup_file_selected")
            compose.onNodeWithTag("backup_import_password").performScrollTo().performTextReplacement(password)
            tap("backup_preview"); waitTag("backup_preview_ready")
            assertEquals(0, target.foodDao().operationCount())
            text("Genau diese geprüfte Sicherung ausdrücklich wiederherstellen")
            compose.onNodeWithTag("backup_restore").performScrollTo().assertIsNotEnabled()
            text("Enthaltene Gesundheits-/Fitnesshistorie ausdrücklich lokal wiederherstellen")
            compose.onNodeWithTag("backup_restore").performScrollTo().assertIsEnabled()
            compose.activityRule.scenario.recreate(); waitTag("backup_preview_ready")
            compose.onNodeWithTag("backup_restore").performScrollTo().assertIsNotEnabled()
            assertEquals("", compose.onNodeWithTag("backup_import_password").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            assertEquals(0, target.foodDao().operationCount())
            text("Genau diese geprüfte Sicherung ausdrücklich wiederherstellen")
            text("Enthaltene Gesundheits-/Fitnesshistorie ausdrücklich lokal wiederherstellen")
            tap("backup_restore"); waitTag("backup_result")
            compose.onNodeWithTag("backup_result").performScrollTo().assertTextContains("vollständig wiederhergestellt", substring = true)
            assertEquals(source.foodDao().allVersions(), target.foodDao().allVersions())
            assertEquals(source.foodDao().planningBatches(), target.foodDao().planningBatches())
            assertEquals(source.bodyDao().versions(), target.bodyDao().versions())
            assertEquals("73,0400", target.bodyDao().versions().single().rawValue)
            val count = target.foodDao().operationCount()
            compose.activityRule.scenario.recreate(); waitTag("workspace_page_BACKUP")
            assertEquals(count, target.foodDao().operationCount())
            assertEquals(1, target.foodDao().allVersions().size)
        } finally { source.close(); file.delete() }
    }

    @Test fun anExportCallbackMustBelongToTheLiveRequestAndCannotResumeAfterItsViewModelWasCleared() = runBlocking {
        val context = compose.activity.applicationContext
        val (source, _) = source()
        val store = FoodStore(source)
        var written: ByteArray? = null
        var writes = 0
        val documents = object : BackupDocuments {
            override fun read(uri: Uri) = error("Not used")
            override fun write(uri: Uri, encrypted: ByteArray) { writes++; written = encrypted.copyOf() }
        }
        val owners = mutableListOf<ViewModelStore>()
        fun model(): BackupViewModel {
            val owner = ViewModelStore().also(owners::add)
            return ViewModelProvider(owner, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    BackupViewModel(store, documents, context.packageName) as T
            })[BackupViewModel::class.java]
        }
        fun ready(model: BackupViewModel): String {
            compose.waitUntil(25_000) { compose.waitForIdle(); model.state.value.exportRequest != null || model.state.value.error != null }
            assertNull(model.state.value.error)
            return requireNotNull(model.state.value.exportRequest).id
        }
        try {
            val before = source.foodDao().operationCount()
            val first = model(); val secret = password.toCharArray()
            first.export(secret, false, true); val oldId = ready(first)
            assertTrue(secret.all { it == '\u0000' })
            first.consumeExportRequest(oldId)
            val uri = Uri.parse("content://synthetic.eg.backup/output")
            first.finishExport("wrong", uri); assertEquals(0, writes)
            owners.first().clear()
            val replacement = model()
            replacement.finishExport(oldId, uri); assertEquals(0, writes)
            replacement.export(password.toCharArray(), false, true); val id = ready(replacement)
            replacement.consumeExportRequest(id)
            replacement.finishExport(oldId, uri); assertEquals(0, writes)
            replacement.finishExport(id, uri)
            compose.waitUntil(25_000) { compose.waitForIdle(); !replacement.state.value.busy }
            assertEquals(1, writes)
            val archive = BackupArchiveCodec.decode(BackupEncryption().open(requireNotNull(written), password.toCharArray()))
            assertFalse(archive.fitnessIncluded)
            assertTrue(archive.tables.filter { it.name in FoodBackupStore.FITNESS_TABLES }.all { it.rows.isEmpty() })
            assertEquals(before, source.foodDao().operationCount())
        } finally { owners.forEach { it.clear() }; source.close() }
    }
}
