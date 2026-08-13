package co.logos.noderemote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * Screens for Node Remote. Field names and vocabulary mirror logos_node_1click.
 */

/** How the PHONE is doing, as opposed to how the node is doing. */
enum class Link { DISCONNECTED, CONNECTING, CONNECTED, NO_INTERNET }

// ── Blocks: the 14 fields of BlockModel ────────────────────────────────────────────────
data class Block(
    val timestamp: String, val slot: String, val version: String,
    val parentBlock: String, val blockRoot: String, val leaderKey: String,
    val entropy: String, val proof: String, val voucherCm: String,
    val signature: String, val txCount: Int, val transactions: List<String>,
    val rawJson: String, val parsed: Boolean,
) {
    companion object {
        fun list(json: String): List<Block> = runCatching {
            val a = JSONArray(json)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val txs = o.optJSONArray("transactions")
                Block(
                    o.optString("timestamp"), o.optString("slot"), o.optString("version"),
                    o.optString("parentBlock"), o.optString("blockRoot"), o.optString("leaderKey"),
                    o.optString("entropy"), o.optString("proof"), o.optString("voucherCm"),
                    o.optString("signature"), o.optInt("txCount"),
                    (0 until (txs?.length() ?: 0)).map { txs!!.getString(it) },
                    o.optString("rawJson"), o.optBoolean("parsed", false),
                )
            }
        }.getOrDefault(emptyList())
    }
}

// ── Proposals: {id, txs, removed, time} ────────────────────────────────────────────────
data class Proposal(val id: String, val txs: Int, val removed: Int, val time: String) {
    companion object {
        fun list(json: String): List<Proposal> = runCatching {
            val a = JSONArray(json)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Proposal(o.optString("id"), o.optInt("txs"), o.optInt("removed"), o.optString("time"))
            }
        }.getOrDefault(emptyList())
    }
}

