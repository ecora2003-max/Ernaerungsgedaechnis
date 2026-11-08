package de.ernaehrung.demo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.ernaehrung.demo.data.offers.*
import de.ernaehrung.demo.ui.DemoTheme
import kotlinx.coroutines.*

/** Only a one-shot callback for a live private PKCE attempt. It cannot select a
 * provider/endpoint, run a tool, import a price or book an actual event. */
class KnusprOAuthActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var completing = false
    private var message by mutableStateOf("Knuspr-Anmeldung wird geprüft …")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DemoTheme { Surface { Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Knuspr verbinden", style = MaterialTheme.typography.headlineMedium)
            Text(message)
            OutlinedButton({ returnToApp() }) { Text("Zur App zurück") }
        } } } }
        handle(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }
    private fun handle(intent: Intent) {
        if (completing) return
        val uri = intent.data ?: return
        intent.data = null // Never keep the one-shot authorization code in task/saved intent state.
        if (uri.scheme != packageName || uri.host != "knuspr-oauth" || uri.toString().length > 16384) {
            message = "Keine passende laufende Anmeldung. Verbindung in der App neu beginnen."; return
        }
        completing = true
        scope.launch {
            try {
                val store = PrivateKnusprStore.forApp(applicationContext)
                val oauth = KnusprOAuth(HttpsOfferHttp())
                val pending = withContext(Dispatchers.IO) { requireNotNull(store.read().pending) }
                oauth.authorizationCode(pending, uri.toString())
                val claimed = withContext(Dispatchers.IO) { store.claim(pending.state) }
                val token = oauth.complete(claimed, uri.toString())
                val retained = withContext(Dispatchers.IO) { store.finish(claimed.state, token) }
                message = if (retained) "Anmeldung gespeichert. Lesefunktionen werden getrennt geprüft." else
                    "Verbindung inzwischen getrennt oder neu begonnen. Keine alte Sitzung wiederhergestellt."
                returnToApp()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "Anmeldung nicht abgeschlossen. Vorhandene Lebensmitteldaten bleiben erhalten. Verbindung in der App erneut beginnen." }
            finally { completing = false }
        }
    }
    private fun returnToApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
