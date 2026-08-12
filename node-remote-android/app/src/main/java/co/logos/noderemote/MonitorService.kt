package co.logos.noderemote

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

/**
 * Keeps Tor alive and polls /v1/status, raising notifications on transitions.
 *
 * A foreground service because the whole point is to notice a node dying while the app
 * is in the background — which is exactly when Android would otherwise freeze us.
 */
class MonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var prev: NodeState? = null
    private var lastHeight = -1L
    private var stalledSince: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uri = intent?.getStringExtra("uri").orEmpty()
        val token = intent?.getStringExtra("token").orEmpty()
        val periodMs = (intent?.getIntExtra("periodSec", 15) ?: 15) * 1000L

        createChannels()
        startForeground(FG_ID, ongoing("Watching your node", "starting…"))

        if (job == null) job = scope.launch { loop(uri, token, periodMs) }
        // STICKY: if Android kills us under pressure, come back. Without the intent we
        // would restart with no URI, hence the redelivery.
        return START_REDELIVER_INTENT
    }

    private suspend fun loop(uri: String, token: String, periodMs: Long) {
        val p = parsePair(uri)
        if (p == null) { Log.e(TAG, "bad pairing uri"); stopSelf(); return }
        val (onion, ca) = p

        TorClient.start(this) { Log.i(TAG, it) }
        var waited = 0
        while (TorClient.socksPort == 0 && waited < 180) { delay(1000); waited++ }
        TorClient.addClientAuth(onion, ca)
            .onFailure { Log.e(TAG, "client auth failed", it) }

        val prefs = Settings(this)
        while (currentCoroutineContext().isActive) {
            val now = System.currentTimeMillis()
            val cur = TorClient.get("http://$onion/v1/status", token)
                .map { NodeState.parse(it, now) }
                .getOrElse { NodeState.unreachable(now, it.message.orEmpty()) }

            // Track height stagnation across frames, not inside diff(), so a single
            // slow block doesn't reset the stall clock.
            if (cur.reachable && cur.status == "Running") {
                if (cur.height != lastHeight) { lastHeight = cur.height; stalledSince = now }
                else if (stalledSince == null) stalledSince = now
            } else { stalledSince = null; lastHeight = -1 }

            for (n in Transitions.diff(prev, cur, stalledSince)) {
                if (!prefs.enabled(n.event)) continue
                if (prefs.inQuietHours(now)) { Log.i(TAG, "quiet hours, suppressed: ${n.text}"); continue }
                notify(n)
                Log.i(TAG, "NOTIFY ${n.event.name}: ${n.text}")
            }
            // Log every poll. Without this a silent loop is indistinguishable from a
            // stalled one, which cost a debugging round earlier.
            Log.i(TAG, "poll reachable=${cur.reachable} status=${cur.status} " +
                       "height=${cur.height} peers=${cur.peers} err=${cur.error.take(60)}")
            prev = cur

            updateOngoing(cur)
            delay(periodMs)
        }
    }

    private fun parsePair(s: String): Pair<String, String>? = runCatching {
        val u = android.net.Uri.parse(s.trim())
        val onion = u.getQueryParameter("onion").orEmpty()
        val ca = u.getQueryParameter("ca").orEmpty()
        if (onion.isEmpty() || ca.isEmpty()) null else onion to ca
    }.getOrNull()

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_FG, "Node watch", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Node alerts", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun ongoing(title: String, text: String): Notification =
        NotificationCompat.Builder(this, CH_FG)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE))
            .build()

    private fun updateOngoing(s: NodeState) {
        val text = when {
            // Only "can't reach" when the DESKTOP itself did not answer — not when it
            // answered and told us the node is deliberately stopped/recovering.
            !s.answered -> "can't reach your node"
            s.isStopped() -> "Node stopped"
            s.isRecovering() ->
                if (s.recoveryBlocks > 0) "Recovering — replaying ${s.recoveryBlocks} blocks"
                else "Recovering chain"
            s.status == "Running" -> "height ${s.height} · ${s.peers} peers · ${s.state}"
            s.state.equals("Bootstrapping", true) -> "Bootstrapping"
            s.isStarting() -> "Starting…"
            s.status == "Error" -> "Node error"
            !s.reachable -> "can't reach your node"
            else -> s.status
        }
        getSystemService(NotificationManager::class.java).notify(FG_ID, ongoing("Node Remote", text))
    }

    private fun notify(n: Notice) {
        val prefs = Settings(this)
        val note = NotificationCompat.Builder(this, CH_ALERT)
            .setContentTitle(n.event.title)
            .setContentText(n.text)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            // VISIBILITY_SECRET hides it from the lock screen entirely; PRIVATE would still
            // show the title. SECRET is what "private" should mean to a user.
            .setVisibility(if (prefs.privateNotifications)
                               NotificationCompat.VISIBILITY_SECRET
                           else NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(n.event.ordinal + 100, note)
    }

    override fun onDestroy() { job?.cancel(); scope.cancel(); super.onDestroy() }

    companion object {
        private const val TAG = "NodeRemoteMon"
        private const val CH_FG = "nr_watch"
        private const val CH_ALERT = "nr_alert"
        private const val FG_ID = 1

        fun start(ctx: Context, uri: String, token: String, periodSec: Int = 15) {
            val i = Intent(ctx, MonitorService::class.java)
                .putExtra("uri", uri).putExtra("token", token).putExtra("periodSec", periodSec)
            ContextCompat_startForegroundService(ctx, i)
        }
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, MonitorService::class.java))

        private fun ContextCompat_startForegroundService(ctx: Context, i: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }
}

