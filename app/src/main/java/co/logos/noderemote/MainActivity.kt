package co.logos.noderemote

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Node Remote — spike build.
 *
 * Paste the `lgnode://pair?…` URI that node_remote's beginPairing() produced (the QR
 * scanner comes later), and this will: start embedded Tor, register the client-auth key,
 * and pull live node status over the onion.
 *
 * The point of this build is to prove the hardest link in the chain on real hardware:
 * that a client-authorized v3 onion is reachable from kmp-tor. Everything else is UI.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Intent extras let adb drive the whole flow with no screen taps:
        //   adb shell am start -n co.logos.noderemote/.MainActivity \
        //     --es uri '<lgnode://…>' --es token '<tok>' --ez auto true
        val preUri = intent?.getStringExtra("uri").orEmpty()
        val preTok = intent?.getStringExtra("token").orEmpty()
        val auto   = intent?.getBooleanExtra("auto", false) ?: false
        setContent { MaterialTheme { Screen(preUri, preTok, auto) } }
    }

    @Composable
    private fun Screen(preUri: String = "", preTok: String = "", auto: Boolean = false) {
        var uri by remember { mutableStateOf(preUri) }
        var token by remember { mutableStateOf(preTok) }
        val log = remember { mutableStateListOf<String>() }
        var status by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }

        fun logLine(s: String) { log.add(s); android.util.Log.i("NodeRemoteApp", s) }

        // Auto-run for adb-driven testing: start tor, then pair+fetch once it is up.
        LaunchedEffect(auto) {
            if (!auto) return@LaunchedEffect
            logLineAuto("auto: starting tor")
            TorClient.start(this@MainActivity) { logLineAuto(it) }
            var waited = 0
            while (TorClient.socksPort == 0 && waited < 120) { kotlinx.coroutines.delay(1000); waited++ }
            if (TorClient.socksPort == 0) { logLineAuto("auto: no SOCKS port"); return@LaunchedEffect }
            val p = parse(uri)
            if (p == null) { logLineAuto("auto: bad URI"); return@LaunchedEffect }
            val (onion, ca) = p
            TorClient.addClientAuth(onion, ca)
                .onSuccess { logLineAuto("auto: client auth registered") }
                .onFailure { logLineAuto("auto: client auth FAILED: $it") }
            val r = withContext(Dispatchers.IO) { TorClient.get("http://$onion/v1/status", token) }
            r.onSuccess { logLineAuto("auto: STATUS_OK ${it.replace("\n"," ")}") }
             .onFailure { logLineAuto("auto: STATUS_FAIL $it") }
        }

        Column(
            Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Node Remote", style = MaterialTheme.typography.headlineSmall)
            Text("● Private · Tor onion — no third party can see this link exists",
                 style = MaterialTheme.typography.bodySmall)

            OutlinedTextField(uri, { uri = it }, label = { Text("lgnode:// pairing URI") },
                              modifier = Modifier.fillMaxWidth(), maxLines = 4)
            OutlinedTextField(token, { token = it }, label = { Text("device token") },
                              modifier = Modifier.fillMaxWidth(), singleLine = true)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    logLine("starting tor…")
                    TorClient.start(this@MainActivity) { logLine(it) }
                }) { Text("Start Tor") }

                Button(enabled = !busy, onClick = {
                    busy = true
                    lifecycleScope.launch {
                        val p = parse(uri)
                        if (p == null) { logLine("bad URI"); busy = false; return@launch }
                        val (onion, ca) = p
                        logLine("pairing with $onion")
                        // Tor must be up before the control command can be issued.
                        var waited = 0
                        while (TorClient.socksPort == 0 && waited < 90) {
                            withContext(Dispatchers.IO) { Thread.sleep(1000) }; waited++
                        }
                        if (TorClient.socksPort == 0) {
                            logLine("tor never opened a SOCKS port"); busy = false; return@launch
                        }
                        TorClient.addClientAuth(onion, ca)
                            .onSuccess { logLine("client auth registered ✓") }
                            .onFailure { logLine("client auth FAILED: $it") }

                        val url = "http://$onion/v1/status"
                        logLine("GET $url")
                        val r = withContext(Dispatchers.IO) { TorClient.get(url, token) }
                        r.onSuccess { body ->
                            status = pretty(body)
                            logLine("status ✓ (${body.length} bytes)")
                        }.onFailure { logLine("status FAILED: $it") }
                        busy = false
                    }
                }) { Text(if (busy) "Working…" else "Pair + Fetch") }
            }

            if (status.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Text(status, Modifier.padding(12.dp),
                             fontFamily = FontFamily.Monospace,
                             style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            HorizontalDivider()
            Text("log", style = MaterialTheme.typography.labelMedium)
            SelectionContainer {
                Text(log.joinToString("\n"), fontFamily = FontFamily.Monospace,
                     style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    private fun logLineAuto(s: String) { android.util.Log.i("NodeRemoteApp", s) }

    /** lgnode://pair?v=1&onion=<addr>&ca=<base32>&t=<tok>&exp=<unix> */
    private fun parse(s: String): Pair<String, String>? = runCatching {
        val u = Uri.parse(s.trim())
        if (u.scheme != "lgnode") return null
        val onion = u.getQueryParameter("onion").orEmpty()
        val ca = u.getQueryParameter("ca").orEmpty()
        if (onion.isEmpty() || ca.isEmpty()) return null
        onion to ca
    }.getOrNull()

    private fun pretty(s: String): String = runCatching {
        val o = JSONObject(s)
        buildString {
            for (k in listOf("status", "state", "phase", "height", "slot",
                             "peers", "connections", "reachable", "apiBase")) {
                if (o.has(k)) append(k).append(": ").append(o.get(k)).append('\n')
            }
        }.ifBlank { s }
    }.getOrDefault(s)
}
