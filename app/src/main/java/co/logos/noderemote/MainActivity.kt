package co.logos.noderemote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
        var showSettings by remember { mutableStateOf(false) }
        // Whether the user has asked to be connected. Separate from `link`, which is the
        // OBSERVED state — gating the poll loop on `link` let one transient failure set
        // DISCONNECTED and stop the loop forever, with no way back short of a restart.
        var wantConnected by remember { mutableStateOf(false) }
        var showEnterUri by remember { mutableStateOf(false) }

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

        // ZXing scanner. Returns the raw QR text; the launcher handles the camera
        // permission prompt itself, so there is no separate permission dance.
        val scanner = rememberLauncherForActivityResult(ScanContract()) { res ->
            val raw = res.contents
            // Backing out of the scanner is a normal outcome, not a failure. Reporting it
            // in red teaches people to distrust the red text that does matter.
            if (raw.isNullOrBlank()) { note = "" }
            else if (parse(raw) == null) { note = "That QR isn't a Node Remote pairing code" }
            else {
                uri = raw.trim()
                // The token rides in the URI's t= field; the manual path still accepts one.
                token = Uri.parse(raw.trim()).getQueryParameter("t").orEmpty()
                note = ""
                scope.launch { connect() }
            }
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
            note = when (path) {
                "stop" -> "stopping node…"; "start" -> "starting node…"
                "wipe" -> "wiping database…"; else -> "regenerating config…"
            }
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
            // Show the MESSAGE, not the envelope. Dumping the raw body put
            // {"error":"The node is not running.","ok":false} on screen — the reader has
            // to parse JSON in their head to find the one sentence that matters.
            note = r.fold(
                onSuccess = { body ->
                    runCatching {
                        val o = org.json.JSONObject(body)
                        val err = o.optString("error")
                        when {
                            o.optBoolean("ok", false) -> ""       // success: nothing to say
                            // NOT an error: the request's INTENT was already satisfied.
                            // "The node is not running" in reply to a STOP is the desired
                            // end state, and painting a normal stopped node red teaches
                            // people to ignore the red box that does matter.
                            isAlreadyInDesiredState(path, err) -> ""
                            else -> err.ifEmpty { body }
                        }
                    }.getOrElse { body }
                },
                onFailure = { it.message ?: "request failed" },
            )
            android.util.Log.i(TAG, "control $path -> ${note.ifEmpty { "ok" }}")
            busy = false
            refresh()
        }

        LaunchedEffect(auto) { if (auto && !wantConnected) connect() }
        LaunchedEffect(wantConnected) {
            while (wantConnected) { refresh(); delay(10_000) }
        }

        val connected = wantConnected
        // Only a frame the DESKTOP answered counts. A failed fetch is not knowledge of
        // the node's state, and treating it as such made the app show "Start node" —
        // asserting the node was down — before it had connected at all.
        val hasData = state.answered
        val running = state.reachable && (state.state.equals("Online", true) ||
                                          state.status == "Running")

        // Destructive-ish and remote: stopping a node from a phone by accident is a bad
        // afternoon, so it goes through a confirm rather than firing on tap.
        confirm?.let { action ->
            AlertDialog(
                onDismissRequest = { confirm = null },
                title = {
                    Text(when (action) {
                        "stop" -> "Stop node?"
                        "start" -> "Start node?"
                        "wipe" -> "Wipe database?"
                        else -> "Regenerate config?"
                    })
                },
                text = {
                    Text(when (action) {
                        "stop" -> "The node will stop producing and following blocks until you start it again."
                        "start" -> "The node will start with the config it is already set up with."
                        "wipe" -> "Deletes the chain database and consensus state. The node re-syncs " +
                                  "from genesis, which takes a while. Your wallet keys and config are kept."
                        else -> "Rewrites user_config.yaml from the module's defaults. The current file " +
                                "is backed up first, because it holds your leader key."
                    })
                },
                confirmButton = {
                    TextButton(onClick = {
                        val a = action
                        confirm = null
                        scope.launch { control(if (a == "regenerate") "regenerate" else a) }
                    }) {
                        Text(when (action) {
                                 "stop" -> "Stop node"; "start" -> "Start node"
                                 "wipe" -> "Wipe"; else -> "Regenerate"
                             },
                             color = if (action == "start") LogosColors.green500
                                     else LogosColors.red500)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirm = null }) { Text("Cancel") }
                },
            )
        }

        Scaffold(topBar = {
            TopAppBar(
                navigationIcon = {
                    if (!connected && showEnterUri) {
                        IconButton(onClick = { showEnterUri = false; note = "" }) {
                            ChevronLeftGlyph(LogosColors.white)
                        }
                    }
                    if (showSettings) {
                        IconButton(onClick = { showSettings = false }) {
                            ChevronLeftGlyph(LogosColors.white)
                        }
                    }
                },
                title = {
                    Column(verticalArrangement = Arrangement.Center) {
                        val t = when {
                            showSettings -> "Settings"
                            !connected && showEnterUri -> "Enter URI"
                            !connected -> ""            // welcome carries its own title
                            else -> "Node Remote"
                        }
                        if (t.isNotEmpty()) Text(t, fontWeight = FontWeight.Bold)
                        if (showSettings || !connected) return@Column
                        // Secondary line: how the PHONE is doing. Kept separate from the
                        // node's own state pill so it is always clear which one is unhappy.
                        val (txt, col) = when (link) {
                            Link.CONNECTED -> "Connected via Tor" to LogosColors.green500
                            Link.CONNECTING -> "Connecting via Tor" to LogosColors.orange300
                            Link.NO_INTERNET -> "No internet" to LogosColors.red500
                            Link.DISCONNECTED -> "Disconnected" to LogosColors.gray400
                        }
                        Text(txt, fontSize = 12.sp, color = col,
                             lineHeight = 13.sp,
                             modifier = Modifier.offset(y = (-3).dp))
                    }
                },
                actions = {
                    // Gear only. The Start/Stop control moved into the Status row, next to
                    // the state it acts on, instead of sitting beside the app name.
                    if (connected && !showSettings) {
                        // fillMaxHeight + Center: the title is two lines here, and the
                        // default action alignment left the gear riding high against it.
                        Box(Modifier.fillMaxHeight().padding(end = 6.dp),
                            contentAlignment = Alignment.Center) {
                            IconButton(onClick = { showSettings = true }) {
                                GearGlyph(LogosColors.white)
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

                if (!connected && showEnterUri) {
                    EnterUriScreen(uri, { u, t ->
                        uri = u; token = t
                        scope.launch { connect() }
                    }, note)
                } else if (!connected) {
                    WelcomeScreen(
                        onScan = {
                            scanner.launch(ScanOptions().apply {
                                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                setBeepEnabled(false)
                                setOrientationLocked(false)
                                setCaptureActivity(ScanActivity::class.java)
                            })
                        },
                        onEnterUri = { showEnterUri = true },
                        note = note,
                    )
                } else if (showSettings) {
                    SettingsPage(
                        nodeRunning = running,
                        onWipe = { confirm = "wipe" },
                        onRegenerate = { confirm = "regenerate" },
                        settings = Settings(this@MainActivity),
                        onChanged = {
                            // Restart the watcher so a change takes effect now rather than
                            // at the next app launch.
                            if (uri.isNotEmpty())
                                MonitorService.start(this@MainActivity, uri, token, 15)
                        },
                        onDisconnect = {
                            MonitorService.stop(this@MainActivity)
                            wantConnected = false
                            link = Link.DISCONNECTED
                            showSettings = false
                            uri = ""; token = ""
                            state = NodeState(); rawStatus = ""
                            blocks = emptyList(); proposals = emptyList()
                            note = "Disconnected. Scan a new QR to pair again."
                        },
                    )
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
                    when (tab) {
                        0 -> StatusTab(state, rawStatus, note) {
                            if (!hasData) {
                                CircularProgressIndicator(Modifier.size(18.dp),
                                    strokeWidth = 2.dp, color = LogosColors.orange300)
                            } else {
                                val on = !busy && state.reachable
                                val tint = (if (running) LogosColors.white else LogosColors.green500)
                                    .copy(alpha = if (on) 1f else 0.38f)
                                TextButton(
                                    enabled = on,
                                    onClick = { confirm = if (running) "stop" else "start" },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                ) {
                                    if (busy) {
                                        CircularProgressIndicator(Modifier.size(16.dp),
                                            strokeWidth = 2.dp, color = tint)
                                    } else {
                                        if (running) StopGlyph(tint) else PlayGlyph(tint)
                                        Spacer(Modifier.width(8.dp))
                                        Text(if (running) "Stop node" else "Start node",
                                             color = tint, fontWeight = FontWeight.Medium,
                                             fontSize = 14.sp)
                                    }
                                }
                            }
                        }
                        1 -> BlocksTab(blocks)
                        else -> ProposalsTab(proposals)
                    }
                }
            }
        }
    }

    /** Filled square — the universal stop mark. */
    @Composable
    private fun StopGlyph(tint: Color) {
        Box(Modifier.size(11.dp).background(tint, RoundedCornerShape(2.dp)))
    }

    /** Right-pointing triangle — play. Drawn so the app does not depend on
     *  material-icons-extended (thousands of vectors) for two glyphs. */
    @Composable
    private fun PlayGlyph(tint: Color) {
        Canvas(Modifier.size(12.dp)) {
            val p = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, size.height / 2f)
                lineTo(0f, size.height)
                close()
            }
            drawPath(p, tint)
        }
    }

    /**
     * True when the node already IS what the request asked for. blockchain_module reports
     * these as ok:false with a message, but they are outcomes, not failures — the only
     * genuine errors are things like a config that will not parse.
     */
    private fun isAlreadyInDesiredState(path: String, err: String): Boolean {
        val e = err.lowercase()
        return when (path) {
            "stop" -> "not running" in e || "already stopped" in e
            "start" -> "already running" in e || "already started" in e
            else -> false
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