@Composable
fun StatusTab(s: NodeState, raw: String, note: String = "", nowMs: Long = 0L,
              control: @Composable () -> Unit = {}) {
    // A STALE reading must not be rendered as current. raw is only rewritten on a SUCCESSFUL
    // poll, so with the desktop gone the tiles below keep asserting the last Height/Peers/
    // Blend/Balance indefinitely — the app read "Online" with live-looking numbers while
    // Basecamp had been down for minutes.
    // NOT CONNECTED => EVERY field reads "—". No grace period, no last-known values left on
    // screen pretending to be current.
    //
    // `live` requires BOTH: the desktop answered THIS poll (s.answered) and the reading is
    // not older than the staleness window (polling may have stopped altogether, in which
    // case the last frame still says answered=true forever).
    //
    // Everything below the pill — Blend, Balance, tip, lib, peer id — is parsed from `raw`,
    // which is only rewritten on a SUCCESSFUL poll. Dropping the JSON is what makes those
    // fields fall back to their "—" defaults; without it they keep asserting the last good
    // values, which is how the app showed "Blend Edge" for a node that was not running.
    // Height/Slot/Peers come from NodeState and are already -1 (=> "—") when unreachable.
    val stale = nowMs > 0 && s.isStale(nowMs)
    val live = s.answered && !stale
    val o = remember(raw, live) { if (live) runCatching { JSONObject(raw) }.getOrNull() else null }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
           verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // An error REPLACES the state block rather than sitting under it: "Online" beside
        // a failure is contradictory, and the reader has to work out which to believe.
        // Shown in full — the longest real one measured is 131 chars (a YAML syntax error
        // carrying line/column), and truncating that discards the part that locates it.
        // Only the honest Error state paints the red card. The module populates `error`
        // exclusively when status=="Error" (node meant to be up, down with a cause), so a
        // stopped/recovering node never shows red here.
        val nodeErr = s.error.takeIf { it.isNotEmpty() && s.status == "Error" }
        when {
            // A control note ("stopping node…", "starting node…") is a TRANSIENT PROGRESS
            // message, not a failure. It was routed through ErrorCard, so every normal
            // start/stop flashed a red error panel — with a copy button, as if there were
            // something to report. Busy gets its own card: accent orange, matching the
            // Bootstrapping/Recovering pills, which are the other "working, not broken"
            // states.
            note.isNotEmpty() -> BusyCard(note, control)
            nodeErr != null -> ErrorCard(nodeErr, control)
            else -> NodeStateBlock(s, control, !live)
        }

        // Say HOW old, rather than silently blanking the screen. "Last reading 6 min ago"
        // is information; a bare "Waiting for data…" with no history is not.
        if (!live && s.atMillis > 0 && nowMs > 0) {
            val mins = ((nowMs - s.atMillis) / 60_000).toInt()
            Text(if (mins >= 1) "Last reading $mins min ago — can't reach your node now"
                 else "Last reading moments ago — can't reach your node now",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // A working-but-busy node explains itself in plain text under the state, not in the
        // red card. Red is for things you must act on.
        s.notice.takeIf { it.isNotEmpty() && s.answered }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        run {
            // Formatted from balanceRaw (BASE UNITS) with 1-click's own algorithm, so the
            // two surfaces print the same string for the same wallet — "200M LGO", not
            // "200000000.0000". White like every other value: orange is the accent colour,
            // and using it here made the balance read as a highlight or a warning rather
            // than one fact among several.
            val raw = o?.optString("balanceRaw")?.ifEmpty { null }
            val balance = raw?.let { fmtLgo(it) }
                ?: o?.optString("balance")?.ifEmpty { null }
            InfoRow("Balance", balance ?: "—",
                    if (balance != null) LogosColors.white else LogosColors.gray400)

            // Only when there is NOTHING to show. The module keeps the last good figure and
            // reports why the latest refresh failed, so both were rendered at once —
            // "100M LGO" directly above "Balance unavailable: Unknown wallet address",
            // which contradict each other. A stale figure plus a reason is a *stale* state,
            // not an unavailable one; if we have a number, show the number.
            if (balance == null) {
                o?.optString("balanceError")?.takeIf { it.isNotEmpty() && s.reachable }?.let {
                    Text("Balance unavailable: $it",
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Tile("Height", if (s.height >= 0) "${s.height}" else "—", Modifier.weight(1f))
            Tile("Slot", if (s.slot >= 0) "${s.slot}" else "—", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Tile("Peers", if (s.peers >= 0) "${s.peers}" else "—", Modifier.weight(1f))
            // Blend is the mix-network role: Edge (uses it) vs Core (relays for others).
            val blend = o?.optString("blendStatus")?.ifEmpty { null } ?: "—"
            val bp = o?.optInt("blendPeers", -1) ?: -1
            // Matches the module exactly (BlockchainView.qml getBlendColor): Edge OR Core
            // are both "active" and paint info-blue; everything else is textSecondary grey.
            Tile("Blend", if (blend == "Core" && bp > 0) "Core · $bp" else blend,
                 Modifier.weight(1f),
                 accent = if (blend == "Edge" || blend == "Core") LogosColors.blue400
                          else LogosColors.gray400)
        }

        // Identifiers get their own block with copy affordances — they are long hex that
        // nobody retypes, so the only useful interaction is copying.
        if (o != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    CopyRow("tip", o.optString("tip"))
                    CopyRow("lib", o.optString("lib"))
                    CopyRow("peer id", o.optString("peerId"))
                }
            }
        }
    }
}

/**
 * The NODE's state as a full-width block: "Status" on the left, the value on the right,
 * tinted to match. Vocabulary is logos_node_1click's exact set — Bootstrapping / Online /
 * Not Started (NodeDashboardView.qml:63).
 *
 * Connection state is deliberately NOT here; it lives under the title. Conflating "my node
 * is up" with "my phone can reach it" is how you end up staring at a red block wondering
 * which of the two broke.
 *
 * Red is reserved for an ACTUAL error reported by the node, not for "we could not reach
 * it" — those are different problems and colouring them the same trains you to ignore both.
 */
/** In-flight action: orange like the other busy states, no copy button — there is nothing
 *  to report, and offering to copy "starting node…" implies something went wrong. */
@Composable
private fun BusyCard(text: String, control: @Composable () -> Unit = {}) {
    // Structurally IDENTICAL to NodeStateBlock — same alpha, same paddings, same single Row,
    // same titleMedium — so swapping between them cannot change the block's height and the
    // page does not jump every time you press start or stop.
    Surface(color = LogosColors.orange300.copy(alpha = 0.12f),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // The ONE spinner. The control renders none while busy — two spinners for a
                // single action read as two things happening.
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp,
                                          color = LogosColors.orange300)
                Spacer(Modifier.width(10.dp))
                Text(text.replaceFirstChar { it.uppercase() },
                     fontWeight = FontWeight.Bold,
                     color = LogosColors.orange300,
                     style = MaterialTheme.typography.titleMedium,
                     modifier = Modifier.weight(1f))
                control()
            }
        }
    }
}

