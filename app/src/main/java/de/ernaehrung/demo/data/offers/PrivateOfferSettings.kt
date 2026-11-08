package de.ernaehrung.demo.data.offers

import android.content.Context
import android.util.AtomicFile
import de.eg.recipe.JsonOutput
import java.io.File
import java.time.Instant
import java.time.LocalDate

/** Private control metadata only: no supplier response, prices, credentials,
 * health or food history. Excluded from Android/Room backup by its location. */
internal class PrivateOfferSettings private constructor(context: Context) : OfferRefreshJournal {
    companion object {
        @Volatile private var shared: PrivateOfferSettings? = null
        fun forApp(context: Context): PrivateOfferSettings = shared ?: synchronized(this) {
            shared ?: PrivateOfferSettings(context.applicationContext).also { shared = it }
        }
    }
    data class Selection(val scope: LiveOfferScope, val automatic: Boolean)
    private val file = AtomicFile(File(context.noBackupFilesDir, "live-offer-controls-v1.json"))
    private fun read(): Map<String, Any?> = if (!file.baseFile.exists()) emptyMap() else {
        require(file.baseFile.length() <= 65536)
        val data = OfferJson.read(file.readFully().toString(Charsets.UTF_8))
        require(data["format"] == "eg-live-offer-controls-1"); data
    }
    private fun write(data: Map<String, Any?>) {
        val bytes = JsonOutput.encode(data + ("format" to "eg-live-offer-controls-1")).toByteArray()
        require(bytes.size <= 65536)
        var stream: java.io.FileOutputStream? = null
        try { stream = file.startWrite(); stream.write(bytes); file.finishWrite(stream) }
        catch (failure: Exception) { stream?.let(file::failWrite); throw failure }
    }
    @Synchronized fun selection(): Selection? {
        val value = OfferJson.objectValue(read()["selection"]) ?: return null
        val scope = LiveOfferScope(LiveRetailer.valueOf(value["retailer"] as String), value["store"] as? String,
            value["region"] as? String, value["delivery"] as? String, LocalDate.parse(value["purchaseOn"] as String))
        scope.validate()
        return Selection(scope, value["automatic"] == true)
    }
    @Synchronized fun select(scope: LiveOfferScope, automatic: Boolean) {
        scope.validate()
        write(read() + ("selection" to mapOf("retailer" to scope.retailer.name, "store" to scope.storeId,
            "region" to scope.regionId, "delivery" to scope.deliveryAreaId, "purchaseOn" to scope.purchaseOn.toString(), "automatic" to automatic)))
    }
    @Synchronized fun clearSelection() { write(read() - "selection") }
    @Synchronized override fun last(sourceId: String): OfferRefreshAttempt? {
        val attempts = OfferJson.objectValue(read()["attempts"]) ?: return null
        val attempt = OfferJson.objectValue(attempts[sourceId]) ?: return null
        return OfferRefreshAttempt(Instant.parse(attempt["at"] as String), attempt["automaticPaused"] == true)
    }
    @Synchronized override fun record(sourceId: String, attempt: OfferRefreshAttempt) {
        require(sourceId.isNotBlank() && sourceId.length <= 160)
        val data = read(); val attempts = OfferJson.objectValue(data["attempts"]).orEmpty()
        require(attempts.size < 64 || sourceId in attempts)
        write(data + ("attempts" to (attempts + (sourceId to mapOf("at" to attempt.at.toString(), "automaticPaused" to attempt.automaticPaused)))))
    }
}
