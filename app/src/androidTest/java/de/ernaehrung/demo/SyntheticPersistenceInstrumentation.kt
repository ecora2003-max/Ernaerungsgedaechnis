package de.ernaehrung.demo

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.AtomicFile
import androidx.test.runner.AndroidJUnitRunner
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.data.offers.*
import de.ernaehrung.demo.data.ai.*
import de.ernaehrung.demo.data.fitness.*
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

/** Explicit CI phases in separate actual app processes. No test component is
 * shipped in the application; synthetic fixtures never touch a physical phone.
 */
class SyntheticPersistenceInstrumentation : AndroidJUnitRunner() {
    private lateinit var request: Bundle
    override fun onCreate(arguments: Bundle?) {
        request = Bundle(arguments ?: Bundle())
        super.onCreate(arguments)
    }

    override fun onStart() {
        // Normal connectedAndroidTest remains the unchanged AndroidJUnitRunner
        // suite. Only an explicit separate host invocation selects a proof phase.
        if (!request.containsKey("phase")) { super.onStart(); return }
        val result = Bundle()
        try {
            check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone")) {
                "Persistence proof requires a disposable emulator"
            }
            check(targetContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) { "Debug subject required" }
            val proof = requireNotNull(request.getString("proof_id"))
            require(proof.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
            val source = requireNotNull(request.getString("source_sha"))
            require(source.matches(Regex("[0-9a-f]{40}")))
            val phase = requireNotNull(request.getString("phase"))
            require(phase == "seed" || phase == "read")
            val connectivity = targetContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            check(connectivity.activeNetwork == null) { "Actual active network must be absent" }
            runBlocking {
                val db = FoodDatabase.localSandbox(targetContext); val dao = db.foodDao(); val store = FoodStore(db)
                val journal = AtomicFile(File(targetContext.filesDir,"eg-ci-restart-$proof.json"))
                val key = "ci-restart-$proof"
                if (phase == "seed") {
                    check(!journal.baseFile.exists()) { "Fresh proof identifier required" }
                    val date = AppCalendar.today().toString()
                    val first = store.rememberProduct("$key-v1",ProductInput("Synthetic restart original $proof"))
                    val second = store.rememberProduct("$key-v2",ProductInput("Synthetic restart revised $proof",energyKcal = 0.0),
                        requireNotNull(dao.version(first)).productId,"Synthetic revision",first)
                    val location = store.addLocation("$key-location","Synthetic restart storage $proof",true)
                    val batch = store.receive("$key-receive",BatchInput(first,location,100_000L,date,release = ReleaseStatus.RELEASED),true)
                    store.waste("$key-waste",WasteInput(batch,25_000L,date,"Synthetic restart waste"),true)
                    val meal = store.consume("$key-eat",ConsumptionInput(first,date,"Synthetic restart meal",10_000L,
                        listOf(Allocation(batch,10_000L))),true)
                    check(dao.balance(batch) == 65_000L)
                    val offerSettings = PrivateOfferSettings.forApp(targetContext)
                    val refreshAt = Instant.now().toEpochMilli()
                    offerSettings.record(key, OfferRefreshAttempt(Instant.ofEpochMilli(refreshAt), true))
                    offerSettings.select(LiveOfferScope(LiveRetailer.KNUSPR, null, null, key, LocalDate.parse(date)), true)
                    val login = PrivateKnusprStore.forApp(targetContext)
                    val issuer = URI("https://login.example.test")
                    val authServer = KnusprAuthorizationServer(issuer, issuer.resolve("/authorize"), issuer.resolve("/token"), null, emptyList())
                    val pending = KnusprPendingAuthorization(authServer, key, proof, "synthetic-verifier-$proof",
                        "${targetContext.packageName}://knuspr-oauth", refreshAt)
                    check(login.stage(login.beginAttempt(), pending)); login.claim(proof)
                    check(login.finish(proof, KnusprToken("synthetic-access-$proof", null, null, key, authServer)))
                    val gemini = PrivateGeminiSettings.forApp(targetContext)
                    gemini.saveKey("synthetic-key-$proof"); gemini.mode(DirectRecipeMode.CLOUD)
                    gemini.model("models/gemini-synthetic-fixture")
                    // Synthetic raw intervals in real SQLite/WAL. No OS health
                    // read or Watch proof. Consent is stopped before Main starts.
                    val origin = FitnessHealthPolicy.SAMSUNG_PACKAGE
                    check(db.healthDao().history(origin).isEmpty() && db.healthDao().state(origin)?.enabled != true)
                    val healthAt = System.currentTimeMillis()
                    val healthDay = Instant.ofEpochMilli(healthAt).atZone(ZoneOffset.UTC).toLocalDate()
                    val pendingSteps = FitnessSourceRecord("$key-pending-steps", FitnessRecordKind.STEPS, origin,
                        healthAt - 60_000, healthAt + 60_000, null, null, healthAt - 1, null, 0,
                        "SYNTHETIC", "No Watch proof", null, 0, steps = 9_999)
                    val pendingWorkout = pendingSteps.copy(externalId = "$key-pending-workout", kind = FitnessRecordKind.WORKOUT,
                        steps = null, exerciseType = 0)
                    val complete = pendingWorkout.copy(externalId = "$key-complete-workout", endAt = healthAt - 1)
                    val provider = object : FitnessHealthSource {
                        override val requiredPermissions = FitnessHealthPolicy.REQUIRED_PERMISSIONS
                        override fun availability() = FitnessHealthSource.Availability.AVAILABLE
                        override suspend fun grantedPermissions() = requiredPermissions
                        override suspend fun readSnapshot() = FitnessHealthSnapshot(origin, healthAt - FitnessHealthPolicy.MAX_WINDOW_MILLIS,
                            healthAt, healthAt, listOf(pendingSteps, pendingWorkout, complete), healthDay.toString(), "Z",
                            healthDay.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), healthAt, 17)
                        override suspend fun readWorkoutDuration(query: FitnessWorkoutDurationQuery) = FitnessWorkoutDurationSnapshot(
                            sourcePackage = origin, windowStart = query.startAt, windowEnd = query.endAt, capturedAt = System.currentTimeMillis(),
                            records = listOf(complete), durationMillis = 0, status = FitnessWorkoutDurationStatus.AVAILABLE)
                    }
                    store.enableFitnessImport("$key-health-enable", true, true)
                    store.syncFitness("$key-health-sync", provider)
                    store.readFitnessWorkoutDuration("$key-health-duration", provider,
                        db.healthDao().latestWorkout(origin, complete.externalId)!!.durationTarget())
                    store.enableFitnessImport("$key-health-stop", false, true)
                    val data = JSONObject().put("proof",proof).put("source",source).put("package",targetContext.packageName)
                        .put("pid",Process.myPid()).put("sdk",Build.VERSION.SDK_INT).put("schema",db.openHelper.readableDatabase.version)
                        .put("date",date).put("first",first).put("second",second).put("location",location)
                        .put("batch",batch).put("meal",meal).put("operations",dao.operationCount()).put("meals",dao.activeConsumptionCount())
                        .put("offerRefreshAt",refreshAt).put("healthCapturedAt",healthAt)
                    val output = journal.startWrite()
                    try { output.write(data.toString().toByteArray(Charsets.UTF_8)); journal.finishWrite(output) }
                    catch (failure: Throwable) { journal.failWrite(output); throw failure }
                    // Deliberately leave the real Room/WAL connection open.
                    // The host kills the app; the next process must recover it.
                } else {
                    val data = journal.openRead().bufferedReader().use { JSONObject(it.readText()) }
                    check(data.getString("proof") == proof && data.getString("source") == source)
                    check(data.getString("package") == targetContext.packageName && data.getInt("sdk") == Build.VERSION.SDK_INT)
                    check(data.getInt("pid") != Process.myPid()) { "This is not a new process" }
                    check(data.getInt("schema") == db.openHelper.readableDatabase.version)
                    val offerSettings = PrivateOfferSettings.forApp(targetContext)
                    check(offerSettings.last(key) == OfferRefreshAttempt(Instant.ofEpochMilli(data.getLong("offerRefreshAt")), true))
                    check(offerSettings.selection()?.let { it.automatic && it.scope.deliveryAreaId == key && it.scope.storeId == null } == true)
                    val login = PrivateKnusprStore.forApp(targetContext)
                    check(login.read().token?.let { it.access == "synthetic-access-$proof" && it.clientId == key } == true)
                    check(login.read().pending == null && login.read().completing == null)
                    try { login.claim(proof); error("Completed OAuth nonce replay accepted after process restart") }
                    catch (_: IllegalArgumentException) { }
                    // Default-deny production sources cannot be activated by
                    // restored selection, automatic preference or a login token.
                    check(LiveOfferRuntime(emptyMap(), journal = offerSettings).refresh(requireNotNull(offerSettings.selection()).scope,
                        automatic = true).status == LiveRefreshStatus.BLOCKED)
                    login.clear(); offerSettings.clearSelection()
                    val gemini = PrivateGeminiSettings.forApp(targetContext)
                    check(gemini.read().let { it.key == "synthetic-key-$proof" && it.mode == DirectRecipeMode.CLOUD &&
                        it.model == "models/gemini-synthetic-fixture" })
                    gemini.clear()
                    val origin = FitnessHealthPolicy.SAMSUNG_PACKAGE
                    val healthAt = data.getLong("healthCapturedAt")
                    val healthState = requireNotNull(db.healthDao().state(origin))
                    check(!healthState.enabled && healthState.lastSuccessRunId == "$key-health-sync" && healthState.lastErrorCode == null)
                    val healthRows = db.healthDao().latest(origin)
                    check(healthRows.size == 3 && db.healthDao().history(origin).size == 3)
                    val deferred = healthRows.filter { it.visibilityReason == FitnessRecordVisibility.DEFERRED_END_AFTER_CAPTURE }
                    check(deferred.size == 2 && deferred.all { !it.visible && it.revision == 1L && it.endAt == healthAt + 60_000 && it.sourceModifiedAt == healthAt - 1 })
                    check(deferred.single { it.kind == "STEPS" }.steps == 9_999L)
                    check(healthRows.single { it.visible }.let { it.externalId == "$key-complete-workout" && it.endAt == healthAt - 1 })
                    val retryOnly = object : FitnessHealthSource {
                        override val requiredPermissions = FitnessHealthPolicy.REQUIRED_PERMISSIONS
                        override fun availability(): FitnessHealthSource.Availability = error("Retry must not access provider")
                        override suspend fun grantedPermissions(): Set<String> = error("Retry must not access provider")
                        override suspend fun readSnapshot(): FitnessHealthSnapshot = error("Retry must not access provider")
                    }
                    check(store.syncFitness("$key-health-sync", retryOnly).aggregatedSteps == 17L)
                    val complete = healthRows.single { it.visible }
                    val duration = store.readFitnessWorkoutDuration("$key-health-duration", retryOnly, complete.durationTarget())
                    check(duration.workoutDurationMillis == 0L && duration.workoutDurationStatus == "AVAILABLE" && duration.workoutDurationAttempt == 1L)
                    check(duration.workoutExternalId == complete.externalId && duration.workoutRecordRevision == complete.revision &&
                        duration.workoutRecordFingerprint == complete.fingerprint)
                    check(db.healthDao().currentWorkoutDurations(origin, true).single() == duration)
                    check(db.healthDao().latest(origin) == healthRows && db.healthDao().state(origin) == healthState)
                    val date = data.getString("date"); val first = data.getString("first"); val second = data.getString("second")
                    val location = data.getString("location"); val batch = data.getString("batch"); val meal = data.getString("meal")
                    check(dao.balance(batch) == 65_000L && requireNotNull(dao.batch(batch)).versionId == first)
                    check(requireNotNull(dao.version(first)).energyKcal == null && requireNotNull(dao.version(second)).energyKcal == 0.0)
                    check(requireNotNull(dao.consumption(meal)).let { it.productVersionId == first && it.date == date && it.amount == 10_000L })
                    check(store.receive("$key-receive",BatchInput(first,location,100_000L,date,release = ReleaseStatus.RELEASED),true) == batch)
                    check(store.waste("$key-waste",WasteInput(batch,25_000L,date,"Synthetic restart waste"),true) == batch)
                    check(store.consume("$key-eat",ConsumptionInput(first,date,"Synthetic restart meal",10_000L,
                        listOf(Allocation(batch,10_000L))),true) == meal)
                    try {
                        store.waste("$key-unconfirmed",WasteInput(batch,1_000L,date,"Unconfirmed after restart"),false)
                        error("Unconfirmed operation was accepted")
                    } catch (_: IllegalArgumentException) { }
                    check(dao.operation("$key-unconfirmed") == null && dao.balance(batch) == 65_000L)
                    check(dao.operationCount() == data.getInt("operations") && dao.activeConsumptionCount() == data.getInt("meals"))
                    db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { check(it.count == 0) }
                    check(store.readStockHistory(StockHistoryQuery(batchId = batch)).entries.map { it.operation.id }.toSet() ==
                        setOf("$key-receive","$key-waste","$key-eat"))
                }
                check(connectivity.activeNetwork == null)
            }
            result.putString("EG_RESTART_STATUS","PASS")
            result.putString("EG_RESTART_PHASE",phase)
            result.putString("EG_RESTART_PROOF",proof)
            result.putString("EG_RESTART_SOURCE",source)
            result.putString("EG_RESTART_PID",Process.myPid().toString())
            result.putString("EG_RESTART_SDK",Build.VERSION.SDK_INT.toString())
            finish(Activity.RESULT_OK,result)
        } catch (failure: Throwable) {
            result.putString("EG_RESTART_STATUS","FAIL")
            result.putString("EG_RESTART_ERROR",failure.javaClass.name + ": " + failure.message)
            finish(Activity.RESULT_CANCELED,result)
        }
    }
}
