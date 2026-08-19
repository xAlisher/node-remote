package co.logos.noderemote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/**
 * Leader rewards: a voucher POOL and a claims LEDGER.
 *
 * Two shapes, deliberately not one list. Before you claim, vouchers are fungible — every
 * one is worth the same, you do not choose between them, so they are a quantity and not a
 * set of things with identity. After you claim, you care about a CLAIM: it has a hash, a
 * cost, a duration and an outcome. Forcing both into one board would put the wrong clothes
 * on one of them (logos-blockchain-ui/docs/VOUCHER-STATE-MAP.md §4).
 *
 * The vocabulary here mirrors that document exactly, because the desktop panel exists to
 * fix a UI that stated things that were not true, and a phone that re-states them would
 * undo the fix:
 *
 *  - READY is not PENDING. The node sorts vouchers into `available` and `pending`
 *    (reserved, claim in flight) and sends only `available`. The desktop spent a release
 *    labelling those "pending", which named the one bucket the operator can never see.
 *  - SETTLED means FINALIZED — below LIB, not merely included in a block. Finalization
 *    runs hours behind the tip, so "Submitted" for a long time is normal, not a fault.
 *  - An UNKNOWN FEE renders as unknown, never as zero. A fee is the spent note minus its
 *    change, and the chain records only the note's id, so a claim whose note was spent
 *    before the ledger existed cannot be priced. Zero would be a false claim about money.
 *  - BLOCKS LED is not VOUCHERS EARNED, and the gap cannot be explained from here.
 *  - AMOUNTS ARE LGO. The chain's raw u64 IS LGO; there is no sub-unit to divide by.
 */

// ── One row of the ledger ──────────────────────────────────────────────────────────────
data class Claim(
    val tx: String,
    val status: String,          // submitted | in_block | settled | expired
    val slot: Long,
    val reward: Long,
    /** null = UNKNOWN, which is a different statement from 0 and must render differently. */
    val fee: Long?,
    val settledAt: String,
    val submittedAt: String,
    /** Expiry is inferred (nothing observable happens), so the UI says so rather than asserts. */
    val inferred: Boolean,
) {
    val net: Long? get() = fee?.let { reward - it }

    companion object {
        fun list(a: JSONArray?): List<Claim> {
            if (a == null) return emptyList()
            return (0 until a.length()).mapNotNull { i ->
                runCatching {
                    val o = a.getJSONObject(i)
                    Claim(
                        tx = o.optString("tx"),
                        status = o.optString("status"),
                        slot = o.optLong("slot", 0),
                        reward = o.optLong("reward", 0),
                        // has() not optLong(default): a fee we do not know must stay null all
                        // the way to the screen. Collapsing it to 0 here is exactly how a
                        // "+9,517 − 0" would get printed for a claim that certainly paid one.
                        fee = if (o.has("fee")) o.optLong("fee") else null,
                        settledAt = o.optString("settledAt"),
                        submittedAt = o.optString("submittedAt"),
                        inferred = o.optBoolean("inferred", false),
                    )
                }.getOrNull()
            }
        }
    }
}