/**
 * Full-text, copyable error. No maxLines: a truncated error is worse than none, because
 * the tail is where the line/column and the actual cause live.
 */
@Composable
private fun ErrorCard(text: String, control: @Composable () -> Unit = {}) {
    val clip = LocalClipboardManager.current
    var copied by remember(text) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1400); copied = false } }
    Surface(color = LogosColors.red500.copy(alpha = 0.10f),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()) {
      Column {
        Row(Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp, end = 14.dp),
            verticalAlignment = Alignment.Top) {
            IconButton(onClick = { clip.setText(AnnotatedString(text)); copied = true }) {
                CopyGlyph(if (copied) LogosColors.green500 else LogosColors.gray400)
            }
            Text(text,
                 color = LogosColors.red500,
                 style = MaterialTheme.typography.bodySmall,
                 modifier = Modifier.weight(1f).padding(top = 12.dp))
        }
        // The control still has to be reachable while an error is displayed — an error is
        // usually the moment you most want to start or stop the node.
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, bottom = 8.dp)) { control() }
      }
    }
}

/**
 * LGO display, ported from logos_node_1click's NodeDashboardView.qml (_fmtBalance +
 * _lgoTrim) so the phone and the desktop tile never disagree about the same wallet.
 *
 * wallet_get_balance returns BASE UNITS; 1 LGO = 10^4 base units. 2000000000000 base
 * = 200,000,000 LGO -> "200M LGO". Abbreviated because the full number does not fit a
 * stat row, and suffixed with the ticker because a bare number is not a balance.
 *
 * Checked against the QML original on 11 inputs; 10 are byte-identical. The one deliberate
 * divergence is the EMPTY string: JavaScript's Number("") is 0, so 1-click prints "0 LGO"
 * for a missing balance. That is a JS coercion wart, and claiming a wallet holds zero when
 * we simply have no figure is exactly the kind of confident-and-wrong this app keeps
 * getting bitten by. We print "— LGO". The call site filters empty before it gets here, so
 * the two can never actually disagree on screen.
 */
private const val BASE_UNITS_PER_LGO = 10_000.0

private fun lgoTrim(x: Double): String {
    val r = Math.round(x * 100) / 100.0
    // Match QML's Number.toString(): drop a trailing ".0" rather than printing "200.0M".
    return if (r == Math.floor(r) && !r.isInfinite()) r.toLong().toString() else r.toString()
}

fun fmtLgo(raw: String): String {
    val n = raw.trim().trim('"').toDoubleOrNull() ?: return "— LGO"
    val v = n / BASE_UNITS_PER_LGO
    val a = Math.abs(v)
    val s = when {
        a >= 1e12 -> lgoTrim(v / 1e12) + "T"
        a >= 1e9  -> lgoTrim(v / 1e9) + "B"
        a >= 1e6  -> lgoTrim(v / 1e6) + "M"
        a >= 1e3  -> lgoTrim(v / 1e3) + "K"
        else      -> lgoTrim(v)
    }
    return "$s LGO"
}

