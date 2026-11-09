package de.ernaehrung.demo

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.room.withTransaction
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.ernaehrung.demo.data.fitness.*
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.DemoTheme
import de.ernaehrung.demo.ui.FitnessViewModel
import de.ernaehrung.demo.ui.HealthExtraViewModel
import de.ernaehrung.demo.ui.screens.FitnessScreen
import de.ernaehrung.demo.ui.screens.HealthExtraScreen
import java.time.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Real default Android UI/SQLite/offline proof with a SYNTHETIC read-only source.
 * Not an actual Health Connect permission/record or physical Watch-4 test.
 */
@RunWith(AndroidJUnit4::class)
class FitnessImportOnDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun unlock() = prepareUnlockedTestDevice()

    @Test fun additionalTypesUseExplicitSelectionRawSourceValuesHistoryConsentReopenAndSeparatePurge(): Unit = runBlocking {
        assertTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val context = compose.activity.applicationContext
        val network = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertNull(network.activeNetwork)
        val sandbox = FoodDatabase.localSandbox(context)
        val foodBefore = sandbox.foodDao().operationCount()
        val coreBefore = FoodStore(sandbox).fitnessCache().first()
        // Verify the real application navigation, then exercise the production
        // screen against an isolated synthetic provider and real platform SQLite.
        compose.onNodeWithTag("workspace_tab_MORE").performClick()
        compose.onNodeWithTag("workspace_go_HEALTH_EXTRA").performScrollTo().performClick()
        compose.onNodeWithTag("health_extra_screen").assertExists()
        val name = "synthetic-extra-ui-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, FoodDatabase::class.java, name).build()
        var db = open()
        val clock = System.currentTimeMillis()
        var store = FoodStore(db) { clock }
        val provider = SyntheticExtraSource()
        var models = ViewModelStore()
        lateinit var model: HealthExtraViewModel
        fun mount() {
            compose.runOnUiThread {
                model = ViewModelProvider(models, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        HealthExtraViewModel(store, provider) { clock } as T
                })[HealthExtraViewModel::class.java]
                compose.activity.setContent { DemoTheme {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                        HealthExtraScreen(model)
                    }
                } }
            }
        }
        fun ready(predicate: () -> Boolean) { compose.waitUntil(15_000) { !model.state.value.busy && predicate() }; compose.waitForIdle() }
        fun tap(tag: String) { compose.onNodeWithTag(tag).performScrollTo().performClick() }
        try {
            mount(); ready { model.state.value.cache != null && model.state.value.availability != null }
            assertEquals(0, provider.reads)
            compose.onNodeWithTag("health_extra_connect").performScrollTo().assertIsNotEnabled()
            HealthExtraKind.entries.forEach { tap("health_extra_kind_${it.name}") }
            tap("health_extra_days_90")
            compose.onNodeWithTag("health_extra_connect").performScrollTo().assertIsNotEnabled()
            tap("health_extra_history_consent"); tap("health_extra_consent")
            tap("health_extra_connect"); ready { model.state.value.cache?.records?.size == 7 }
            assertEquals(1, provider.reads); assertTrue(provider.lastQuery!!.needsHistory)
            val snapshot = model.state.value.cache!!
            assertEquals(0L, snapshot.lastSuccess!!.steps)
            assertEquals(4, snapshot.records.sumOf { it.record.samples.size })
            assertTrue(snapshot.records.all { it.visible && it.revision == 1L })
            assertEquals("W", db.healthExtraDao().latest().single { it.kind == "BASAL_RATE" }.unit)
            compose.onNodeWithTag("health_extra_step_day").performScrollTo().assertTextContains(": 0", substring = true)
            compose.onAllNodesWithText("test.synthetic.scale", substring = true).assertCountEquals(2)
            tap("health_extra_stop"); ready { model.state.value.cache?.state?.enabled == false }
            compose.runOnUiThread { compose.activity.setContent { DemoTheme {} }; models.clear() }
            compose.waitForIdle(); db.close(); db = open(); store = FoodStore(db) { clock }; models = ViewModelStore()
            mount(); ready { model.state.value.cache?.records?.size == 7 }
            assertEquals(1, provider.reads)
            assertEquals(snapshot.records, model.state.value.cache!!.records)
            assertFalse(model.state.value.cache!!.state.enabled)
            tap("health_extra_erase_consent"); tap("health_extra_erase")
            ready { model.state.value.cache?.records?.isEmpty() == true }
            assertTrue(db.healthExtraDao().latestSamples().isEmpty())
            assertEquals(coreBefore, FoodStore(sandbox).fitnessCache().first())
            assertEquals(foodBefore, sandbox.foodDao().operationCount()); assertEquals(0, db.foodDao().operationCount())
            assertNull(network.activeNetwork)
        } catch (failure: Throwable) { captureDeviceFailureEvidence("health-extra-flow"); throw failure }
        finally {
            compose.runOnUiThread { compose.activity.setContent { DemoTheme {} }; models.clear() }
            compose.waitForIdle(); db.close(); context.deleteDatabase(name)
        }
    }

    @Test fun syntheticCacheAppearsInDefaultFitnessUiSurvivesRecreationAndDeletesWithoutFoodWrites(): Unit = runBlocking {
        assertTrue("Synthetic cleanup requires a disposable emulator", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        assertNull((target.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetwork)
        val db = FoodDatabase.localSandbox(target)
        var readAt = System.currentTimeMillis()
        val store = FoodStore(db) { readAt + 5_000L }
        val origin = FitnessHealthPolicy.SAMSUNG_PACKAGE
        assertNull("Refuse to overwrite any existing health consent", db.healthDao().state(origin))
        assertTrue("Use an empty synthetic health sandbox", db.healthDao().history(origin).isEmpty())
        val token = "synthetic-${UUID.randomUUID()}"
        val before = store.operations().first()
        val stockBefore = db.foodDao().planningBatches()
        val mealsBefore = db.foodDao().activeConsumptionCount()
        val at = readAt
        val zone = ZoneId.systemDefault()
        val completed = listOf(
            FitnessSourceRecord("$token-steps", FitnessRecordKind.STEPS, origin, at - 60_000L,
                at - 1L, null, null, at - 1L, null, 0L, "CI SYNTHETIC", "No Watch proof", null, 0, steps = 500L),
            FitnessSourceRecord("$token-workout", FitnessRecordKind.WORKOUT, origin, at - 1_800_000L,
                at - 1L, null, null, at - 1L, null, 0L, "CI SYNTHETIC", "No Watch proof", null, 0,
                exerciseType = 0, title = "Synthetisches Testtraining"),
            FitnessSourceRecord("$token-missing", FitnessRecordKind.WORKOUT, origin, at - 3_600_000L,
                at - 1_800_001L, null, null, at - 1L, null, 0L, "CI SYNTHETIC", "No Watch proof", null, 0,
                exerciseType = 0, title = "Synthetisches verschwundenes Training"))
        val pendingSteps = completed[0].copy(externalId = "$token-pending-steps", endAt = at + 60_000L, steps = 9_999L)
        val pendingWorkout = completed[1].copy(externalId = "$token-pending-workout", endAt = at + 60_000L,
            title = "Synthetisches zurückgestelltes Training")
        val records = completed + listOf(pendingSteps, pendingWorkout)
        var currentRecords = records
        var reads = 0
        val provider = object : FitnessHealthSource {
            override val requiredPermissions = FitnessHealthPolicy.REQUIRED_PERMISSIONS
            override fun availability() = FitnessHealthSource.Availability.AVAILABLE
            override suspend fun grantedPermissions() = requiredPermissions
            override suspend fun readSnapshot(): FitnessHealthSnapshot {
                reads++
                val day = Instant.ofEpochMilli(readAt).atZone(zone).toLocalDate()
                return FitnessHealthSnapshot(origin, readAt - Duration.ofDays(28).toMillis(), readAt, readAt + reads - 1L,
                    currentRecords, day.toString(), zone.id, day.atStartOfDay(zone).toInstant().toEpochMilli(), readAt, 321L)
            }
        }
        fun waitTag(tag: String) = compose.waitUntil(25_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
        try {
            store.enableFitnessImport("$token-enable", true, true)
            store.syncFitness("$token-sync", provider)
            store.syncFitness("$token-sync", provider)
            assertEquals(1, reads)
            currentRecords = records.filterNot { it.externalId == "$token-missing" }
            store.syncFitness("$token-rescan", provider)
            // Only the local synthetic cache is tested. Do not enable the real
            // provider when the default screen resumes on this CI emulator.
            store.enableFitnessImport("$token-stop", false, true)
            waitTag("workspace_tab_MORE")
            compose.onNodeWithTag("workspace_tab_MORE").performClick()
            compose.onNodeWithTag("workspace_go_FITNESS").performScrollTo().performClick()
            waitTag("fitness_daily_steps")
            compose.onNodeWithTag("fitness_daily_steps").performScrollTo().assertTextContains("321", substring = true)
            compose.onNodeWithTag("fitness_record_counts").assertTextContains("Trainings: 1", substring = true)
            compose.onNodeWithTag("fitness_record_counts").assertTextContains("Schrittintervalle: 1", substring = true)
            compose.onNodeWithTag("fitness_sport_$token-workout").performScrollTo()
                .assertTextEquals("Trainingsart: Sonstiges Training")
            compose.onNodeWithTag("fitness_duration_WORKOUT_$token-workout").performScrollTo()
                .assertTextContains("29 min 59,999 s", substring = true)
            compose.onNodeWithTag("fitness_deferred_records").performScrollTo().assertTextContains("2", substring = true)
            compose.onNodeWithTag("fitness_duration_WORKOUT_$token-pending-workout").assertDoesNotExist()
            compose.onNodeWithTag("fitness_deferred_$token-pending-workout").performScrollTo().performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Version 1: zurückgestellt:", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Version 1: zurückgestellt:", substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Historie schließen").performScrollTo().performClick()
            compose.onNodeWithTag("fitness_missing_records").performScrollTo().assertTextContains("1", substring = true)
            compose.onNodeWithTag("fitness_missing_$token-missing").performScrollTo().performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Gespeicherte Änderungshistorie").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Gespeicherte Änderungshistorie").performScrollTo().assertIsDisplayed()
            assertEquals(2, db.healthDao().historyFor(origin, FitnessRecordKind.WORKOUT.name, "$token-missing").size)
            assertEquals(2, reads)
            compose.activityRule.scenario.recreate()
            waitTag("fitness_daily_steps")
            compose.onNodeWithTag("fitness_daily_steps").performScrollTo().assertTextContains("321", substring = true)
            val reopened = Room.databaseBuilder(target, FoodDatabase::class.java, FoodDatabase.TEST_DATABASE_NAME)
                .addMigrations(FoodMigrations.MIGRATION_8_9, FoodMigrations.MIGRATION_9_10, FoodMigrations.MIGRATION_10_11, FoodMigrations.MIGRATION_11_12, FoodMigrations.MIGRATION_12_13, FoodMigrations.MIGRATION_13_14, FoodMigrations.MIGRATION_14_15, FoodMigrations.MIGRATION_15_16, FoodMigrations.MIGRATION_16_17).build()
            try {
                assertEquals(2, reopened.healthDao().latest(origin).count { it.visible })
                val pending = reopened.healthDao().latest(origin).filter {
                    it.visibilityReason == FitnessRecordVisibility.DEFERRED_END_AFTER_CAPTURE
                }
                assertEquals(setOf(pendingSteps, pendingWorkout), pending.map { it.record() }.toSet())
                assertEquals(false, reopened.healthDao().state(origin)?.enabled)
            } finally { reopened.close() }
            compose.onNodeWithTag("fitness_open_training").performScrollTo().performClick()
            waitTag("training_deferred_count")
            compose.onNodeWithTag("training_deferred_count").performScrollTo().assertTextContains("Zurückgestellte Quelltrainings: 1", substring = true)
            compose.onNodeWithTag("training_missing_count").performScrollTo().assertTextContains("1 importierte Trainings", substring = true)
            compose.onNodeWithTag("workspace_back").performClick()
            waitTag("fitness_deferred_records")
            readAt = at + 60_000L // Explicit synthetic clock; no physical Watch/time evidence.
            // Seed all synthetic consent transitions atomically. The resumed
            // default screen must never observe enabled=true and accidentally
            // start the real OS provider while this fixture is being prepared.
            db.withTransaction {
                store.enableFitnessImport("$token-reenable", true, true)
                store.syncFitness("$token-complete", provider)
                store.syncFitness("$token-complete", provider)
                store.enableFitnessImport("$token-stop-again", false, true)
            }
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("fitness_deferred_records").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("fitness_record_counts").performScrollTo().assertTextContains("Trainings: 2", substring = true)
            compose.onNodeWithTag("fitness_record_counts").assertTextContains("Schrittintervalle: 2", substring = true)
            compose.onNodeWithTag("fitness_daily_steps").performScrollTo().assertTextContains("321", substring = true)
            assertEquals(2, db.healthDao().historyFor(origin, "WORKOUT", pendingWorkout.externalId).size)
            assertEquals(3, reads)
            compose.onNodeWithTag("fitness_delete").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("fitness_delete_opt_in").performScrollTo().performClick()
            compose.onNodeWithTag("fitness_delete").performScrollTo().performClick()
            waitTag("fitness_no_cache")
            assertTrue(db.healthDao().history(origin).isEmpty())
            assertEquals(before, store.operations().first())
            assertEquals(stockBefore, db.foodDao().planningBatches())
            assertEquals(mealsBefore, db.foodDao().activeConsumptionCount())
            assertEquals(0, target.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
            assertNull((target.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetwork)
            assertEquals(3, reads)
        } catch (failure: Throwable) {
            captureDeviceFailureEvidence("fitness-flow")
            throw failure
        } finally {
            // We refused any pre-existing health state above. Never purge food.
            store.purgeLocalFitnessImport("$token-cleanup", true)
        }
    }

    @Test fun actualWorkoutDurationActionRetainsSourceSpanUnknownZeroRestartAndPrivacy(): Unit = runBlocking {
        assertTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val context = compose.activity.applicationContext
        val network = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertNull(network.activeNetwork)
        val sandbox = FoodDatabase.localSandbox(context)
        val originalFood = sandbox.foodDao().operationCount()
        val name = "synthetic-workout-duration-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, FoodDatabase::class.java, name).build()
        var db = open()
        var clock = System.currentTimeMillis()
        val origin = FitnessHealthPolicy.SAMSUNG_PACKAGE
        val raw = FitnessSourceRecord("synthetic-duration-workout", FitnessRecordKind.WORKOUT, origin,
            clock - 90_000, clock - 1_000, null, null, clock - 1, null, 0,
            "SYNTHETIC", "No Watch proof", null, 0, exerciseType = 0)
        val provider = object : FitnessHealthSource {
            var metric: Long? = 51_125
            var fail = false
            val reads = AtomicInteger()
            val durationReads = AtomicInteger()
            override val requiredPermissions = FitnessHealthPolicy.REQUIRED_PERMISSIONS
            override fun availability() = FitnessHealthSource.Availability.AVAILABLE
            override suspend fun grantedPermissions() = requiredPermissions
            override suspend fun readSnapshot(): FitnessHealthSnapshot {
                reads.incrementAndGet()
                val day = Instant.ofEpochMilli(clock).atZone(ZoneOffset.UTC).toLocalDate()
                return FitnessHealthSnapshot(origin, clock - FitnessHealthPolicy.MAX_WINDOW_MILLIS, clock, clock,
                    listOf(raw), day.toString(), "Z", day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), clock, null)
            }
            override suspend fun readWorkoutDuration(query: FitnessWorkoutDurationQuery): FitnessWorkoutDurationSnapshot {
                durationReads.incrementAndGet()
                if (fail) error("Synthetic source failure")
                return FitnessWorkoutDurationSnapshot(sourcePackage = origin, windowStart = query.startAt, windowEnd = query.endAt,
                    capturedAt = clock, records = listOf(raw), durationMillis = metric,
                    status = if (metric == null) FitnessWorkoutDurationStatus.NOT_RETURNED else FitnessWorkoutDurationStatus.AVAILABLE)
            }
        }
        val models = ViewModelStore()
        lateinit var model: FitnessViewModel
        var store = FoodStore(db) { clock }
        fun mount() = compose.runOnUiThread {
            model = ViewModelProvider(models, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = FitnessViewModel(store, provider) as T
            })[FitnessViewModel::class.java]
            compose.activity.setContent { DemoTheme {
                Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) { FitnessScreen(model) }
            } }
        }
        fun ready(predicate: () -> Boolean) = compose.waitUntil(20_000) { !model.state.value.busy && predicate() }
        fun tap(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()
        val readTag = "fitness_workout_duration_read_${raw.externalId}"
        val valueTag = "fitness_workout_duration_${raw.externalId}"
        try {
            mount(); ready { model.state.value.cache != null && model.state.value.availability != null }
            assertEquals(0, provider.durationReads.get())
            tap("fitness_opt_in"); tap("fitness_connect"); ready { model.state.value.cache?.records?.size == 1 }
            val initial = db.healthDao().history(origin)
            val core = db.healthDao().state(origin)
            tap(readTag); ready { model.state.value.cache?.knownDurations?.singleOrNull()?.workoutDurationMillis == 51_125L }
            compose.onNodeWithTag(valueTag).performScrollTo().assertTextContains("51,125", substring = true)
            clock++; provider.metric = null
            tap(readTag); ready { model.state.value.cache?.durationAttempts?.singleOrNull()?.workoutDurationStatus == "NOT_RETURNED" }
            compose.onNodeWithTag(valueTag).performScrollTo().assertTextContains("Unbekannt", substring = true)
            compose.onNodeWithTag("fitness_workout_duration_previous_${raw.externalId}").performScrollTo().assertExists()
            clock++; provider.fail = true
            tap(readTag); ready { model.state.value.cache?.durationAttempts?.singleOrNull()?.workoutDurationStatus == "READ_FAILED" }
            assertEquals(51_125L, model.state.value.cache!!.knownDurations.single().workoutDurationMillis)
            clock++; provider.fail = false; provider.metric = 0
            tap(readTag); ready { model.state.value.cache?.knownDurations?.singleOrNull()?.workoutDurationMillis == 0L }
            assertEquals(4, provider.durationReads.get())
            assertEquals(initial, db.healthDao().history(origin)); assertEquals(core, db.healthDao().state(origin))
            val zero = model.state.value.cache!!.knownDurations.single()
            val target = db.healthDao().latest(origin).single().durationTarget()
            tap("fitness_stop"); ready { model.state.value.cache?.state?.enabled == false }
            compose.onNodeWithTag(readTag).performScrollTo().assertIsNotEnabled()
            compose.runOnUiThread { compose.activity.setContent { DemoTheme {} }; models.clear() }
            compose.waitForIdle(); db.close(); db = open(); store = FoodStore(db) { clock }
            mount(); ready { model.state.value.cache?.knownDurations?.singleOrNull()?.workoutDurationMillis == 0L }
            assertEquals(zero, store.readFitnessWorkoutDuration(zero.id, provider, target))
            assertEquals(4, provider.durationReads.get()); assertEquals(1, provider.reads.get())
            compose.onNodeWithTag(valueTag).performScrollTo().assertTextContains("0 s", substring = true)
            tap("fitness_delete_opt_in"); tap("fitness_delete"); ready { model.state.value.cache?.records?.isEmpty() == true }
            assertTrue(db.healthDao().workoutDurationHistory(origin, raw.externalId).isEmpty())
            val retired = db.healthDao().run(zero.id)!!
            assertEquals("REDACTED", retired.kind); assertNull(retired.workoutDurationMillis); assertNull(retired.workoutExternalId)
            assertNull(retired.workoutDurationAttempt)
            assertEquals(0, db.foodDao().operationCount()); assertEquals(originalFood, sandbox.foodDao().operationCount())
            assertNull(network.activeNetwork)
        } catch (failure: Throwable) { captureDeviceFailureEvidence("workout-duration-flow"); throw failure }
        finally {
            compose.runOnUiThread { compose.activity.setContent { DemoTheme {} }; models.clear() }
            compose.waitForIdle(); db.close(); context.deleteDatabase(name)
        }
    }

    @Test fun actualFitnessControlsAcceptOnlyDeferredDataRetryCompleteAndStopAfterPermissionLoss(): Unit = runBlocking {
        // Real production UI/ViewModel/FoodStore/SQLite. ONLY the read-only
        // health port/clock are synthetic; no OS permission or Watch evidence.
        assertTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val context = compose.activity.applicationContext
        val network = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertNull(network.activeNetwork)
        val sandbox = FoodDatabase.localSandbox(context)
        val originalFoodOperations = sandbox.foodDao().operationCount()
        val db = Room.inMemoryDatabaseBuilder(context, FoodDatabase::class.java).build()
        val at = System.currentTimeMillis()
        val store = FoodStore(db) { at + 10_000 }
        val origin = FitnessHealthPolicy.SAMSUNG_PACKAGE
        val steps = FitnessSourceRecord("synthetic-control-steps", FitnessRecordKind.STEPS, origin,
            at - 60_000, at + 1_000, null, null, at - 1, null, 0,
            "SYNTHETIC", "No Watch proof", null, 0, steps = 9_999)
        val workout = steps.copy(externalId = "synthetic-control-workout", kind = FitnessRecordKind.WORKOUT,
            steps = null, exerciseType = 0)
        val provider = object : FitnessHealthSource {
            @Volatile var captured = at
            @Volatile var allowed = true
            val reads = AtomicInteger()
            override val requiredPermissions = FitnessHealthPolicy.REQUIRED_PERMISSIONS
            override fun availability() = FitnessHealthSource.Availability.AVAILABLE
            override suspend fun grantedPermissions() = if (allowed) requiredPermissions else emptySet()
            override suspend fun readSnapshot(): FitnessHealthSnapshot {
                reads.incrementAndGet()
                val end = captured
                val zone = ZoneId.systemDefault()
                val day = Instant.ofEpochMilli(end).atZone(zone).toLocalDate()
                return FitnessHealthSnapshot(origin, end - FitnessHealthPolicy.MAX_WINDOW_MILLIS, end, end,
                    listOf(steps, workout), day.toString(), zone.id, day.atStartOfDay(zone).toInstant().toEpochMilli(), end, 17)
            }
        }
        val models = ViewModelStore()
        lateinit var model: FitnessViewModel
        fun awaitReady(predicate: () -> Boolean) = compose.waitUntil(20_000) { !model.state.value.busy && predicate() }
        fun tap(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()
        try {
            compose.runOnUiThread {
                model = ViewModelProvider(models, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        FitnessViewModel(store, provider) as T
                })[FitnessViewModel::class.java]
                compose.activity.setContent { DemoTheme {
                    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        FitnessScreen(model)
                    }
                } }
            }
            awaitReady { model.state.value.availability != null && model.state.value.cache != null }
            assertEquals(0, provider.reads.get())
            compose.onNodeWithTag("fitness_connect").performScrollTo().assertIsNotEnabled()
            tap("fitness_opt_in"); tap("fitness_connect")
            awaitReady { model.state.value.cache?.lastSuccess != null }
            compose.onNodeWithTag("fitness_message").performScrollTo().assertTextContains("Noch zurückgestellte Quellintervalle: 2.", substring = true)
            compose.onNodeWithTag("fitness_deferred_records").performScrollTo().assertTextContains("2", substring = true)
            compose.onNodeWithTag("fitness_missing_records").assertDoesNotExist()
            compose.onNodeWithTag("fitness_record_counts").performScrollTo().assertTextContains("Trainings: 0", substring = true)
            compose.onNodeWithTag("fitness_daily_steps").performScrollTo().assertTextContains("17", substring = true)
            assertNull(db.healthDao().state(origin)?.lastErrorCode)
            val original = db.healthDao().history(origin)
            assertEquals(2, original.size); assertTrue(original.all { !it.visible })
            tap("fitness_resync"); awaitReady { provider.reads.get() == 2 }
            assertEquals(original, db.healthDao().history(origin))
            provider.captured = at + 2_000
            tap("fitness_resync"); awaitReady { provider.reads.get() == 3 && model.state.value.cache?.records?.all { it.visible } == true }
            compose.onNodeWithTag("fitness_deferred_records").assertDoesNotExist()
            compose.onNodeWithTag("fitness_message").performScrollTo().assertTextEquals("Samsung-Health-Daten lokal übernommen.")
            compose.onNodeWithTag("fitness_record_counts").performScrollTo().assertTextContains("Trainings: 1", substring = true)
            assertEquals(4, db.healthDao().history(origin).size)
            provider.allowed = false
            tap("fitness_resync"); awaitReady { model.state.value.cache?.state?.enabled == false }
            compose.onNodeWithTag("fitness_import_error_code").performScrollTo().assertTextContains("PERMISSION_MISSING", substring = true)
            assertEquals(3, provider.reads.get())
            assertEquals(4, db.healthDao().history(origin).size)
            assertEquals(0, db.foodDao().operationCount())
            assertEquals(originalFoodOperations, sandbox.foodDao().operationCount())
            assertNull(network.activeNetwork)
        } catch (failure: Throwable) {
            captureDeviceFailureEvidence("fitness-controls-flow")
            throw failure
        } finally {
            compose.runOnUiThread { compose.activity.setContent { DemoTheme {} }; models.clear() }
            compose.waitForIdle()
            db.close()
        }
    }
}