/** The whole /v1/rewards frame. */
data class Rewards(
    val answered: Boolean = false,
    /** -1 = not read yet. Distinct from 0, which is a real and different answer. */
    val ready: Int = -1,
    val readyEstimate: Long = 0,
    val lastReward: Long = 0,
    val lastFee: Long? = null,
    val blocksLed: Int = 0,
    val claims: List<Claim> = emptyList(),
    val settled: Int = 0,
    val inFlight: Int = 0,
    val claimed: Long = 0,
    val fees: Long = 0,
    val feesComplete: Boolean = true,
    val net: Long = 0,
    val lastScannedSlot: Long = 0,
    val libSlot: Long = 0,
    val scanCaughtUp: Boolean = false,
    /** How far the ledger's scan trails LIB, in slots. -1 = chain position unknown. */
    val slotsBehind: Long = -1,
    /** The ledger came from a cached parse — the file was mid-rewrite when we read it. */
    val stale: Boolean = false,
    val error: String = "",
    val readyError: String = "",
) {
    /** What the tab badge shows. Never a number we have not actually read. */
    val badge: Int? get() = if (ready > 0) ready else null

    /**
     * Is the ledger current enough to state totals plainly?
     *
     * NOT `scanCaughtUp`. That is the strict `lastScanned >= libSlot`, and on a running
     * node it is essentially never true — LIB advances about a slot a second while the
     * desktop's scan runs every 20s, so the honest-looking "totals are still partial" would
     * sit on screen permanently. Measured live at 39 slots behind.
     *
     * A slot is about a second, so this tolerance is roughly three minutes of chain. Inside
     * it, the totals can be off by at most a claim submitted in the last few minutes — and
     * such a claim is `submitted`, counted under In flight, not under a settled total.
     * Outside it, the reader is told the distance rather than a bare "partial".
     */
    val ledgerCurrent: Boolean
        get() = scanCaughtUp || (slotsBehind in 0..SLOT_LAG_TOLERANCE)

    companion object {
        /** ~3 minutes of chain at one slot per second. */
        const val SLOT_LAG_TOLERANCE = 200L

        fun parse(json: String): Rewards = runCatching {
            val o = JSONObject(json)
            val s = o.optJSONObject("summary") ?: JSONObject()
            Rewards(
                answered = true,
                ready = o.optInt("ready", -1),
                readyEstimate = o.optLong("readyEstimate", 0),
                lastReward = o.optLong("lastReward", 0),
                lastFee = if (o.has("lastFee")) o.optLong("lastFee") else null,
                blocksLed = o.optInt("blocksLed", 0),
                claims = Claim.list(o.optJSONArray("claims")),
                settled = s.optInt("settled", 0),
                inFlight = s.optInt("inFlight", 0),
                claimed = s.optLong("claimed", 0),
                fees = s.optLong("fees", 0),
                feesComplete = s.optBoolean("feesComplete", true),
                net = s.optLong("net", 0),
                lastScannedSlot = s.optLong("lastScannedSlot", 0),
                libSlot = s.optLong("libSlot", 0),
                scanCaughtUp = s.optBoolean("scanCaughtUp", false),
                slotsBehind = s.optLong("slotsBehind", -1),
                stale = o.optBoolean("stale", false),
                error = o.optString("error"),
                readyError = o.optString("readyError"),
            )
        }.getOrElse { Rewards(answered = true, error = "unparseable") }
    }
}

// ── The screen ─────────────────────────────────────────────────────────────────────────

