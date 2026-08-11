package co.logos.noderemote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
fun StatusTab(s: NodeState, raw: String) {
    val o = remember(raw) { runCatching { JSONObject(raw) }.getOrNull() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
           verticalArrangement = Arrangement.spacedBy(12.dp)) {

        NodeStateBlock(s)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Tile("Height", if (s.height >= 0) "${s.height}" else "—", Modifier.weight(1f))
            Tile("Slot", if (s.slot >= 0) "${s.slot}" else "—", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Tile("Peers", if (s.peers >= 0) "${s.peers}" else "—", Modifier.weight(1f))
            Tile("Connections", o?.optString("connections")?.ifEmpty { null } ?: "—", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            // Orange is for a real value. An unknown balance is grey like any other
            // missing field — colouring a dash draws the eye to nothing.
            val balance = o?.optString("balance")?.ifEmpty { null }
            Tile("Balance", balance ?: "—", Modifier.weight(1f),
                 accent = if (balance != null) LogosColors.orange300 else LogosColors.gray400)
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

        o?.optString("balanceError")?.takeIf { it.isNotEmpty() }?.let {
            Text("Balance unavailable: $it",
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
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
@Composable
private fun NodeStateBlock(s: NodeState) {
    val err = s.error.takeIf { it.isNotEmpty() && s.reachable }
    val (label, color) = when {
        !s.answered -> "Waiting for data…" to LogosColors.gray400
        err != null -> "Error" to LogosColors.red500
        !s.reachable -> "Unknown" to LogosColors.gray400
        s.state.equals("Online", true) -> "Online" to LogosColors.green500
        s.state.equals("Bootstrapping", true) -> "Bootstrapping" to LogosColors.orange300
        s.status == "Running" -> s.state.ifEmpty { "Running" } to LogosColors.orange300
        else -> "Not Started" to LogosColors.red500
    }
    Surface(color = color.copy(alpha = 0.12f), shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Status", style = MaterialTheme.typography.labelLarge,
                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                     modifier = Modifier.weight(1f))
                Text(label, fontWeight = FontWeight.Bold, color = color)
            }
            // The node's own words, verbatim. Paraphrasing an error is how you lose the
            // one string that would have told you what actually happened.
            if (err != null) {
                Spacer(Modifier.height(6.dp))
                Text(err, style = MaterialTheme.typography.bodySmall, color = LogosColors.red500)
            }
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
@Composable
fun NotificationsTab(settings: Settings, onChanged: () -> Unit) {
    var master by remember { mutableStateOf(settings.showNotifications) }
    var priv by remember { mutableStateOf(settings.privateNotifications) }
    // Recomposition key: toggling a per-event switch has to redraw the row it lives in.
    var rev by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
           verticalArrangement = Arrangement.spacedBy(4.dp)) {

        SwitchRow(
            title = "Show notifications",
            blurb = "Alerts while Node Remote watches in the background",
            checked = master,
            onCheck = { master = it; settings.showNotifications = it; onChanged() },
        )
        SwitchRow(
            title = "Private notifications",
            blurb = "Hide the contents on the lock screen",
            checked = priv,
            enabled = master,
            onCheck = { priv = it; settings.privateNotifications = it; onChanged() },
        )

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
