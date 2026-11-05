package de.ernaehrung.demo.data.fitness

import android.os.Build
import android.os.RemoteException
import androidx.annotation.RequiresApi
import java.io.IOException
import kotlinx.coroutines.CancellationException

internal enum class FitnessReadStage(val description: String) {
    PROVIDER("die Gesundheitsverbindung öffnen"), PERMISSIONS("die Leserechte prüfen"),
    CHANGE_TOKEN("den Änderungsstand vorbereiten"), STEPS("Schrittintervalle lesen"),
    STEP_MAPPING("Schrittintervalle übernehmen"), WORKOUTS("Trainings lesen"),
    WORKOUT_MAPPING("Trainings übernehmen"), DAILY_STEPS("den Tageswert der Schritte lesen"),
    CHANGE_CHECK("den abschließenden Änderungsstand prüfen")
}
internal enum class FitnessReadReason { BAD_REQUEST, STATE, IO, REMOTE, OTHER }

/** No original exception message/cause/record ever enters UI, database or logs. */
internal class FitnessReadFailure(val stage: FitnessReadStage, val reason: FitnessReadReason,
    val platformCode: Int?) : IllegalStateException("HEALTH_READ_${stage.name}_${reason.name}" +
        (platformCode?.let { "_HC_$it" } ?: ""))

internal object FitnessImportDiagnostics {
    private val validationCodes = setOf("HEALTH_SOURCE_SCOPE", "HEALTH_WINDOW_INVALID", "HEALTH_IMPORT_LIMIT",
        "HEALTH_DUPLICATE_SOURCE_ID", "HEALTH_DAY_INVALID", "HEALTH_AGGREGATE_INVALID", "HEALTH_RECORD_TIME_INVALID",
        "HEALTH_RECORD_INTERVAL_INVALID", "HEALTH_RECORD_AFTER_CAPTURE", "HEALTH_RECORD_OUTSIDE_WINDOW",
        "HEALTH_SOURCE_MODIFIED_TIME_INVALID", "HEALTH_SOURCE_VERSION_INVALID", "HEALTH_OFFSET_INVALID",
        "HEALTH_METADATA_INVALID", "HEALTH_STEP_INVALID", "HEALTH_WORKOUT_INVALID", "HEALTH_CACHE_AMBIGUOUS",
        "HEALTH_CACHE_INVALID", "HEALTH_REVISION_LIMIT", "HEALTH_AUTHORIZATION_CHANGED")
    suspend fun <T> read(stage: FitnessReadStage, action: suspend () -> T): T = try { action() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (permission: SecurityException) { throw permission }
    catch (permission: FitnessPermissionMissing) { throw permission }
    catch (failure: Exception) {
        val reason = when (failure) {
            is IllegalArgumentException -> FitnessReadReason.BAD_REQUEST
            is IOException -> FitnessReadReason.IO
            is RemoteException -> FitnessReadReason.REMOTE
            is IllegalStateException -> FitnessReadReason.STATE
            else -> FitnessReadReason.OTHER
        }
        throw FitnessReadFailure(stage, reason,
            if (Build.VERSION.SDK_INT >= 34) frameworkCode(failure) else null)
    }
    @RequiresApi(34)
    private fun frameworkCode(failure: Throwable): Int? {
        var item: Throwable? = failure
        repeat(4) {
            val current = item ?: return null
            if (current is android.health.connect.HealthConnectException)
                return current.errorCode.takeIf { it in 1..100 }
            item = current.cause
        }
        return null
    }
    fun code(failure: Exception): String = when (failure) {
        is FitnessPermissionMissing, is SecurityException -> "PERMISSION_MISSING"
        is FitnessSourceChanged -> "SOURCE_CHANGED_RETRY"
        is FitnessReadFailure -> requireNotNull(failure.message)
        else -> failure.message?.takeIf { it in validationCodes } ?: "IMPORT_FAILED"
    }
    /** Stored strings are also fail closed: never echo arbitrary old errors. */
    fun safeCode(code: String?): String {
        if (code in validationCodes || code in setOf("PERMISSION_MISSING", "SOURCE_CHANGED_RETRY", "IMPORT_FAILED")) return requireNotNull(code)
        val known = FitnessReadStage.entries.any { stage -> FitnessReadReason.entries.any { reason ->
            val base = "HEALTH_READ_${stage.name}_${reason.name}"
            code == base || (code?.startsWith(base + "_HC_") == true &&
                code.substringAfterLast("_HC_").toIntOrNull() in 1..100)
        } }
        return if (known) requireNotNull(code) else "IMPORT_FAILED"
    }
    fun explain(storedCode: String?): String {
        val code = safeCode(storedCode)
        val text = when {
            code == "PERMISSION_MISSING" -> "Leserechte fehlen. Die letzte lokale Kopie bleibt lesbar."
            code == "SOURCE_CHANGED_RETRY" -> "Samsung-Health-Daten haben sich während der Übernahme geändert. Nach abgeschlossener Synchronisierung erneut versuchen."
            code == "HEALTH_RECORD_AFTER_CAPTURE" -> "Ein Quellintervall endet nach dem gelesenen Zeitpunkt. Die bisherige Kopie bleibt erhalten."
            code == "HEALTH_SOURCE_MODIFIED_TIME_INVALID" -> "Ein Quelländerungszeitpunkt ist ungültig. Datum und Uhrzeit von Telefon und Uhr prüfen."
            code.startsWith("HEALTH_READ_") -> {
                val stage = FitnessReadStage.entries.firstOrNull { code.startsWith("HEALTH_READ_${it.name}_") }
                "Health Connect konnte ${stage?.description ?: "die Gesundheitsdaten lesen"} nicht abschließen. Die bisherige Kopie bleibt erhalten."
            }
            code in validationCodes -> "Die gelieferten Gesundheitsdaten konnten nicht vollständig geprüft werden. Die bisherige Kopie bleibt erhalten."
            else -> "Übernahme nicht möglich. Die zuletzt erfolgreich gespeicherten Daten bleiben erhalten."
        }
        return "$text Fehlercode: $code."
    }
}
