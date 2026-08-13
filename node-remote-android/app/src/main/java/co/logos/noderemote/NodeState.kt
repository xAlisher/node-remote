package co.logos.noderemote

import org.json.JSONObject

/**
 * One /v1/status frame, plus the transition logic that drives notifications.
 *
 * Transitions are computed phone-side from CONSECUTIVE frames — the desktop pushes
 * nothing. That keeps the server dumb and means notifications cost no extra round trips
 * beyond the poll the UI already needs.
 */
data class NodeState(
    /** The DESKTOP answered our request. Says nothing about the node itself. */
    val answered: Boolean = false,
    /** The desktop reached the NODE's API. Only meaningful when [answered]. */
    val reachable: Boolean = false,
    val status: String = "",        // Running | Starting | Recovering | Bootstrapping | Stopped | Error
    val state: String = "",         // Online | Bootstrapping
    val phase: String = "",
    val height: Long = -1,
    val slot: Long = -1,
    val peers: Int = -1,
    val error: String = "",
    /**
     * A node that is WORKING but busy — currently only "replaying stored blocks".
     * Deliberately separate from [error]: it is not a failure, it must not render red, and
     * it means the node is ALIVE, so offering "Start node" would be wrong.
     */
    val notice: String = "",
    /** Shared binary user intent as the desktop last recorded it: started | stopped | unknown. */
    val intent: String = "",
    /** Active chain recovery (block replay). [recoveryBlocks] is 0 when the count is unknown. */
    val recovering: Boolean = false,
    val recoveryBlocks: Int = 0,
    val atMillis: Long = 0,
) {
    companion object {
        fun parse(json: String, now: Long): NodeState = runCatching {
            val o = JSONObject(json)
            val rec = o.optJSONObject("recovering")
            NodeState(
                answered = true,
                reachable = o.optBoolean("reachable", false),
                status = o.optString("status"),
                state = o.optString("state"),
                phase = o.optString("phase"),
                height = o.optLong("height", -1),
                slot = o.optLong("slot", -1),
                peers = o.optInt("peers", -1),
                error = o.optString("error"),
                notice = o.optString("notice"),
                intent = o.optString("intent"),
                recovering = rec?.optBoolean("active", false) ?: (o.optString("status") == "Recovering"),
                recoveryBlocks = rec?.optInt("blocks", 0) ?: 0,
                atMillis = now,
            )
        }.getOrElse { NodeState(answered = true, error = "unparseable", atMillis = now) }

        /** Marker for "we could not reach the desktop at all" — distinct from "node is down". */
        fun unreachable(now: Long, why: String) =
            NodeState(answered = false, status = "Unreachable", error = why, atMillis = now)
    }
}

/**
 * True when the desktop ANSWERED and refused us. That is a definitive reply, not silence,
 * and it must never render as "Waiting for data…" — during the token bug the app sat on
 * that spinner indefinitely while every poll was being told 401.
 */
/**
 * Alive but replaying stored blocks before its API comes up — 1-click's "Recovering chain".
 * The process is up, so offer Stop, not Start. [recoveryBlocks] gives the count when known.
 */
fun NodeState.isRecovering() = recovering || status == "Recovering"

/**
 * The node is coming up but not yet replaying and not yet answering — a plain "Starting…".
 * Distinct from [isRecovering]: conflating the two labelled every startup "Recovering chain".
 */
fun NodeState.isStarting() = status == "Starting"

/** The user asked for the node to be off, and it is. Neutral — not a failure, not red. */
fun NodeState.isStopped() = status == "Stopped"

/**
 * The reading is too old to keep asserting.
 *
 * Every displayed value — the pill, and the tiles fed from the last good JSON — is a
 * SNAPSHOT. If polling stops (app backgrounded, watcher off, desktop gone) nothing clears
 * them, so the app kept showing "Online" with live-looking Height/Peers while Basecamp had
 * been down for minutes. A frozen frame presented as current is the same lie as a stale log
 * line presented as state.
 *
 * 45s: the phone polls every 10s foreground / 15s backgrounded, so three missed polls.
 */
/**
 * The 6-digit short authentication string, shown on BOTH ends so a user can confirm the
 * phone that scanned the code is the phone the desktop is talking to.
 *
 * Ported byte-for-byte from pairing.cpp sas(): HMAC-SHA256 over "lgnode/sas/v1|" + onion,
 * keyed by the enrollment token, first 4 bytes big-endian, mod 10^6, zero-padded.
 *
 * The app had NO implementation of this at all, while the desktop pane instructed the user
 * to "confirm this code matches your phone" — an instruction that could not be followed.
 * The SAS is the defence against a photographed QR: the attacker has the code but the
 * legitimate user sees digits that do not match on their own screen.
 */