@Composable
private fun NodeStateBlock(s: NodeState, control: @Composable () -> Unit = {},
                           stale: Boolean = false) {
    val (label, color) = when {
        // A 4xx is an ANSWER — the desktop heard us and refused. Showing the same spinner
        // as "no reply yet" hides a fixable problem behind an infinite wait.
        s.isRejected() -> "Not authorised — pair again" to LogosColors.red500
        // Stale outranks every healthy label: we are not entitled to say "Online" from a
        // reading we know is minutes old.
        stale -> "Waiting for data…" to LogosColors.gray400
        !s.answered -> "Waiting for data…" to LogosColors.gray400
        // Alive but replaying its database. 1-click calls this exact state "Recovering
        // chain" (NodeDashboardView _statusDisplay), so we use its words. The block below
        // (StatusTab) shows the "Replaying N stored blocks…" sub-line from s.notice, giving
        // the same title+subtitle pair the desktop shows. Orange: busy, not broken.
        s.isRecovering() -> "Recovering chain" to LogosColors.orange300
        // The node reported an actual, actionable failure — the ONLY red node state. The
        // module sets status=="Error" solely when the node was meant to be up and is down
        // with a cause, so a deliberately stopped node can never land here.
        s.status == "Error" && s.error.isNotEmpty() -> "Error" to LogosColors.red500
        // The user asked for the node to be off, and it is. Neutral, never red — and the
        // control still offers Start. This is what a phone-initiated stop must read as,
        // instead of the old red "deploy config" card.
        s.isStopped() -> "Node stopped" to LogosColors.gray400
        s.state.equals("Online", true) -> "Online" to LogosColors.green500
        s.state.equals("Bootstrapping", true) -> "Bootstrapping" to LogosColors.orange300
        s.status == "Running" -> s.state.ifEmpty { "Running" } to LogosColors.orange300
        // Coming up, but not yet replaying and not yet answering. Its own honest word —
        // NOT "Recovering chain" (there is nothing to replay).
        s.isStarting() -> "Starting…" to LogosColors.orange300
        !s.reachable -> "Unknown" to LogosColors.gray400
        else -> "Not Started" to LogosColors.red500
    }
    Surface(color = color.copy(alpha = 0.12f), shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontWeight = FontWeight.Bold, color = color,
                     style = MaterialTheme.typography.titleMedium,
                     modifier = Modifier.weight(1f))
                // Start/Stop lives HERE, next to the thing it acts on, rather than in the
                // title bar where it sat beside an app name it has nothing to do with.
                control()
            }
        }
    }
}

/** Full-width label/value row, same shape as the Status block. */
@Composable
private fun InfoRow(label: String, value: String, accent: Color) {
    Surface(color = LogosColors.gray875, shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant,
                 modifier = Modifier.weight(1f))
            Text(value, fontWeight = FontWeight.Bold, color = accent,
                 style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun Tile(label: String, value: String, m: Modifier = Modifier,
                 accent: Color = LogosColors.white) {
    Card(m) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall,
                 fontWeight = FontWeight.Bold, color = accent, maxLines = 1,
                 overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CopyRow(label: String, value: String) {
    if (value.isEmpty()) return
    val clip = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1400); copied = false } }
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontFamily = FontFamily.Monospace,
                 style = MaterialTheme.typography.bodySmall,
                 maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { clip.setText(AnnotatedString(value)); copied = true }) {
            CopyGlyph(if (copied) LogosColors.green500 else LogosColors.gray400)
        }
    }
}

/**
 * Lucide's `copy` icon, drawn to its real geometry rather than approximated:
 *   <rect width="14" height="14" x="8" y="8" rx="2"/>
 *   <path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/>
 * on a 24x24 grid, stroke 2, round caps and joins. The back shape is an L (not a full
 * rect) so it reads as a sheet BEHIND the front one instead of two stacked outlines.
 */