@Composable
fun RewardsTab(
    r: Rewards,
    live: Boolean,
    balanceRaw: Long,
    claiming: Boolean,
    onClaim: () -> Unit,
) {
    // Same rule as every other tab: not connected => no figures. A cached ledger rendered
    // without that gate reads as current data for a desktop that has been gone for minutes.
    if (!live) {
        Empty2("Not connected.\nRewards appear once the desktop answers.")
        return
    }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp),
               verticalArrangement = Arrangement.spacedBy(10.dp)) {

        item { PoolCard(r, balanceRaw, claiming, onClaim) }

        item {
            // IntrinsicSize.Min + fillMaxHeight makes both tiles as tall as the taller one.
            // Their subtitles differ in length — "24 claims" against "at most — some fees
            // unknown", which wraps to two lines — so without this the pair sat at visibly
            // different heights and read as two unrelated cards rather than one row.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                // "Claimed" is the lifetime total of settled rewards. When the chain scan
                // has not reached LIB the total is PARTIAL, and a partial sum presented as
                // a lifetime figure is the kind of quiet lie this screen exists to avoid.
                Tile2("Claimed",
                      if (r.settled > 0) fmtLgo(r.claimed) else "—",
                      sub = when {
                          r.settled == 0 -> "no claims yet"
                          !r.ledgerCurrent -> "${r.settled} claims · still scanning"
                          else -> "${r.settled} claims"
                      },
                      m = Modifier.weight(1f))
                // Net is claimed minus fees. It is only a TOTAL when every settled row has
                // a known fee; otherwise fees can only grow, so the figure is a ceiling and
                // is labelled with "at most".
                Tile2("Net",
                      if (r.settled > 0) fmtLgo(r.net) else "—",
                      sub = if (r.settled == 0) "after fees"
                            else if (r.feesComplete) "after ${fmtLgo(r.fees)} fees"
                            else "at most — some fees unknown",
                      m = Modifier.weight(1f))
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                // Blocks led is NOT vouchers earned, and the difference cannot be explained
                // from the phone: the wallet hides any voucher it cannot prove at the
                // current tip, and no API lists those. Measured on a live node: 111 led, 10
                // claimed, 12 claimable. So this is shown as a separate count and never
                // subtracted from anything.
                Tile2("Blocks led",
                      if (r.blocksLed > 0) "${r.blocksLed}" else "—",
                      sub = "from this node's log",
                      m = Modifier.weight(1f))
                Tile2("In flight",
                      if (r.inFlight > 0) "${r.inFlight}" else "—",
                      sub = if (r.inFlight > 0) "settles in ~2h" else "none submitted",
                      accent = if (r.inFlight > 0) LogosColors.orange300 else LogosColors.white,
                      m = Modifier.weight(1f))
            }
        }

        if (r.error.isNotEmpty()) item { NoteCard(r.error) }

        // How far behind the ledger is. Stated always, not only when something is wrong:
        // the file advances only while the desktop's Blockchain pane is loaded, so "caught
        // up" is a fact worth showing rather than an absence worth hiding.
        item { ScanLine(r) }

        if (r.claims.isEmpty()) {
            item {
                Empty2(if (r.error.isNotEmpty()) ""
                       else "No claims recorded yet.\nClaims appear here the moment one is submitted.")
            }
        } else {
            item {
                Text("Claims", style = MaterialTheme.typography.labelMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                     modifier = Modifier.padding(start = 4.dp, top = 6.dp))
            }
            items(r.claims) { c -> ClaimRow(c) }
        }
    }
}

