#pragma once

#include <string>
#include "logos_module_context.h"

class OnionService;
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

    /// Begin pairing: mint an X25519 client-auth keypair, pre-authorize it, and mint a
    /// one-time enrollment token. Returns {uri, sas, expiresAt, deviceKey}.
    /// The `uri` is what the QR encodes. Do NOT display it before the onion is ready —
    /// the 120s window would be spent waiting for the descriptor upload.
    std::string beginPairing(const std::string& label);

    /// Exercise the pairing primitives against known answers. Returns a JSON report.
    /// Exists so the crypto is provable headlessly, with no phone and no Tor.
    std::string selfTest();

    /// Authorize a paired client. `x25519PubBase32` is the client's public key.
    /// Returns {"ok":bool}. Takes effect on the next restartOnion().
    std::string authorizeClient(const std::string& name, const std::string& x25519PubBase32);
    std::string revokeClient(const std::string& name);

    /// Mint a fresh .onion (wipes the persistent HS key material).
    std::string regenerateOnion();

logos_events:
    /// Emitted once the onion descriptor is published and the service is reachable.
    void onionReady(const std::string& onion);
    /// Emitted on a terminal onion failure with a stable code.
    void onionFailed(const std::string& code);

private:
    OnionService* m_onion = nullptr;
    HttpSurface*  m_http  = nullptr;
    unsigned short m_port = 0;
};