@Composable
private fun CopyGlyph(tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        val u = size.minDimension / 24f          // Lucide's grid unit
        val stroke = Stroke(width = 2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)

        // Front sheet: rounded rect at (8,8), 14x14, r=2
        drawRoundRect(
            color = tint,
            topLeft = Offset(8f * u, 8f * u),
            size = Size(14f * u, 14f * u),
            cornerRadius = CornerRadius(2f * u, 2f * u),
            style = stroke,
        )

        // Back sheet: the visible L, with 2-unit rounded corners.
        val p = Path().apply {
            moveTo(4f * u, 16f * u)
            quadraticBezierTo(2f * u, 16f * u, 2f * u, 14f * u)   // bottom-left corner
            lineTo(2f * u, 4f * u)
            quadraticBezierTo(2f * u, 2f * u, 4f * u, 2f * u)     // top-left corner
            lineTo(14f * u, 2f * u)
            quadraticBezierTo(16f * u, 2f * u, 16f * u, 4f * u)   // top-right corner
        }
        drawPath(p, tint, style = stroke)
    }
}

@Composable
fun BlocksTab(blocks: List<Block>) {
    if (blocks.isEmpty()) {
        Empty("No blocks yet.\nBlocks stream in as the node produces them.")
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp),
               verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(blocks) { b ->
            var open by remember { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("slot ${b.slot.ifEmpty { "—" }}", fontWeight = FontWeight.Bold)
                        Text("${b.txCount} tx", style = MaterialTheme.typography.labelLarge,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(b.timestamp, style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!b.parsed) {
                        Text("unparsed payload", color = MaterialTheme.colorScheme.error,
                             style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { open = !open }) {
                        Text(if (open) "Hide" else "Details", color = LogosColors.orange300)
                    }
                    if (open) {
                        CopyRow("blockRoot", b.blockRoot); CopyRow("parentBlock", b.parentBlock)
                        CopyRow("leaderKey", b.leaderKey); CopyRow("entropy", b.entropy)
                        CopyRow("proof", b.proof);         CopyRow("voucherCm", b.voucherCm)
                        CopyRow("signature", b.signature)
                    }
                }
            }
        }
    }
}

@Composable
fun ProposalsTab(props: List<Proposal>) {
    if (props.isEmpty()) {
        Empty("No proposals yet.\nThese are blocks THIS node produced — they appear only when it wins a leader slot.")
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp),
               verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(props) { p ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(p.time.ifEmpty { "—" }, fontWeight = FontWeight.Bold,
                         modifier = Modifier.padding(horizontal = 14.dp))
                    Text("${p.txs} tx" + if (p.removed > 0) " · ${p.removed} removed" else "",
                         style = MaterialTheme.typography.labelMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant,
                         modifier = Modifier.padding(horizontal = 14.dp))
                    CopyRow("id", p.id)
                }
            }
        }
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Notifications tab. Master switch, a privacy switch, then the per-event list.
 *
 * The event set mirrors what the ecodev watcher (logos-node-monitor.py) tracks, so the
 * phone alerts on the same things the desktop indicator turns red for.
 */
/**
 * Quiet hours as two hour pickers. Equal values mean OFF — that is the storage format the
 * service already uses, surfaced rather than reinvented.
 *
 * Hour granularity, not minutes: this suppresses node alarms overnight, and nobody needs
 * 22:45 precision for that. A full time picker would be more UI for no more capability.
 */
@Composable
private fun QuietHoursRow(settings: Settings, enabled: Boolean, onChanged: () -> Unit) {
    var from by remember { mutableIntStateOf(settings.quietFrom) }
    var to by remember { mutableIntStateOf(settings.quietTo) }
    val on = from != to
    val dim = if (enabled) LogosColors.white else LogosColors.gray400

    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Quiet hours", style = MaterialTheme.typography.bodyLarge, color = dim)
                Text(if (on) "Alerts are held between %02d:00 and %02d:00".format(from, to)
                     else "Off — alerts arrive at any hour",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HourPicker(from, enabled) { from = it; settings.quietFrom = it; onChanged() }
            Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant,
                 modifier = Modifier.padding(horizontal = 6.dp))
            HourPicker(to, enabled) { to = it; settings.quietTo = it; onChanged() }
        }
    }
}

