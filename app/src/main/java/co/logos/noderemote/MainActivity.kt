package co.logos.noderemote

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Node Remote — title bar with a Start/Stop control on the right, and three tabs
 * (Status · Blocks · Proposals) whose fields mirror logos_node_1click.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val preUri = intent?.getStringExtra("uri").orEmpty()
        val preTok = intent?.getStringExtra("token").orEmpty()
        val auto = intent?.getBooleanExtra("auto", false) ?: false

        if (intent?.getBooleanExtra("monitor", false) == true && preUri.isNotEmpty())
            MonitorService.start(this, preUri, preTok, intent?.getIntExtra("periodSec", 15) ?: 15)
        if (intent?.getBooleanExtra("stopMonitor", false) == true) MonitorService.stop(this)
        intent?.getStringExtra("enableEvent")?.let { k ->
            Event.entries.find { it.key == k }?.let { Settings(this).setEnabled(it, true) }
        }
        intent?.getStringExtra("disableEvent")?.let { k ->
            Event.entries.find { it.key == k }?.let { Settings(this).setEnabled(it, false) }
        }

        setContent { NodeRemoteTheme { App(preUri, preTok, auto) } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun App(preUri: String, preTok: String, auto: Boolean) {
        var uri by remember { mutableStateOf(preUri) }
        var token by remember { mutableStateOf(preTok) }
        var connected by remember { mutableStateOf(false) }
        var tab by remember { mutableIntStateOf(0) }

        var state by remember { mutableStateOf(NodeState()) }
        var rawStatus by remember { mutableStateOf("") }
        var blocks by remember { mutableStateOf<List<Block>>(emptyList()) }
        var proposals by remember { mutableStateOf<List<Proposal>>(emptyList()) }
        var busy by remember { mutableStateOf(false) }
        var note by remember { mutableStateOf("") }

        val scope = rememberCoroutineScope()
        val onion = remember(uri) { parse(uri)?.first.orEmpty() }

        suspend fun connect() {
            val p = parse(uri) ?: run { note = "bad pairing URI"; return }
            note = "starting Tor…"
            TorClient.start(this@MainActivity) { android.util.Log.i(TAG, it) }
            var w = 0
            while (TorClient.socksPort == 0 && w < 180) { delay(1000); w++ }
            if (TorClient.socksPort == 0) { note = "Tor did not start"; return }
            TorClient.addClientAuth(p.first, p.second)
                .onFailure { note = "client auth failed: ${it.message}" }
            note = ""
            connected = true
        }

        // One pass drives all three tabs. Blocks/proposals are cheap next to a Tor
        // round-trip, so fetching them together beats three separate circuits.
        suspend fun refresh() {
            if (onion.isEmpty()) return
            withContext(Dispatchers.IO) {
                TorClient.get("http://$onion/v1/status", token)
                    .onSuccess { rawStatus = it; state = NodeState.parse(it, System.currentTimeMillis()) }
                    .onFailure { state = NodeState.unreachable(System.currentTimeMillis(), it.message.orEmpty()) }
                TorClient.get("http://$onion/v1/blocks", token).onSuccess { blocks = Block.list(it) }
                TorClient.get("http://$onion/v1/proposals", token).onSuccess { proposals = Proposal.list(it) }
            }
            android.util.Log.i(TAG, "refresh status=${state.status} height=${state.height} " +
                                    "blocks=${blocks.size} proposals=${proposals.size}")
        }

        suspend fun control(path: String) {
            busy = true
            note = if (path == "stop") "stopping…" else "starting…"
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val req = Request.Builder()
                        .url("http://$onion/v1/$path")
                        .post("".toRequestBody("application/json".toMediaType()))
                        .header("Authorization", "Bearer $token")
                        .build()
                    TorClient.http().newCall(req).execute().use { it.body?.string().orEmpty() }
                }
            }
            note = r.getOrElse { "failed: ${it.message}" }.take(160)
            android.util.Log.i(TAG, "control $path -> $note")
            busy = false
            refresh()
        }

        LaunchedEffect(auto) { if (auto && !connected) connect() }
        LaunchedEffect(connected) { while (connected) { refresh(); delay(10_000) } }

        Scaffold(topBar = {
            TopAppBar(
                title = { Text("Node Remote", fontWeight = FontWeight.Bold) },
                actions = {
                    // Start/Stop sits to the RIGHT of the title. One button, not two: the
                    // label follows live state, so a "Stop" is never offered for a node
                    // that is already stopped.
                    val running = state.reachable && state.status == "Running"
                    Button(
                        enabled = connected && !busy && state.reachable,
                        onClick = { scope.launch { control(if (running) "stop" else "start") } },
                        modifier = Modifier.padding(end = 12.dp),
                        colors = if (running)
                            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        else ButtonDefaults.buttonColors(),
                    ) { Text(if (busy) "…" else if (running) "Stop" else "Start") }
                }
            )
        }) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {

                if (!connected) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("● Private · Tor onion — no third party can see this link exists",
                             style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(uri, { uri = it }, label = { Text("lgnode:// pairing URI") },
                                          modifier = Modifier.fillMaxWidth(), maxLines = 4)
                        OutlinedTextField(token, { token = it }, label = { Text("device token") },
                                          modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Button(onClick = { scope.launch { connect() } }) { Text("Connect") }
                        if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    TabRow(selectedTabIndex = tab) {
                        listOf("Status", "Blocks", "Proposals").forEachIndexed { i, t ->
                            Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                        }
                    }
                    if (note.isNotEmpty()) {
                        Text(note, Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                             style = MaterialTheme.typography.bodySmall)
                    }
                    when (tab) {
                        0 -> StatusTab(state, rawStatus)
                        1 -> BlocksTab(blocks)
                        else -> ProposalsTab(proposals)
                    }
                }
            }
        }
    }

    private fun parse(s: String): Pair<String, String>? = runCatching {
        val u = Uri.parse(s.trim())
        if (u.scheme != "lgnode") return null
        val onion = u.getQueryParameter("onion").orEmpty()
        val ca = u.getQueryParameter("ca").orEmpty()
        if (onion.isEmpty() || ca.isEmpty()) null else onion to ca
    }.getOrNull()

    private companion object { const val TAG = "NodeRemoteApp" }
}
