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
                    val data = JSONObject().put("proof",proof).put("source",source).put("package",targetContext.packageName)
                        .put("pid",Process.myPid()).put("sdk",Build.VERSION.SDK_INT).put("schema",db.openHelper.readableDatabase.version)
                        .put("date",date).put("first",first).put("second",second).put("location",location)
                        .put("batch",batch).put("meal",meal).put("operations",dao.operationCount()).put("meals",dao.activeConsumptionCount())
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
