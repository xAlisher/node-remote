package co.logos.noderemote

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
import androidx.compose.ui.graphics.Color
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

        NodeStatePill(s)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Tile("Height", if (s.height >= 0) "${s.height}" else "—", Modifier.weight(1f))
            Tile("Slot", if (s.slot >= 0) "${s.slot}" else "—", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Tile("Peers", if (s.peers >= 0) "${s.peers}" else "—", Modifier.weight(1f))
            Tile("Connections", o?.optString("connections")?.ifEmpty { null } ?: "—", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Tile("Balance", o?.optString("balance")?.ifEmpty { null } ?: "—",
                 Modifier.weight(1f), accent = LogosColors.orange300)
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

        if (s.error.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(s.error, Modifier.padding(12.dp),
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onErrorContainer)
            }
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
 * The NODE's state, in logos_node_1click's exact vocabulary:
 * Bootstrapping / Online / Not Started (NodeDashboardView.qml:63).
 * Connection state is deliberately NOT shown here — it lives under the title, because
 * conflating "my node is up" with "my phone can reach it" is how you end up staring at a
 * red pill wondering which one broke.
 */
@Composable
private fun NodeStatePill(s: NodeState) {
    val (label, color) = when {
        !s.reachable -> "Unknown" to LogosColors.gray400
        s.state.equals("Online", true) -> "Online" to LogosColors.green500
        s.state.equals("Bootstrapping", true) -> "Bootstrapping" to LogosColors.orange300
        s.status == "Running" -> (s.state.ifEmpty { "Running" }) to LogosColors.orange300
        else -> "Not Started" to LogosColors.red500
    }
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(color = color.copy(alpha = 0.18f), shape = MaterialTheme.shapes.large) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).background(color, RoundedCornerShape(5.dp)))
                Text(label, fontWeight = FontWeight.Bold, color = LogosColors.white)
            }
        }
        if (s.phase.isNotEmpty()) {
            // Real, but not part of the established vocabulary — no other module shows it,
            // so it is secondary rather than a headline.
            Text("phase ${s.phase}", style = MaterialTheme.typography.labelMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        TextButton(onClick = { clip.setText(AnnotatedString(value)); copied = true }) {
            Text(if (copied) "Copied" else "Copy",
                 color = if (copied) LogosColors.green500 else LogosColors.orange300)
        }
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