/** Tap to advance an hour, long-press to go back — a compact stepper without a dialog. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HourPicker(value: Int, enabled: Boolean, onSet: (Int) -> Unit) {
    Surface(
        color = LogosColors.gray875,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.combinedClickable(
            enabled = enabled,
            onClick = { onSet((value + 1) % 24) },
            onLongClick = { onSet((value + 23) % 24) },
        ),
    ) {
        Text("%02d".format(value),
             modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
             color = if (enabled) LogosColors.white else LogosColors.gray400,
             style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun SettingsPage(settings: Settings, onChanged: () -> Unit, onDisconnect: () -> Unit,
                 onWipe: () -> Unit = {}, onRegenerate: () -> Unit = {},
                 nodeRunning: Boolean = false) {
    var master by remember { mutableStateOf(settings.showNotifications) }
    var priv by remember { mutableStateOf(settings.privateNotifications) }
    // Recomposition key: toggling a per-event switch has to redraw the row it lives in.
    var rev by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
           verticalArrangement = Arrangement.spacedBy(4.dp)) {

        SwitchRow(
            title = "Show notifications",
            // Name the mechanism, because it is visible and it uses battery: turning this
            // on runs a foreground service, which Android REQUIRES to show a permanent
            // notification. People who find that notification and cannot explain it come
            // away thinking the app is misbehaving.
            blurb = "Runs a background watcher so alerts arrive with the app closed. " +
                    "Android shows a permanent \"Node Remote\" notification while it runs. " +
                    "Off means no watcher and no battery use.",
            checked = master,
            onCheck = { master = it; settings.showNotifications = it; onChanged() },
        )
        SwitchRow(
            title = "Private notifications",
            blurb = "Alerts show only the app name on the lock screen — not your node's " +
                    "state, height or balance.",
            checked = priv,
            enabled = master,
            onCheck = { priv = it; settings.privateNotifications = it; onChanged() },
        )

        // Quiet hours. The service has honoured these since it was written, but nothing
        // could SET them — quietFrom and quietTo stayed 0/0, which the code reads as
        // "disabled", so the feature existed only in the source.
        QuietHoursRow(settings, enabled = master, onChanged = onChanged)

        HorizontalDivider(Modifier.padding(vertical = 14.dp),
                          color = MaterialTheme.colorScheme.outlineVariant)

        Text("Notify me about", style = MaterialTheme.typography.labelLarge,
             color = MaterialTheme.colorScheme.onSurfaceVariant,
             modifier = Modifier.padding(bottom = 6.dp))

        key(rev) {
            Event.entries.forEach { e ->
                SwitchRow(
                    title = e.title,
                    blurb = e.blurb,
                    checked = settings.enabledRaw(e),
                    enabled = master,
                    onCheck = { settings.setEnabled(e, it); rev++; onChanged() },
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))

        Text("Node maintenance", style = MaterialTheme.typography.labelLarge,
             color = MaterialTheme.colorScheme.onSurfaceVariant,
             modifier = Modifier.padding(bottom = 8.dp))

        ActionRow(
            title = "Regenerate config",
            // Say what happens to the file, in the order it happens, and name the thing you
            // would go looking for. "Backed up first" told the reader nothing they could
            // act on — backed up where, under what name, and to undo what?
            blurb = "Replaces the node's settings file with a fresh default one. Your " +
                    "current file is copied next to it as user_config.yaml.bak-<time> " +
                    "first, so you can restore it by hand.",
            enabled = !nodeRunning,
            onClick = onRegenerate,
        )
        ActionRow(
            title = "Wipe database",
            blurb = "Deletes the chain database and consensus state, then re-downloads the " +
                    "whole chain from genesis — hours, not minutes. keystore.yaml and " +
                    "user_config.yaml are kept, so your wallet and settings survive.",
            enabled = !nodeRunning,
            danger = true,
            onClick = onWipe,
        )
        if (nodeRunning) {
            Text("Stop the node to use these.",
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant,
                 modifier = Modifier.padding(top = 4.dp))
        }

        Spacer(Modifier.height(28.dp))

        // Outlined, not filled: this ends the pairing and the phone loses the node until
        // it scans a new QR. Destructive enough to want deliberate, not eye-catching.
        OutlinedButton(
            onClick = onDisconnect,
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, LogosColors.red500),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = LogosColors.red500),
        ) { Text("Disconnect", fontWeight = FontWeight.Medium) }

        // The important half was missing. Disconnect clears this phone's copy of the
        // pairing — it does NOT revoke the key on the desktop. Someone who thought this
        // secured a lost phone would be wrong, and would not find out.
        Text("Forgets the pairing on this phone; you'll need a new QR from Basecamp to pair " +
             "again. It does NOT revoke this device on the desktop — if the phone is lost, " +
             "press Disconnect in the Node Remote app in Basecamp instead.",
             style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant,
             modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
    }
}

/**
 * Lucide icons, rendered from their ACTUAL path data via PathParser rather than
 * hand-approximated — the gear in particular is a 40-segment bezier outline that no
 * amount of circles-and-ticks reproduces honestly.
 *
 * Source: lucide.dev, 24x24 viewBox, stroke 2, round cap/join.
 */
