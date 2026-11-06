package de.ernaehrung.demo.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.ernaehrung.demo.data.backup.*
import de.ernaehrung.demo.data.local.*
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class BackupExportRequest(val id: String, val suggestedName: String)
internal data class BackupPreview(val archive: BackupArchive, val digest: String)
internal data class BackupUiState(val busy: Boolean = false, val exportRequest: BackupExportRequest? = null,
    val preview: BackupPreview? = null, val result: String? = null, val error: String? = null)

internal class BackupViewModel(private val store: FoodStore, private val documents: BackupDocuments,
    private val applicationId: String) : ViewModel() {
    private val mutable = MutableStateFlow(BackupUiState())
    val state = mutable.asStateFlow()
    private var work: Job? = null
    private var pending: Pair<String, ByteArray>? = null
    private var generation = 0L

    fun clearPreview() {
        if (mutable.value.busy) return
        generation++; work?.cancel()
        mutable.value = BackupUiState()
    }
    fun export(password: CharArray, fitness: Boolean, confirmed: Boolean) {
        if (mutable.value.busy) { password.fill('\u0000'); return }
        generation++; val attempt = generation
        mutable.value = BackupUiState(busy = true)
        work = viewModelScope.launch {
            try {
                val archive = withContext(Dispatchers.IO) { store.captureBackup(applicationId, fitness, confirmed) }
                val encrypted = withContext(Dispatchers.Default) {
                    val plain = BackupArchiveCodec.encode(archive)
                    try { BackupEncryption().seal(plain, password) } finally { plain.fill(0) }
                }
                ensureActive(); check(generation == attempt)
                val id = UUID.randomUUID().toString()
                pending = id to encrypted
                val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(archive.createdAt))
                mutable.value = BackupUiState(busy = true, exportRequest = BackupExportRequest(id, "EG-$stamp.egbackup"))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = BackupUiState(error = "Sicherung konnte nicht vorbereitet werden. Der lokale Datenbestand wurde nicht verändert.") }
            finally { password.fill('\u0000') }
        }
    }
    /** Consume the launch event before starting the external Activity; rotation cannot launch it twice. */
    fun consumeExportRequest(id: String): Boolean {
        if (pending?.first != id || mutable.value.exportRequest?.id != id) return false
        mutable.value = mutable.value.copy(exportRequest = null)
        return true
    }
    fun finishExport(requestId: String?, uri: Uri?, onWritten: (Uri) -> Unit = {}) {
        val entry = pending?.takeIf { it.first == requestId } ?: return // No transfer authorization survives process death.
        val bytes = entry.second
        pending = null
        if (uri == null) {
            bytes.fill(0); mutable.value = BackupUiState(result = "Speichern abgebrochen. Keine Sicherung geschrieben."); return
        }
        work = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { documents.write(uri, bytes) }
                ensureActive()
                mutable.value = BackupUiState(result = "Verschlüsselte Sicherung gespeichert.")
                onWritten(uri)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = BackupUiState(error = "Sicherungsdatei konnte nicht vollständig geschrieben werden. Eine unvollständige Datei lässt sich nicht wiederherstellen; deine lokalen Daten bleiben erhalten.") }
            finally { bytes.fill(0) }
        }
    }
    fun preview(uri: Uri, password: CharArray) {
        if (mutable.value.busy) { password.fill('\u0000'); return }
        generation++; val attempt = generation
        mutable.value = BackupUiState(busy = true)
        work = viewModelScope.launch {
            try {
                val envelope = withContext(Dispatchers.IO) { documents.read(uri) }
                val preview = withContext(Dispatchers.Default) {
                    val plain = BackupEncryption().open(envelope, password)
                    try {
                        val archive = BackupArchiveCodec.decode(plain)
                        require(archive.applicationId == applicationId)
                        BackupPreview(archive, BackupArchiveCodec.digest(plain))
                    } finally { plain.fill(0) }
                }
                withContext(Dispatchers.IO) { store.validateBackupForPreview(preview.archive, applicationId) }
                ensureActive(); check(generation == attempt)
                mutable.value = BackupUiState(preview = preview)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = BackupUiState(error = "Datei oder Passwort konnte nicht geprüft werden, oder diese Sicherung passt nicht zur App-Variante. Keine Daten wurden übernommen.") }
            finally { password.fill('\u0000') }
        }
    }
    fun restore(digest: String, confirmed: Boolean, fitnessConfirmed: Boolean) {
        if (mutable.value.busy) return
        val preview = mutable.value.preview ?: return
        if (preview.digest != digest || !confirmed || (preview.archive.fitnessIncluded && !fitnessConfirmed)) {
            mutable.value = mutable.value.copy(error = "Den genauen geprüften Umfang ausdrücklich bestätigen."); return
        }
        mutable.value = mutable.value.copy(busy = true, error = null, result = null)
        val key = UUID.randomUUID().toString()
        work = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { store.restoreBackup(key, preview.archive, digest, applicationId, true) }
                mutable.value = BackupUiState(result = if (result.status == BackupRestoreStatus.RESTORED)
                    "Sicherung vollständig wiederhergestellt. Gesundheitsübernahme bleibt ausgeschaltet."
                    else "Diese Daten sind bereits vorhanden. Bestehende und spätere Änderungen bleiben erhalten.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: IllegalArgumentException) { mutable.value = mutable.value.copy(busy = false, error = failure.message ?: "Sicherung kann hier nicht sicher übernommen werden.") }
            catch (_: Exception) { mutable.value = mutable.value.copy(busy = false, error = "Wiederherstellung abgebrochen. Es wurden keine teilweise übernommenen Daten gespeichert.") }
        }
    }
    override fun onCleared() {
        generation++; work?.cancel(); pending?.second?.fill(0); pending = null
        super.onCleared()
    }
}