fun pairingSas(token: String, onion: String): String {
    if (token.isEmpty() || onion.isEmpty()) return ""
    return runCatching {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(token.toByteArray(), "HmacSHA256"))
        mac.update("lgnode/sas/v1|".toByteArray())
        mac.update(onion.toByteArray())
        val d = mac.doFinal()
        val v = ((d[0].toLong() and 0xFF) shl 24) or ((d[1].toLong() and 0xFF) shl 16) or
                ((d[2].toLong() and 0xFF) shl 8) or (d[3].toLong() and 0xFF)
        "%06d".format(v % 1_000_000)
    }.getOrDefault("")
}

fun NodeState.isStale(nowMillis: Long) =
    answered && atMillis > 0 && (nowMillis - atMillis) > 45_000

fun NodeState.isRejected() = !answered && error.startsWith("HTTP 4")

/**
 * A notifiable event. `key` is the settings toggle; `defaultOn` is its default.
 *
 * The set is grounded in what the ecodev watcher (infra/.../logos-node-monitor.py) actually
 * tracks — mode, height, peers, balance, error — rather than invented. That watcher's three
 * states are Online (green), Bootstrapping (yellow) and Offline (red, BLINKING), which is a
 * clear signal about which transition a node runner treats as an alarm.
 */
enum class Event(val key: String, val title: String, val blurb: String, val defaultOn: Boolean) {
    NODE_STOPPED("n_stopped", "Node stopped",
        "It was running and now it isn't", true),
    NODE_ERROR("n_error", "Node error",
        "The node reported a problem", true),
    LINK_LOST("n_link", "Can't reach your node",
        "Your phone lost the connection — the node may be fine", false),
    BOOTSTRAPPING("n_boot", "Bootstrapping",
        "Catching up with the chain instead of following it", false),
    SYNC_STALLED("n_stalled", "Sync stalled",
        "Height hasn't moved for 10 minutes while running", false),
    PEERS_ZERO("n_peers0", "No peers",
        "Lost every peer connection", false),
    NODE_STARTED("n_started", "Node started",
        "Came back up", false),
    BALANCE_CHANGED("n_balance", "Balance changed",
        "Usually a leader reward landing", false),
    NEW_PROPOSAL("n_proposal", "Block proposed",
        "Your node won a leader slot and produced a block", false),
}

data class Notice(val event: Event, val text: String)

/**
 * Compares two consecutive frames and reports what a user would want waking up for.
 *
 * Deliberately conservative about the difference between "the NODE went down" and "the
 * PHONE lost the link" — on mobile the second is common and mostly uninteresting, and
 * conflating them would train the user to ignore the alert that actually matters.
 */
object Transitions {

    /** height must be unchanged for this long, while Running, before we call it stalled. */
    const val STALL_MILLIS = 10 * 60 * 1000L

    fun diff(prev: NodeState?, cur: NodeState, stalledSinceMillis: Long?): List<Notice> {
        if (prev == null) return emptyList()          // first frame is a baseline, not news
        val out = mutableListOf<Notice>()

        // A deliberate stop: the DESKTOP answered and told us the node is Stopped, having
        // previously been up. This is a real node-down event, NOT a link loss — the desktop
        // is right here answering — so it must fire "Node stopped", which the old
        // both-reachable guard below could never reach (a stopped node has reachable=false).
        if (cur.answered && cur.isStopped() && (prev.reachable || prev.status == "Running")) {
            out += Notice(Event.NODE_STOPPED, "Your node stopped")
            return out   // a clean stop is the whole story; don't also cry LINK_LOST
        }

        // Link lost: we cannot reach the desktop. NOT the same as the node stopping — the
        // node may be perfectly fine and the phone simply off Wi-Fi.
        if (prev.reachable && !cur.reachable)
            out += Notice(Event.LINK_LOST, "Can't reach your node right now")

        // Only compare node-level facts when BOTH frames actually reached the desktop.
        if (prev.reachable && cur.reachable) {
            val wasUp = prev.status == "Running"
            val isUp = cur.status == "Running"
            if (wasUp && !isUp)
                out += Notice(Event.NODE_STOPPED, "Your node stopped (${cur.status})")
            if (!wasUp && isUp)
                out += Notice(Event.NODE_STARTED, "Your node is running")

            if (cur.error.isNotEmpty() && cur.error != prev.error)
                out += Notice(Event.NODE_ERROR, cur.error.take(120))

            if (prev.peers > 0 && cur.peers == 0)
                out += Notice(Event.PEERS_ZERO, "Node has no peers")

            // Bootstrapping is the watcher's yellow state — worth knowing, not an alarm.
            if (!prev.state.equals("Bootstrapping", true) &&
                cur.state.equals("Bootstrapping", true))
                out += Notice(Event.BOOTSTRAPPING, "Node is bootstrapping (catching up)")

            // Stall: Running, height not advancing for STALL_MILLIS. stalledSinceMillis is
            // held by the caller so this stays a pure function of the two frames + a clock.
            if (isUp && stalledSinceMillis != null &&
                cur.atMillis - stalledSinceMillis >= STALL_MILLIS)
                out += Notice(Event.SYNC_STALLED,
                              "Height stuck at ${cur.height} for ${STALL_MILLIS / 60000} min")
        }
        return out
    }
}
