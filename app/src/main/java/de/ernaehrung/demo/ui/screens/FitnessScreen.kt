package de.ernaehrung.demo.ui.screens

import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.metadata.Metadata
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.ernaehrung.demo.FitnessPrivacyActivity
import de.ernaehrung.demo.data.fitness.*
import de.ernaehrung.demo.data.local.*
import de.ernaehrung.demo.ui.FitnessViewModel
import de.ernaehrung.demo.ui.FitnessPresentation
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
private fun rememberFitnessModel(): FitnessViewModel {
    val context = LocalContext.current.applicationContext
    val factory = remember(context) { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
            FitnessViewModel(FoodStore(FoodDatabase.localSandbox(context)), AndroidHealthConnectSource(context)) as T
    } }
    return viewModel(key = "local-fitness", factory = factory)
}

@Composable
internal fun FitnessScreen(model: FitnessViewModel = rememberFitnessModel(), onOpenBody: (() -> Unit)? = null) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        model.onPermissionResult() // Actual grants are reread; callback data grants no consent.
    }
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.onForeground() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.permissionLaunch) {
        val id = state.permissionLaunch
        if (id != null && model.consumePermissionLaunch(id)) {
            try { launcher.launch(model.requiredPermissions) }
            catch (_: Exception) { model.permissionLaunchFailed() }
        }
    }
    var approved by remember { mutableStateOf(false) }
    var deleteApproved by remember { mutableStateOf(false) }
    var shown by remember { mutableIntStateOf(20) }
    fun time(at: Long) = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss z")
        .format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
    fun offset(seconds: Int?) = seconds?.let { ZoneOffset.ofTotalSeconds(it).id } ?: "unbekannt"
    Column(Modifier.fillMaxWidth().testTag("fitness_screen"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Fitness · Samsung Health", style = MaterialTheme.typography.headlineSmall)
        onOpenBody?.let { open ->
            OutlinedButton(open, Modifier.testTag("fitness_open_body")) { Text("Körpermessungen manuell erfassen") }
        }
        Text("Galaxy Watch 4 synchronisiert zunächst mit Samsung Health auf dem Smartphone. Dort müssen Schritte und Training für Health Connect freigegeben sein. Die Übernahme kann verzögert sein.")
        Text(when (state.availability) {
            FitnessHealthSource.Availability.AVAILABLE -> "Health Connect ist verfügbar."
            FitnessHealthSource.Availability.INSTALL_OR_UPDATE_REQUIRED -> "Health Connect muss installiert oder aktualisiert werden."
            FitnessHealthSource.Availability.UNSUPPORTED -> "Health Connect ist auf diesem Gerät nicht verfügbar."
            null -> "Gesundheitsverbindung wird geprüft …"
        }, Modifier.testTag("fitness_source_status"))
        if (state.availability == FitnessHealthSource.Availability.INSTALL_OR_UPDATE_REQUIRED &&
            Build.VERSION.SDK_INT < 34) {
            OutlinedButton({
                // Only this explicit public-store action may leave the app.
                // Installing the provider never grants local import consent.
                val provider = "com.google.android.apps.healthdata" // Same provider as the manifest query.
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=$provider&url=healthconnect%3A%2F%2Fonboarding"))
                        .setPackage("com.android.vending").putExtra("callerId", context.packageName))
                } catch (_: ActivityNotFoundException) {
                    try { context.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=$provider"))) }
                    catch (_: RuntimeException) { model.providerInstallUnavailable() }
                } catch (_: SecurityException) { model.providerInstallUnavailable() }
            }, Modifier.testTag("fitness_install_provider")) { Text("Health Connect im Play Store installieren oder aktualisieren") }
        }
        state.message?.let { Text(it, Modifier.testTag("fitness_message")) }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val cache = state.cache
        val enabled = cache?.state?.enabled == true
        if (!enabled) {
            Row {
                Checkbox(approved, { approved = it }, Modifier.testTag("fitness_opt_in"))
                Text("Schritte und Trainings aus Samsung Health nur auf diesem Gerät speichern und beim Öffnen automatisch aktualisieren.")
            }
            Button({ approved = false; model.connect() }, enabled = approved && !state.busy &&
                !state.awaitingPermission && state.availability == FitnessHealthSource.Availability.AVAILABLE,
                modifier = Modifier.testTag("fitness_connect")) { Text("Leserechte prüfen und Daten übernehmen") }
        } else {
            Text("Automatische Übernahme beim Öffnen eingeschaltet. Nur Schritte und Trainings werden gelesen.")
            Button({ model.resync() }, enabled = !state.busy && !state.awaitingPermission,
                modifier = Modifier.testTag("fitness_resync")) { Text("Jetzt erneut synchronisieren") }
            OutlinedButton({ model.stop() }, Modifier.testTag("fitness_stop")) { Text("Übernahme ausschalten") }
        }
        OutlinedButton({
            try { context.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }
            catch (_: Exception) { model.settingsUnavailable() }
        }) { Text("Health-Connect-Rechte verwalten") }
        OutlinedButton({ context.startActivity(Intent(context, FitnessPrivacyActivity::class.java)) }) { Text("Datenschutz der Gesundheitsverbindung") }
        val success = cache?.lastSuccess
        if (success == null) Text("Noch keine erfolgreich übernommene Gesundheitskopie.", Modifier.testTag("fitness_no_cache"))
        else {
            Text("Letzte erfolgreiche Übernahme: ${time(success.recordedAt)}", Modifier.testTag("fitness_last_sync"))
            Text("Gelesenes Fenster: ${time(requireNotNull(success.windowStart))} bis ${time(requireNotNull(success.windowEnd))}. Ältere Einträge bleiben gespeichert und wurden außerhalb dieses Fensters nicht neu abgeglichen.")
            Text("Schritte am ${success.day} (${success.dayZone}): ${success.aggregatedSteps?.toString() ?: "Unbekannt"}",
                Modifier.testTag("fitness_daily_steps"), style = MaterialTheme.typography.titleMedium)
            Text("Der Tageswert stammt aus Health Connect. Einzelne Telefon-/Uhrintervalle werden hier nicht zusammengezählt. Samsung Health als Quelle allein belegt kein bestimmtes Uhrenmodell.")
        }
        cache?.state?.lastErrorCode?.let {
            Text(FitnessImportDiagnostics.explain(it), Modifier.testTag("fitness_import_error_code"))
        }
        val visible = cache?.records.orEmpty().filter { it.visible }
        Text("Gespeicherte Trainings: ${visible.count { it.kind == FitnessRecordKind.WORKOUT.name }} · Schrittintervalle: ${visible.count { it.kind == FitnessRecordKind.STEPS.name }}",
            Modifier.testTag("fitness_record_counts"))
        visible.take(shown).forEach { row ->
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (row.kind == FitnessRecordKind.STEPS.name) "Schrittintervall: ${row.steps} Schritte"
                    else row.title?.takeIf { it.isNotBlank() } ?: "Training aus Samsung Health",
                    style = MaterialTheme.typography.titleSmall)
                Text("${time(row.startAt)} bis ${time(row.endAt)} · ${FitnessPresentation.duration(row.endAt - row.startAt)}",
                    Modifier.testTag("fitness_duration_${row.kind}_${row.externalId}"))
                Text("Quelle: Samsung Health · ${row.manufacturer?.takeIf { it.isNotBlank() } ?: "Hersteller unbekannt"} · ${row.model?.takeIf { it.isNotBlank() } ?: "Gerätemodell unbekannt"}")
                Text(when (row.recordingMethod) {
                    Metadata.RECORDING_METHOD_MANUAL_ENTRY -> "In der Quelle manuell erfasst"
                    Metadata.RECORDING_METHOD_ACTIVELY_RECORDED -> "In der Quelle aktiv aufgezeichnet"
                    Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED -> "In der Quelle automatisch aufgezeichnet"
                    else -> "Aufzeichnungsmethode unbekannt"
                })
                Text("Erfasster Gerätetyp: ${FitnessPresentation.device(row.deviceType)}")
                if (row.kind == FitnessRecordKind.WORKOUT.name) {
                    Text("Trainingsart: ${FitnessPresentation.sport(row.exerciseType)}",
                        Modifier.testTag("fitness_sport_${row.externalId}"))
                    Text("Trainingsenergie und Herzfrequenz: unbekannt")
                }
                Text("Quelländerung: ${time(row.sourceModifiedAt)} · lokale Version ${row.revision}")
                Text("Zeitzonenversatz der Quelle: ${offset(row.startOffsetSeconds)} / ${offset(row.endOffsetSeconds)}")
                TextButton({ model.showHistory(row) }) { Text("Änderungen dieses Eintrags") }
            } }
        }
        if (visible.size > shown) OutlinedButton({ shown += 20 }) { Text("Weitere Einträge anzeigen") }
        val noLongerVisible = cache?.records.orEmpty().filterNot { it.visible }
        if (noLongerVisible.isNotEmpty()) {
            Text("Im vollständig gelesenen Fenster nicht mehr sichtbar: ${noLongerVisible.size}",
                Modifier.testTag("fitness_missing_records"))
            Text("Das kann eine Quelländerung oder Löschung sein. Die gespeicherte Historie bleibt erhalten; diese Einträge zählen nicht zum aktuellen Bestand.")
            noLongerVisible.take(shown).forEach { row ->
                TextButton({ model.showHistory(row) }, Modifier.testTag("fitness_missing_${row.externalId}")) {
                    Text("Historie: ${row.title?.takeIf { it.isNotBlank() } ?: if (row.kind == FitnessRecordKind.STEPS.name) "Schrittintervall" else "Training"} · ${time(row.startAt)}")
                }
            }
            if (noLongerVisible.size > shown) OutlinedButton({ shown += 20 }) { Text("Weitere nicht sichtbare Einträge anzeigen") }
        }
        state.history?.let { rows ->
            Text("Gespeicherte Änderungshistorie", style = MaterialTheme.typography.titleSmall)
            rows.forEach { row -> Text("Version ${row.revision}: ${if (row.visible) "aus Quelle gelesen" else "im vollständigen Fenster nicht mehr sichtbar"} · ${time(row.startAt)} · ${if (row.kind == "STEPS") "${row.steps} Schritte" else row.title ?: "Training"}") }
            TextButton({ model.closeHistory() }) { Text("Historie schließen") }
        }
        if (cache?.lastSuccess != null || cache?.records?.isNotEmpty() == true) {
            Text("Die lokale Kopie lässt sich löschen. Deine Ernährung, Vorräte und Daten in Samsung Health bleiben erhalten.")
            Row {
                Checkbox(deleteApproved, { deleteApproved = it }, Modifier.testTag("fitness_delete_opt_in"))
                Text("Lokale Samsung-Health-Kopie einschließlich Änderungshistorie löschen")
            }
            Button({ deleteApproved = false; model.purge(true) }, enabled = deleteApproved,
                modifier = Modifier.testTag("fitness_delete")) { Text("Lokale Gesundheitskopie löschen") }
        }
    }
}