/** Per-event toggles + quiet hours, in SharedPreferences. */
class Settings(ctx: Context) {
    private val sp = ctx.getSharedPreferences("node_remote", Context.MODE_PRIVATE)

    /**
     * The pairing itself: the lgnode:// URI (onion + client-auth key) and the bearer token.
     *
     * This has to be PERSISTED, not held in Compose state. It was held only in `remember`,
     * seeded from an Intent extra — so backgrounding the app under memory pressure, or any
     * process death, dropped a paired phone back to the welcome screen and demanded a new
     * QR. The pairing survived nothing.
     *
     * Plain app-private SharedPreferences, deliberately. androidx.security's
     * EncryptedSharedPreferences is deprecated and unmaintained, and app-private storage is
     * already encrypted at rest by File-Based Encryption. Honest limit: this does NOT
     * protect against a rooted device or forensic extraction from an unlocked phone. Anyone
     * with that level of access can pair with your node — which is what "Disconnect"
     * (revoking the key on the desktop) is for.
     */
    var pairUri: String
        get() = sp.getString("pair_uri", "").orEmpty()
        set(v) = sp.edit().putString("pair_uri", v).apply()
    var pairToken: String
        get() = sp.getString("pair_token", "").orEmpty()
        set(v) = sp.edit().putString("pair_token", v).apply()

    fun clearPairing() = sp.edit().remove("pair_uri").remove("pair_token").apply()

    /** Master switch. Off means the service posts nothing at all. */
    var showNotifications: Boolean
        get() = sp.getBoolean("notif_on", true)
        set(v) = sp.edit().putBoolean("notif_on", v).apply()

    /** Hide the content on the lock screen — the alert still arrives, but a passer-by
     *  sees only the app name, not your node's state. */
    var privateNotifications: Boolean
        get() = sp.getBoolean("notif_private", false)
        set(v) = sp.edit().putBoolean("notif_private", v).apply()

    fun enabled(e: Event): Boolean = showNotifications && enabledRaw(e)

    /** The row's own setting, ignoring the master switch — so turning the master off
     *  greys the list rather than appearing to wipe every choice. */
    fun enabledRaw(e: Event): Boolean = sp.getBoolean(e.key, e.defaultOn)
    fun setEnabled(e: Event, on: Boolean) = sp.edit().putBoolean(e.key, on).apply()

    /** Quiet hours as local wall-clock hours [from,to); disabled when equal. */
    var quietFrom: Int
        get() = sp.getInt("quiet_from", 0)
        set(v) = sp.edit().putInt("quiet_from", v).apply()
    var quietTo: Int
        get() = sp.getInt("quiet_to", 0)
        set(v) = sp.edit().putInt("quiet_to", v).apply()

    fun inQuietHours(nowMillis: Long): Boolean {
        val from = quietFrom; val to = quietTo
        if (from == to) return false
        val c = java.util.Calendar.getInstance().apply { timeInMillis = nowMillis }
        val h = c.get(java.util.Calendar.HOUR_OF_DAY)
        // Wraps past midnight (e.g. 23 → 7), which is the common case for quiet hours.
        return if (from < to) h in from until to else (h >= from || h < to)
    }
}