private object Lucide {
    // lucide `settings`
    const val SETTINGS =
        "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 " +
        "0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 " +
        "2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 " +
        "2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 " +
        "2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 " +
        "2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 " +
        "2 0 0 0-2-2z"
    const val SETTINGS_HUB = "M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0z"
    // lucide `chevron-left`
    const val CHEVRON_LEFT = "M15 18l-6-6 6-6"
}

/** Lucide `chevron-left`. A drawn glyph, not a text "‹" — a text chevron sits on the
 *  font baseline and never lines up with an adjacent title. */
@Composable
fun ChevronLeftGlyph(tint: Color, size: Dp = 24.dp) {
    val p = remember { PathParser().parsePathString(Lucide.CHEVRON_LEFT).toPath() }
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension / 24f
        scale(s, pivot = Offset.Zero) {
            drawPath(p, tint, style = Stroke(width = 2f, cap = StrokeCap.Round,
                                             join = StrokeJoin.Round))
        }
    }
}

@Composable
fun GearGlyph(tint: Color, size: Dp = 22.dp) {
    val outline = remember { PathParser().parsePathString(Lucide.SETTINGS).toPath() }
    val hub = remember { PathParser().parsePathString(Lucide.SETTINGS_HUB).toPath() }
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension / 24f          // Lucide's viewBox is 24x24
        scale(s, pivot = Offset.Zero) {
            val stroke = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawPath(outline, tint, style = stroke)
            drawPath(hub, tint, style = stroke)
        }
    }
}

@Composable
private fun ActionRow(title: String, blurb: String, enabled: Boolean,
                      danger: Boolean = false, onClick: () -> Unit) {
    val tint = when {
        !enabled -> LogosColors.gray400
        danger -> LogosColors.red500
        else -> LogosColors.white
    }
    Row(Modifier.fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.Medium, color = tint)
            Text(blurb, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("\u203a", color = tint, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun SwitchRow(
    title: String,
    blurb: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheck: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.Medium,
                 color = if (enabled) LogosColors.white else LogosColors.gray400)
            Text(blurb, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked && enabled,
            enabled = enabled,
            onCheckedChange = onCheck,
            colors = SwitchDefaults.colors(
                checkedThumbColor = LogosColors.gray900,
                checkedTrackColor = LogosColors.orange300,
                uncheckedTrackColor = LogosColors.gray320,
                uncheckedBorderColor = LogosColors.gray300,
            ),
        )
    }
}
