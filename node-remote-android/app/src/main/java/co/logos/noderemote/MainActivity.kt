package co.logos.noderemote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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

        // An intent extra wins (a notification tap carries the pairing), otherwise fall back
        // to the STORED pairing. Without the fallback a cold start always showed Welcome,
        // however recently the phone had been paired.
        val saved = Settings(this)
        val preUri = intent?.getStringExtra("uri").orEmpty().ifEmpty { saved.pairUri }
        val preTok = intent?.getStringExtra("token").orEmpty().ifEmpty { saved.pairToken }
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

        // POST_NOTIFICATIONS is a RUNTIME permission from API 33 (we target 36). Declaring
        // it in the manifest is not enough — without the grant every notify() is silently
        // dropped by the system, which is exactly why no alert had ever fired: the app was
        // posting into a void and logging success. Verified on device: granted=false.
        val notifPerm = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()) { granted ->
            // Denial is a legitimate choice, not an error. The watcher still runs and the
            // in-app status is unaffected; only the alerts are silent, so say that once
            // rather than nagging.
            if (!granted) note = "Notifications are off in Android settings — " +
                                 "status still updates while the app is open"
        }
        fun ensureNotificationPermission() {
            if (Build.VERSION.SDK_INT < 33) return
            if (ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // The background watcher's lifecycle IS the "Show notifications" toggle: on means a
        // foreground service polling in the background, off means no service at all — no
        // permanent notification and no battery cost. Previously nothing started it on the
        // pairing path, so a freshly paired phone watched nothing until a setting was
        // toggled by hand.
        fun syncWatcher() {
            if (uri.isEmpty()) { MonitorService.stop(this@MainActivity); return }
            if (Settings(this@MainActivity).showNotifications) {
                ensureNotificationPermission()
                MonitorService.start(this@MainActivity, uri, token, 15)
            } else {
                MonitorService.stop(this@MainActivity)
            }
        }

        suspend fun connect() {
            val p = parse(uri) ?: run { note = "bad pairing URI"; return }
            // Persist BEFORE the network work: every path into the app funnels through here,
            // and a pairing that is only saved on success is lost if the circuit is slow and
            // Android reclaims the activity while we wait.
            Settings(this@MainActivity).let { it.pairUri = uri; it.pairToken = token }
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
            // Only now — a watcher started before the circuit is up would spend its first
            // polls failing and could fire a spurious "can't reach your node".
            syncWatcher()
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
                // A control-response note is transient. Once the node has settled into a
                // healthy or cleanly-stopped state, a lingering note (e.g. a slow-start
                // message) is stale — drop it so it can't sit on screen as a red card while
                // the node is plainly running.
                if (state.reachable || state.isStopped()) note = ""
                TorClient.get("http://$onion/v1/blocks", token).onSuccess { blocks = Block.list(it) }
                TorClient.get("http://$onion/v1/proposals", token).onSuccess { proposals = Proposal.list(it) }
            }
            // Distinguish "no internet at all" from "Tor is up but the node is unreachable".
            // Never fall back to DISCONNECTED here: while the user wants to be connected,
            // "not reachable yet" is CONNECTING (Tor circuits and descriptor fetches take
            // time). DISCONNECTED is a user-intent state, not an observation.
            link = when {
                !hasInternet() -> Link.NO_INTERNET
                // ANSWERED, not reachable. `reachable` means the DESKTOP reached the NODE,
                // which says nothing about our link to the desktop. Keying the transport
                // line off it made the app report "Connecting via Tor" while Tor was fully
                // up and it was the node that was down — blaming the transport for the
                // node's problem, which is exactly the wrong place to send someone.
                state.answered -> Link.CONNECTED
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
                        val code = o.optString("code")
                        when {
                            // Regenerate is the one success worth a word: the module returns
                            // the REAL backup path it just wrote, and that is precisely what
                            // you need if the new config turns out wrong. Telling someone a
                            // backup exists without naming it is not much better than silence.
                            o.optBoolean("ok", false) && path == "regenerate" ->
                                o.optString("backup").takeIf { it.isNotEmpty() }
                                    ?.let { "Config regenerated. Previous file saved as $it" }
                                    ?: ""
                            // Trust STRUCTURE, not phrasing. The module returns ok:true for a
                            // real stop/start AND for the idempotent already_stopped/
                            // already_running cases, so a satisfied intent says nothing.
                            o.optBoolean("ok", false) -> ""
                            // Structured idempotent codes, in case a build returns ok:false
                            // with a code (belt and suspenders).
                            code == "already_stopped" || code == "already_running" -> ""
                            // Fallback for OLDER desktop modules that still reply ok:false
                            // with only an English message and no code.
                            isAlreadyInDesiredState(path, err) -> ""
                            // A genuine failure. Show the module's message — but NEVER the
                            // raw envelope: an empty error used to dump the whole JSON body on
                            // screen in red (seen on a slow start), which reads as a crash for
                            // what is often just the node still coming up.
                            else -> err.ifEmpty { "Couldn't ${path} the node — check the desktop" }
                        }
                    }.getOrElse { "Couldn't ${path} the node — check the desktop" }
                },
                onFailure = { it.message ?: "request failed" },
            )
            android.util.Log.i(TAG, "control $path -> ${note.ifEmpty { "ok" }}")
            busy = false
            refresh()
        }

        // Reconnect on launch whenever we HAVE a pairing — not only when an intent said
        // auto=true. A stored pairing means the user already paired this phone; making them
        // press something to get back to a screen they never chose to leave is friction with
        // no purpose. `auto` stays as an explicit override for the notification-tap path.
        LaunchedEffect(auto, uri) {
            if (!wantConnected && (auto || uri.isNotEmpty())) connect()
        }
        LaunchedEffect(wantConnected) {
            while (wantConnected) { refresh(); delay(10_000) }
        }

        // Drives staleness. The poll loop only recomposes when a reading SUCCEEDS, so with
        // the desktop gone nothing would ever re-evaluate how old the last frame is and the
        // UI would sit on it indefinitely. This ticks regardless of the network.
        var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(wantConnected) {
            while (wantConnected) { nowMs = System.currentTimeMillis(); delay(5_000) }
        }

        val connected = wantConnected
        // Only a frame the DESKTOP answered counts. A failed fetch is not knowledge of
        // the node's state, and treating it as such made the app show "Start node" —
        // asserting the node was down — before it had connected at all.
        val hasData = state.answered
        // reachable IS the answer: node_probe sets it only when the node's own REST API
        // replied, and a node that answers HTTP is running by definition.
        //
        // This used to enumerate the good states — "Online" or status=="Running" — and call
        // everything else stopped. But node_probe maps status to "Running" ONLY when state
        // is "Online", so a bootstrapping node reported state=status="Bootstrapping" and the
        // app offered "Start node" for a node that was already up and syncing. Enumerating
        // the healthy states means every state the node adds later reads as "stopped".
        //
        // It also mis-gated the settings page: Wipe/Regenerate are enabled on !nodeRunning,
        // so both were offered against a LIVE bootstrapping node. The desktop refuses that
        // server-side, but the UI should not be offering it.
        // reachable OR coming-up (starting/recovering). A node replaying its database has
        // not brought its API up yet, so `reachable` is false — but the process is alive and
        // stoppable, and a node stuck mid-replay is exactly when you want to be able to stop
        // it. 1-click disables its control here; we deliberately do not, because "no button
        // at all" leaves you with nothing to do but wait. A Stopped node is NOT running, so
        // it correctly offers Start.
        val running = state.reachable || state.isStarting() || state.isRecovering()

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
                        else -> "Your node's settings file (user_config.yaml) is replaced with a " +
                                "fresh one built from the module's defaults.\n\n" +
                                "Before that, the current file is copied alongside it as " +
                                "user_config.yaml.bak-<timestamp>. If the new one is wrong, " +
                                "rename the backup back over it on the desktop.\n\n" +
                                "Why the copy matters: that file holds consensus.wallet.funding_pk, " +
                                "your leader identity. Anything you customised — peers, ports, " +
                                "addresses — also reverts to defaults."
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
                            // A stale reading cannot claim a live link. `link` is only
                            // assigned inside refresh(), so when polling stops it keeps its
                            // last value — the app read "Connected via Tor" while the
                            // desktop's tor had died with Basecamp (it is a child of the
                            // module host; dieWithParent takes it down). Verified down on
                            // the rig while the phone still claimed Connected.
                            Link.CONNECTED ->
                                if (state.isStale(nowMs)) "Connecting via Tor" to LogosColors.orange300
                                else "Connected via Tor" to LogosColors.green500
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
                            // Re-evaluate rather than unconditionally start: turning the
                            // master switch OFF has to STOP the service, and the old code
                            // restarted it on every change including that one.
                            syncWatcher()
                        },
                        onDisconnect = {
                            // Forget it locally too, or the next launch would reconnect to a
                            // node that has just revoked us.
                            Settings(this@MainActivity).clearPairing()
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
                        0 -> StatusTab(state, rawStatus, note, nowMs) {
                            if (!hasData) {
                                CircularProgressIndicator(Modifier.size(18.dp),
                                    strokeWidth = 2.dp, color = LogosColors.orange300)
                            } else {
                                // A node replaying stored blocks is ALIVE — its API just is
                                // not answering yet. The button was already disabled here
                                // (reachable is false), but it still READ "Start node",
                                // which tells you the node is down when it is working. Show
                                // the truth instead: it is starting.
                                // Offer Stop while recovering — see `running` above.
                                val on = !busy
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
                        // Same rule as the Status fields: when we cannot reach the node,
                        // a cached list is not current data. Blocks/proposals are only
                        // rewritten on a SUCCESSFUL fetch, so without this they sit there
                        // looking live while the desktop is gone.
                        1 -> BlocksTab(if (state.answered && !state.isStale(nowMs)) blocks
                                       else emptyList())
                        else -> ProposalsTab(if (state.answered && !state.isStale(nowMs)) proposals
                                             else emptyList())
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
