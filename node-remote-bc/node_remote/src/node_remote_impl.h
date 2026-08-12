#pragma once

#include <QMutex>
#include <QString>
#include <string>
#include "logos_module_context.h"

class OnionService;
class QTimer;
class BlockStore;
class HttpSurface;

/**
 * @brief Remote command surface for the Logos blockchain node.
 *
 * Serves a small JSON API on loopback and publishes it as a Tor v3 onion service with
 * client authorization, so a paired phone can read node state and (from E2) control it.
 *
 * Privacy property this module exists to provide: with client auth enabled, a party who
 * does not hold an authorized X25519 private key cannot connect to the onion, cannot
 * fetch its descriptor, and cannot confirm it exists. That is stronger than "the request
 * is rejected" — there is no response at all.
 *
 * E1 scope: onion + `GET /v1/status`, where status is read from the node's own REST API
 * on loopback. There is deliberately no `blockchain_module` dependency — it ships no
 * generated SDK header, so declaring it breaks the build (see metadata.json).
 */
class NodeRemoteImpl : public LogosModuleContext
{
public:
    NodeRemoteImpl();
    ~NodeRemoteImpl();

    /// Start the HTTP surface and the onion service. Returns a JSON object:
    /// {"ok":bool,"port":int,"error":"<code>"}. The .onion is not available yet —
    /// poll getRemoteInfo() until "ready" is true (descriptor publish takes ~20-60s).
    std::string startRemote();

    /// Stop the onion service and the HTTP surface.
    std::string stopRemote();

    /// {"running":bool,"ready":bool,"onion":"<addr>","port":int,"error":"","clients":[..]}
    std::string getRemoteInfo();

    /// The same payload the phone gets from GET /v1/status. Exposed as a module method
    /// so it is testable headlessly via `logoscore -c` with no Tor and no HTTP.
    std::string getNodeStatus();

    /// Start the blockchain node. `configPath` empty → resolve the same user_config.yaml
    /// the desktop UI uses. Returns {"ok":bool,"error":"..."}.
    /// Deliberately synchronous-looking to the caller but non-blocking underneath:
    /// blockchain_module.start can take tens of seconds, and blocking the module's
    /// event loop would stall the HTTP surface (and the phone's poll) with it.
    std::string startNode(const std::string& configPath, const std::string& deployment);

    /// Stop the blockchain node. Returns {"ok":bool,"error":"..."}.
    std::string stopNode();

    /// Wipe the chain database + consensus state. Refuses while the node is running.
    /// KEEPS keystore.yaml and user_config.yaml — the wallet keys and settings survive;
    /// only db/, state/ and logs/ go. The node re-runs IBD from genesis on next start.
    std::string wipeDatabase();

    /// Regenerate user_config.yaml. `initialPeers` is a comma-separated list; empty keeps
    /// whatever the current config has.
    std::string regenerateConfig(const std::string& initialPeers);

    /// Blocks seen since load, newest first. Same 14 fields as logos_node_1click's
    /// BlockModel so the phone and the desktop tab agree.
    /// No limit argument: the universal codegen has no LIDL mapping for `int`, and the
    /// store is already capped at 100 (BlockModel::kMaxBlocks). Slice client-side.
    std::string getBlocks();

    /// Blocks THIS node proposed: [{id,txs,removed,time}]. Scraped from the node's logs —
    /// blockchain_module exposes no proposals API.
    std::string getProposals();

    /// Begin pairing: mint an X25519 client-auth keypair, pre-authorize it, and mint a
    /// one-time enrollment token. Returns {uri, sas, expiresAt, deviceKey}.
    /// The `uri` is what the QR encodes. Do NOT display it before the onion is ready —
    /// the 120s window would be spent waiting for the descriptor upload.
    std::string beginPairing(const std::string& label);

    /// Encode `payload` as a QR matrix. Returns {"ok":true,"n":<size>,"cells":[bool…]},
    /// row-major, n*n entries, true = dark.
    ///
    /// This lives HERE rather than calling the separate `qr` core module on purpose.
    /// The QML pane's cross-module call to qr.generateCard failed on a machine where the
    /// qr module was installed and loaded, which made the pairing code unrenderable —
    /// and a pairing surface that depends on a second module being present, loaded AND
    /// reachable has three ways to show the user nothing. The encoder is ~1300 vendored
    /// lines with no runtime dependencies, so bundling it removes all three.
    std::string generateQr(const std::string& payload);

    /// Exercise the pairing primitives against known answers. Returns a JSON report.
    /// Exists so the crypto is provable headlessly, with no phone and no Tor.
    std::string selfTest();

    /// Authorize a paired client. `x25519PubBase32` is the client's public key.
    /// Returns {"ok":bool}. Takes effect on the next restartOnion().
    std::string authorizeClient(const std::string& name, const std::string& x25519PubBase32);
    std::string revokeClient(const std::string& name);

    /// Mint a fresh .onion (wipes the persistent HS key material).
    std::string regenerateOnion();

protected:
    /// Framework hook — modules() is only valid from here on. Subscribing to
    /// blockchain_module's newBlock from the constructor would dereference a null
    /// LogosModules pointer.
    void onContextReady() override;

logos_events:
    /// Emitted once the onion descriptor is published and the service is reachable.
    void onionReady(const std::string& onion);
    /// Emitted on a terminal onion failure with a stable code.
    void onionFailed(const std::string& code);

private:
    // The bearer token minted at pairing time, kept across restarts so an already-paired
    // phone does not start silently 401ing after Basecamp is restarted.
    // std::string, not QString: this header is Qt-free by design — the universal codegen
    // parses it, and it declares only forward-declared classes and <string>.
    std::string tokenPath() const;
    void        persistToken(const std::string& token) const;
    std::string loadToken() const;

    // Last-seen is persisted so a restart does not forget an existing pairing. Throttled:
    // this is written from the request path.
    // std::string / long long, not QString / qint64 — this header stays Qt-free (the
    // universal codegen parses it) and I had just broken that rule two lines after writing
    // it down for the token helpers.
    std::string lastSeenPath() const;
    void        persistLastSeenThrottled() const;
    long long   loadLastSeen() const;
    mutable long long m_lastSeenPersistedAt = 0;

    // Wallet figures, refreshed on a timer and merged into status. NEVER fetched inside a
    // request: the RPCs are synchronous IPC on the same event loop the HTTP server uses.
    void refreshBalance();
    QTimer* m_balanceTimer = nullptr;
    bool    m_ipcBusy = false;      // re-entrancy guard around synchronous wallet IPC
    // Guards the four strings below: written on the balance timer, read on the HTTP
    // request path, and the two demonstrably overlap. Unsynchronised QString sharing
    // across threads is what was killing the module.
    mutable QMutex m_balanceMu;
    QString m_primaryAddress, m_balanceRaw, m_balance, m_balanceError;

    OnionService* m_onion = nullptr;
    BlockStore*   m_blocks = nullptr;
    HttpSurface*  m_http  = nullptr;
    unsigned short m_port = 0;
};
