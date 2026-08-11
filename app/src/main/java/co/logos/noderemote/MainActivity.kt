package co.logos.noderemote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Node Remote — title, connection line, a compact Start/Stop control, and three tabs
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

    private fun hasInternet(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun App(preUri: String, preTok: String, auto: Boolean) {
        var uri by remember { mutableStateOf(preUri) }
        var token by remember { mutableStateOf(preTok) }
        var link by remember { mutableStateOf(Link.DISCONNECTED) }
        var tab by remember { mutableIntStateOf(0) }

        var state by remember { mutableStateOf(NodeState()) }
        var rawStatus by remember { mutableStateOf("") }
        var blocks by remember { mutableStateOf<List<Block>>(emptyList()) }
        var proposals by remember { mutableStateOf<List<Proposal>>(emptyList()) }
        var busy by remember { mutableStateOf(false) }
        var note by remember { mutableStateOf("") }
        var confirm by remember { mutableStateOf<String?>(null) }   // "start" | "stop" | null
        // Whether the user has asked to be connected. Separate from `link`, which is the
        // OBSERVED state — gating the poll loop on `link` let one transient failure set
        // DISCONNECTED and stop the loop forever, with no way back short of a restart.
        var wantConnected by remember { mutableStateOf(false) }

        val scope = rememberCoroutineScope()
        val onion = remember(uri) { parse(uri)?.first.orEmpty() }

        suspend fun connect() {
            val p = parse(uri) ?: run { note = "bad pairing URI"; return }
            wantConnected = true
            if (!hasInternet()) { link = Link.NO_INTERNET; return }
            link = Link.CONNECTING
            TorClient.start(this@MainActivity) { android.util.Log.i(TAG, it) }
            var w = 0
            while (TorClient.socksPort == 0 && w < 180) { delay(1000); w++ }
            if (TorClient.socksPort == 0) {
                link = if (hasInternet()) Link.CONNECTING else Link.NO_INTERNET
                note = "Tor is taking longer than usual to start"
                return
            }
            TorClient.addClientAuth(p.first, p.second)
                .onFailure { note = "client auth failed: ${it.message}" }
            note = ""
            link = Link.CONNECTED
        }

        suspend fun refresh() {
            if (onion.isEmpty()) return
            withContext(Dispatchers.IO) {
                TorClient.get("http://$onion/v1/status", token)
                    .onSuccess { rawStatus = it; state = NodeState.parse(it, System.currentTimeMillis()) }
                    .onFailure { state = NodeState.unreachable(System.currentTimeMillis(), it.message.orEmpty()) }
                TorClient.get("http://$onion/v1/blocks", token).onSuccess { blocks = Block.list(it) }
                TorClient.get("http://$onion/v1/proposals", token).onSuccess { proposals = Proposal.list(it) }
            }
            // Distinguish "no internet at all" from "Tor is up but the node is unreachable".
            // Never fall back to DISCONNECTED here: while the user wants to be connected,
            // "not reachable yet" is CONNECTING (Tor circuits and descriptor fetches take
            // time). DISCONNECTED is a user-intent state, not an observation.
            link = when {
                !hasInternet() -> Link.NO_INTERNET
                state.reachable -> Link.CONNECTED
                else -> Link.CONNECTING
            }
            android.util.Log.i(TAG, "refresh state=${state.state} height=${state.height} " +
                                    "blocks=${blocks.size} proposals=${proposals.size} link=$link")
        }

        suspend fun control(path: String) {
            busy = true
            note = if (path == "stop") "stopping node…" else "starting node…"
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

        LaunchedEffect(auto) { if (auto && !wantConnected) connect() }
        LaunchedEffect(wantConnected) {
            while (wantConnected) { refresh(); delay(10_000) }
        }

        val connected = wantConnected
        val running = state.reachable && state.state.equals("Online", true) ||
                      (state.reachable && state.status == "Running")

        // Destructive-ish and remote: stopping a node from a phone by accident is a bad
        // afternoon, so it goes through a confirm rather than firing on tap.
        confirm?.let { action ->
            AlertDialog(
                onDismissRequest = { confirm = null },
                title = { Text(if (action == "stop") "Stop node?" else "Start node?") },
                text = {
                    Text(if (action == "stop")
                        "The node will stop producing and following blocks until you start it again."
                    else "The node will start with the config it is already set up with.")
                },
                confirmButton = {
                    TextButton(onClick = { confirm = null; scope.launch { control(action) } }) {
                        Text(if (action == "stop") "Stop node" else "Start node",
                             color = if (action == "stop") LogosColors.red500 else LogosColors.green500)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirm = null }) { Text("Cancel") }
                },
            )
        }

        Scaffold(topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Node Remote", fontWeight = FontWeight.Bold)
                        // Secondary line: how the PHONE is doing. Kept separate from the
                        // node's own state pill so it is always clear which one is unhappy.
                        val (txt, col) = when (link) {
                            Link.CONNECTED -> "Connected via Tor" to LogosColors.green500
                            Link.CONNECTING -> "Connecting via Tor" to LogosColors.orange300
                            Link.NO_INTERNET -> "No internet" to LogosColors.red500
                            Link.DISCONNECTED -> "Disconnected" to LogosColors.gray400
                        }
                        Text(txt, fontSize = 12.sp, color = col)
                    }
                },
                actions = {
                    if (connected) {
                        // Compact chip, not a large CTA — this is a rare action, not the
                        // primary one. Label says what it does to WHAT ("Stop node").
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (running) LogosColors.white else LogosColors.green500,
                            modifier = Modifier.padding(end = 12.dp),
                        ) {
                            TextButton(
                                enabled = !busy && state.reachable,
                                onClick = { confirm = if (running) "stop" else "start" },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    if (busy) "…" else if (running) "Stop node" else "▶ Start node",
                                    color = LogosColors.gray900,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = LogosColors.gray900,
                    titleContentColor = LogosColors.white,
                ),
            )
        }) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {

                if (!connected) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Pair with the QR from Basecamp. Nobody without that key can " +
                             "reach — or even see — your node.",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(uri, { uri = it }, label = { Text("lgnode:// pairing URI") },
                                          modifier = Modifier.fillMaxWidth(), maxLines = 4)
                        OutlinedTextField(token, { token = it }, label = { Text("device token") },
                                          modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Button(onClick = { scope.launch { connect() } }) { Text("Connect") }
                        if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    // Active tab in primary orange; the rest plain white.
                    TabRow(
                        selectedTabIndex = tab,
                        containerColor = LogosColors.gray900,
                        contentColor = LogosColors.orange300,
                    ) {
                        listOf("Status", "Blocks", "Proposals").forEachIndexed { i, t ->
                            Tab(selected = tab == i, onClick = { tab = i },
                                selectedContentColor = LogosColors.orange300,
                                unselectedContentColor = LogosColors.white,
                                text = { Text(t, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal) })
                        }
                    }
                    if (note.isNotEmpty()) {
                        Text(note, Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
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