@Composable
private fun PoolCard(r: Rewards, balanceRaw: Long, claiming: Boolean, onClaim: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }

    // The fee is what the LAST settled claim paid. It is the only fee figure that exists —
    // leader_claim does not quote one up front — so it is presented as a recent observation
    // and never as a price.
    val fee = r.lastFee
    val canAfford = fee == null || balanceRaw >= fee
    // Three conditions, all load-bearing. The desktop's button had no `enabled` binding at
    // all and stayed pressable through a press; nine presses became nine transactions, nine
    // distinct vouchers and nine fees — 37,557 LGO for what was meant to be one claim.
    val enabled = r.ready > 0 && canAfford && !claiming

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Ready to claim", style = MaterialTheme.typography.labelMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)

            Text(
                when {
                    r.ready > 0 -> "${r.ready} voucher" + if (r.ready == 1) "" else "s"
                    r.ready == 0 -> "Nothing ready"
                    else -> "—"
                },
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                color = if (r.ready > 0) LogosColors.orange300 else LogosColors.white,
            )

            // The pool's value is an ESTIMATE and says so. The reward is read from ledger
            // state when a claim executes and it does move — 9,517 then 9,535 then 9,664
            // observed on this chain — so this is "vouchers x the most recent settled
            // reward", never a promise of what a claim will pay.
            if (r.ready > 0 && r.readyEstimate > 0) {
                Text("≈ ${fmtLgo(r.readyEstimate)} at the last settled reward",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (fee != null) {
                Text("fee ${fmtLgo(fee)} per claim, last observed",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (r.readyError.isNotEmpty()) {
                Text(r.readyError, style = MaterialTheme.typography.labelSmall,
                     color = LogosColors.gray400)
            }

            Spacer(Modifier.height(4.dp))

            Button(onClick = { confirm = true }, enabled = enabled,
                   modifier = Modifier.fillMaxWidth()) {
                if (claiming) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp,
                                              color = LogosColors.gray900)
                    Spacer(Modifier.width(10.dp))
                    Text("Claiming…")
                } else {
                    Text("Claim one")
                }
            }

            // Say WHY it is off. A disabled control with no reason sends the reader looking
            // for a fault that may not exist — "nothing ready" is a normal, healthy state.
            if (!enabled && !claiming) {
                Text(
                    when {
                        r.ready == 0 -> "No vouchers ready. They appear as this node leads blocks."
                        r.ready < 0  -> "Waiting for the wallet to report the pool."
                        !canAfford   -> "Not enough balance to pay the claim fee " +
                                        "(${fmtLgo(balanceRaw)} available, fee ${fmtLgo(fee ?: 0)})."
                        else -> ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Blocks led vs vouchers ready, when they disagree. Presented as an open
            // question rather than an explanation, because no client can resolve it: a
            // voucher the wallet cannot prove at the current tip is in neither bucket and
            // no endpoint lists it.
            if (r.blocksLed > 0 && r.ready >= 0 && r.blocksLed > r.settled + r.ready) {
                Text("This node has led ${r.blocksLed} blocks. Not every one becomes a " +
                     "claimable voucher, and the difference is not visible from here.",
                     style = MaterialTheme.typography.labelSmall,
                     color = LogosColors.gray400)
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Claim one voucher?") },
            // Name the cost before the press, not after. This dialog is the second half of
            // the fix for the nine-claim incident: the disable stops a double press, and
            // this stops the first press being accidental.
            text = {
                Text(
                    buildString {
                        append("This submits one transaction and spends one voucher.")
                        if (fee != null) append("\n\nIt costs about ${fmtLgo(fee)} in fees, ")
                        else append("\n\nIt costs a fee, ")
                        if (r.lastReward > 0) append("for a reward of about ${fmtLgo(r.lastReward)}.")
                        else append("and pays a reward set by the chain.")
                        append("\n\nSettling takes about two hours. Claim again only after " +
                               "this one settles — claims fired in a burst are the ones " +
                               "observed to expire.")
                    }
                )
            },
            // Claim is the ACTION and carries the accent; Cancel is the way out and must not
            // look like a second action. Both rendered orange by default, which gave a
            // money-spending dialog two equally-weighted buttons — the one place on this
            // screen where the two choices must not look interchangeable.
            confirmButton = {
                TextButton(onClick = { confirm = false; onClaim() }) {
                    Text("Claim", color = LogosColors.orange300, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) {
                    Text("Cancel", color = LogosColors.white)
                }
            },
        )
    }
}

@Composable
private fun ClaimRow(c: Claim) {
    // No status glyph. The state is already carried by the word AND its colour, and a mark
    // in front of it was a third encoding of the same fact — it read as a control, and the
    // circle in particular looked like a spinner that never turned.
    val (tint, label) = when (c.status) {
        "settled"  -> LogosColors.green500 to "Settled"
        "in_block" -> LogosColors.orange300 to "In a block"
        "expired"  -> LogosColors.gray400 to "Expired"
        else       -> LogosColors.orange300 to "Submitted"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontWeight = FontWeight.Bold, color = tint)
                Spacer(Modifier.weight(1f))
                Text(c.settledAt.ifEmpty { c.submittedAt }.takeLast(8),
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            when (c.status) {
                "settled" -> {
                    // An unknown fee is printed as unknown. The alternative — treating a
                    // missing fee as 0 — would render "+9,664 − 0 = +9,664" for a claim
                    // that certainly paid one, which is a false statement about money.
                    val net = c.net
                    Text(
                        if (net != null)
                            "+${fmtLgo(c.reward)} − ${fmtLgo(c.fee!!)} = +${fmtLgo(net)}"
                        else
                            "+${fmtLgo(c.reward)} − fee unknown",
                        color = LogosColors.white, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                    if (net == null) {
                        Text("The chain records only the spent note's id, so this claim " +
                             "cannot be priced.",
                             style = MaterialTheme.typography.labelSmall,
                             color = LogosColors.gray400,
                             modifier = Modifier.padding(horizontal = 14.dp))
                    }
                }
                "expired" -> {
                    // The honest and complete statement. Nothing was lost: the node's
                    // reservation aged out, the voucher went back to the pool, and no fee
                    // was charged because the transaction never executed. This is an
                    // operational outcome, not a bug, and it must not be hidden.
                    Text("Voucher returned to the pool · no fee charged",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant,
                         modifier = Modifier.padding(horizontal = 14.dp))
                    if (c.inferred) {
                        Text("Inferred: an unlanded claim leaves nothing on chain to observe.",
                             style = MaterialTheme.typography.labelSmall,
                             color = LogosColors.gray400,
                             modifier = Modifier.padding(horizontal = 14.dp))
                    }
                }
                else -> {
                    // "Submitted" for hours is NORMAL. Finalization runs well behind the
                    // tip, so without saying so the state reads as something stuck.
                    Text("Waiting to finalize — about two hours is normal.",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant,
                         modifier = Modifier.padding(horizontal = 14.dp))
                }
            }

            // The SAME CopyRow the Proposals tab uses for a block id: the hash wraps to two
            // lines instead of being truncated, and it is copyable. A truncated tx hash is
            // decoration — it is the one value here you actually need to paste somewhere
            // (an explorer, a bug report), and the desktop panel shipped it as unreadable
            // grey ElideRight text, which is the mistake this avoids.
            CopyRow("tx", c.tx)
        }
    }
}

@Composable
private fun ScanLine(r: Rewards) {
    // The ledger is maintained by the desktop's Blockchain pane, not by this app. When it
    // is behind, the totals above are partial — so how far behind is part of the reading,
    // not a diagnostic detail.
    val text = when {
        r.libSlot <= 0 -> "Chain position unknown."
        r.stale -> "Ledger busy — showing the last good read, to slot ${grouped(r.lastScannedSlot)}."
        // Caught up exactly, or close enough that the difference cannot hide a settled
        // claim. Said plainly, because it is the normal state and deserves to read like it.
        r.ledgerCurrent -> "History complete to slot ${grouped(r.lastScannedSlot)}."
        // Genuinely behind. Give the DISTANCE, not a bare "partial" — how far behind is the
        // thing that tells you whether to trust the totals yet.
        else -> "History scanned to slot ${grouped(r.lastScannedSlot)}, " +
                "${grouped(r.slotsBehind)} slots behind the finalized tip — " +
                "totals above are still partial."
    }
    Text(text, style = MaterialTheme.typography.labelSmall,
         color = LogosColors.gray400, modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun NoteCard(text: String) {
    Surface(color = LogosColors.gray875, shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()) {
        Text(text, Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Tile2(label: String, value: String, sub: String = "",
                  m: Modifier = Modifier, accent: Color = LogosColors.white) {
    // fillMaxHeight is what actually equalises the pair — IntrinsicSize.Min on the parent
    // only sets the row's height; without this each card still shrinks to its own content.
    Card(m.fillMaxHeight()) {
        Column(Modifier.padding(14.dp).fillMaxHeight()) {
            Text(label, style = MaterialTheme.typography.labelMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge,
                 fontWeight = FontWeight.Bold, color = accent, maxLines = 1,
                 overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.labelSmall,
                     color = LogosColors.gray400, maxLines = 2)
            }
        }
    }
}

@Composable
private fun Empty2(text: String) {
    if (text.isEmpty()) return
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
