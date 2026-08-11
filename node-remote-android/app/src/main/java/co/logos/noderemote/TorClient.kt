package co.logos.noderemote

import android.content.Context
import android.util.Log
import io.matthewnelson.kmp.tor.common.api.ResourceLoader
import io.matthewnelson.kmp.tor.resource.exec.tor.ResourceLoaderTorExec
import io.matthewnelson.kmp.tor.runtime.Action.Companion.startDaemonAsync
import io.matthewnelson.kmp.tor.runtime.RuntimeEvent
import io.matthewnelson.kmp.tor.runtime.TorRuntime
import io.matthewnelson.kmp.tor.runtime.core.OnEvent
import io.matthewnelson.kmp.tor.runtime.core.config.TorOption
import io.matthewnelson.kmp.tor.runtime.core.ctrl.TorCmd
import io.matthewnelson.kmp.tor.runtime.core.key.X25519
import io.matthewnelson.kmp.tor.runtime.core.key.X25519.PrivateKey.Companion.toX25519PrivateKey
import io.matthewnelson.kmp.tor.runtime.core.net.OnionAddress
import io.matthewnelson.kmp.tor.runtime.core.net.OnionAddress.V3.Companion.toOnionAddressV3
import io.matthewnelson.kmp.tor.runtime.core.util.executeAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Embedded Tor + a client that can reach a CLIENT-AUTHORIZED v3 onion.
 *
 * The client-auth part is what makes Node Remote private: node_remote's onion publishes
 * an auth-encrypted descriptor, so without the key from the pairing QR this device
 * cannot connect to it, fetch its descriptor, or even confirm it exists.
 *
 * Lifted from receiver-android's TorManager, plus ONION_CLIENT_AUTH_ADD.
 */
object TorClient {

    @Volatile var socksPort: Int = 0; private set
    @Volatile var isReady: Boolean = false; private set
    @Volatile var lastError: String? = null; private set

    private var runtime: TorRuntime? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context, onLog: (String) -> Unit = {}) {
        if (runtime != null) return
        synchronized(this) {
            if (runtime != null) return
            val app = context.applicationContext
            val env = TorRuntime.Environment.Builder(
                workDirectory = File(app.filesDir, "kmptor"),
                cacheDirectory = File(app.cacheDir, "kmptor"),
                loader = ResourceLoaderTorExec::getOrCreate,
            )
            val rt = TorRuntime.Builder(env) {
                config {
                    // Let tor pick a free port — hardcoding one collides with any other
                    // Logos app on the device (receiver/booth both embed a node).
                    TorOption.__SocksPort.configure { auto() }
                }
                observerStatic(RuntimeEvent.LISTENERS, OnEvent.Executor.Immediate) { l ->
                    socksPort = l.socks.firstOrNull()?.port?.value ?: 0
                    onLog("SOCKS → 127.0.0.1:$socksPort")
                    Log.i(TAG, "SOCKS listener → 127.0.0.1:$socksPort")
                }
                observerStatic(RuntimeEvent.READY, OnEvent.Executor.Immediate) {
                    isReady = true
                    onLog("tor READY")
                    Log.i(TAG, "tor READY")
                }
                // Without these, a tor that exec's and immediately dies is completely
                // silent: LISTENERS never fires, ERROR never fires, and the app just sits
                // there. Cost is log noise; the alternative is undiagnosable failure.
                observerStatic(RuntimeEvent.LOG.WARN, OnEvent.Executor.Immediate) { m ->
                    onLog("tor WARN: $m"); Log.w(TAG, "WARN $m")
                }
                observerStatic(RuntimeEvent.LOG.INFO, OnEvent.Executor.Immediate) { m ->
                    Log.i(TAG, "INFO $m")
                }
                observerStatic(RuntimeEvent.LIFECYCLE, OnEvent.Executor.Immediate) { e ->
                    Log.i(TAG, "LIFECYCLE $e")
                }
                observerStatic(RuntimeEvent.STATE, OnEvent.Executor.Immediate) { st ->
                    onLog("tor state: $st"); Log.i(TAG, "STATE $st")
                }
                observerStatic(RuntimeEvent.ERROR, OnEvent.Executor.Immediate) { t ->
                    lastError = t.toString()
                    onLog("tor error: $t")
                    Log.e(TAG, "tor error", t)
                }
            }
            runtime = rt
            scope.launch {
                runCatching { rt.startDaemonAsync() }
                    .onFailure {
                        lastError = it.toString()
                        onLog("startDaemon failed: $it")
                        Log.e(TAG, "startDaemon failed", it)
                    }
            }
        }
    }

    /**
     * Register the client-auth key from a pairing QR. Until this succeeds, requests to
     * the onion fail with a SOCKS error — tor cannot even decrypt the descriptor.
     *
     * `privBase32` is the `ca=` field of the lgnode:// URI: the raw 32-byte X25519
     * private key, RFC-4648 base32, lowercase, unpadded.
     */
    suspend fun addClientAuth(onionHost: String, privBase32: String): Result<Unit> = runCatching {
        val rt = runtime ?: error("tor not started")
        // OnionAddress.V3 has no public constructor — build it via the String extension.
        val addr = onionHost.removeSuffix(".onion").toOnionAddressV3()
        val key = privBase32.uppercase().toX25519PrivateKey()
        rt.executeAsync(TorCmd.OnionClientAuth.Add(addr, key))
        Unit
    }

    /** OkHttp routed through tor's SOCKS. socks5h semantics: tor resolves the .onion. */
    fun http(): OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
        .connectTimeout(90, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    fun get(url: String, bearer: String?): Result<String> = runCatching {
        val req = Request.Builder().url(url).apply {
            if (!bearer.isNullOrEmpty()) header("Authorization", "Bearer $bearer")
        }.build()
        http().newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("HTTP ${r.code}: ${body.take(200)}")
            body
        }
    }

    private const val TAG = "NodeRemoteTor"
}
