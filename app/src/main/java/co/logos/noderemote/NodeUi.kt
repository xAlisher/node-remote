package co.logos.noderemote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/**
 * Screens for Node Remote.
 *
 * Field names mirror logos_node_1click exactly — the desktop Blockchain tab and this app
 * show the same data under the same labels, so a user moving between them isn't
 * re-learning anything.
 */

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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
           verticalArrangement = Arrangement.spacedBy(12.dp)) {

        StatePill(s)

        // The tiles the desktop dashboard leads with.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Tile("Height", if (s.height >= 0) "${s.height}" else "—", Modifier.weight(1f))
            Tile("Slot", if (s.slot >= 0) "${s.slot}" else "—", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Tile("Peers", if (s.peers >= 0) "${s.peers}" else "—", Modifier.weight(1f))
            // Phase is the node's consensus phase from /cryptarchia/info — "Following"
            // means it is tracking the canonical chain rather than still catching up.
            Tile("Phase", s.phase.ifEmpty { "—" }, Modifier.weight(1f))
        }

        val bal = runCatching { JSONObject(raw) }.getOrNull()
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Tile("Balance", bal?.optString("balance")?.ifEmpty { null } ?: "—",
                 Modifier.weight(1f), accent = LogosColors.orange300)
            Tile("Connections", bal?.optString("connections")?.ifEmpty { null } ?: "—",
                 Modifier.weight(1f))
        }

        if (s.error.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(s.error, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }

        // tip / lib are long hex — monospace and selectable, as on the desktop.
        val o = runCatching { JSONObject(raw) }.getOrNull()
        if (o != null) {
            HashRow("tip", o.optString("tip"))
            HashRow("lib", o.optString("lib"))
            HashRow("peerId", o.optString("peerId"))
            HashRow("apiBase", o.optString("apiBase"))
        }
    }
}

@Composable
private fun StatePill(s: NodeState) {
    val (label, color) = when {
        !s.reachable -> "Unreachable" to LogosColors.gray400
        s.status == "Running" && s.state == "Online" -> "Online" to LogosColors.green500
        s.status == "Running" -> (s.state.ifEmpty { "Running" }) to LogosColors.yellow500
        else -> s.status.ifEmpty { "—" } to LogosColors.red500
    }
    Surface(color = color.copy(alpha = 0.18f), shape = MaterialTheme.shapes.large) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(color = color, shape = MaterialTheme.shapes.small,
                    modifier = Modifier.size(10.dp)) {}
            Text(label, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Tile(label: String, value: String, m: Modifier = Modifier,
                 accent: Color = MaterialTheme.colorScheme.onSurface) {
    Card(m) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall,
                 fontWeight = FontWeight.Bold, color = accent)
        }
    }
}

@Composable
private fun HashRow(label: String, value: String) {
    if (value.isEmpty()) return
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun BlocksTab(blocks: List<Block>) {
    if (blocks.isEmpty()) {
        Empty("No blocks yet.\nBlocks arrive as the node produces them.")
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
                        Text("${b.txCount} tx", style = MaterialTheme.typography.labelLarge)
                    }
                    Text(b.timestamp, style = MaterialTheme.typography.labelSmall)
                    if (!b.parsed) {
                        Text("unparsed payload", color = MaterialTheme.colorScheme.error,
                             style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Details") }
                    if (open) {
                        Field("blockRoot", b.blockRoot); Field("parentBlock", b.parentBlock)
                        Field("leaderKey", b.leaderKey); Field("entropy", b.entropy)
                        Field("proof", b.proof);         Field("voucherCm", b.voucherCm)
                        Field("signature", b.signature); Field("version", b.version)
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
                Column(Modifier.padding(12.dp)) {
                    Text(p.time.ifEmpty { "—" }, fontWeight = FontWeight.Bold)
                    Text(p.id, fontFamily = FontFamily.Monospace,
                         style = MaterialTheme.typography.bodySmall)
                    Text("${p.txs} tx" + if (p.removed > 0) " · ${p.removed} removed" else "",
                         style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun Field(k: String, v: String) {
    if (v.isEmpty()) return
    Column(Modifier.padding(top = 6.dp)) {
        Text(k, style = MaterialTheme.typography.labelSmall)
        Text(v, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
