#pragma once

// Tor v3 onion service with client authorization.
//
// Lifted from booth-basecamp/radio_module/src/radio_plugin.cpp:893-1035, which has been
// running 24/7 on Sneg and carries four Senty security-review findings in its structure:
//   FINDING-4  tor state (key material) must not be world-readable      → 0700 on every dir
//   ISSUE-5    a failed start must leave nothing on disk                → removeRecursively on any failure
//   ISSUE-3    an immediate tor exit means bad config / port in use     → don't proceed as if healthy;
//              and descriptor-publish polling must be bounded           → hard cap, then a real timeout
//   ISSUE-2    SocksPort 0 — this tor ONLY serves the hidden service
// Keep those properties. They were each paid for once.
//
// What is NEW here vs radio: v3 client authorization. The service publishes its descriptor
// encrypted to a set of authorized X25519 public keys, so a client without the matching
// private key cannot connect, cannot fetch the descriptor, and cannot confirm the service
// exists. That property is the entire privacy claim of this module — see authorizeClient().

#include <QObject>
#include <QProcess>
#include <QString>
#include <QStringList>
#include <QTimer>

class OnionService : public QObject
{
    Q_OBJECT

public:
    explicit OnionService(QObject* parent = nullptr);
    ~OnionService() override;

    // Publish 127.0.0.1:<localPort> as an onion service on virtual port 80.
    // Returns an empty string on success, else a stable error code:
    //   tor_dir_failed | tor_cfg_failed | tor_not_found | tor_start_failed | tor_port_in_use
    QString start(quint16 localPort);
    void    stop();

    // The .onion hostname. Empty until tor has written it (a second or two after start).
    QString onion() const { return m_onion; }

    // True once the descriptor has been uploaded to the HSDirs — i.e. actually reachable.
    // Do NOT hand out a pairing QR before this: the QR's 120s window would otherwise be
    // spent waiting for descriptor upload. (radio learned this the hard way.)
    bool    isReady() const { return m_ready; }
    QString lastError() const { return m_error; }

    // Authorize a client. `x25519PubBase32` is the client's X25519 public key in the
    // base32 form tor expects. Writes <hsDir>/authorized_clients/<name>.auth.
    //
    // Tor reads authorized_clients ONLY AT STARTUP, so this does NOT take effect until
    // reload(). Callers must reload — otherwise pairing appears to succeed while the
    // service is still reachable by anyone. (Caught by tests/pairing_e2e.sh P3.)
    bool authorizeClient(const QString& name, const QString& x25519PubBase32);

    // Remove a client. If this removes the LAST one, a deny-all sentinel is installed
    // (see sealClosed) so the service stays shut rather than reverting to open.
    bool revokeClient(const QString& name);

    // Restart tor so authorized_clients changes take effect. The .onion address is
    // persistent key material, so it survives — same address, new auth set.
    /// Re-read authorized_clients. `hard=false` sends SIGHUP: tor picks up the new client
    /// set while keeping its circuits, introduction points and published descriptor, so a
    /// pairing republishes in seconds instead of after a cold restart.
    ///
    /// `hard=true` fully restarts tor. REQUIRED for revocation: a HUP leaves the revoked
    /// client's cached descriptor and live intro points intact, so it can still reach the
    /// service and is stopped only by the bearer token — which leaks that the onion exists.
    QString reload(bool hard = false);
    QStringList authorizedClients() const;

    // Wipe the persistent HS key material → a brand-new .onion on next start.
    void regenerateAddress();

signals:
    void ready(const QString& onion);
    void failed(const QString& code);

private slots:
    void poll();

private:
    void    sealClosed();
    QString persistentHsDir() const;
    QString authClientsDir() const;
    QString torCacheDir() const;
    bool    spawnTor(const QString& cfg, QString& errOut);

    QProcess* m_tor = nullptr;
    QString   m_runDir;
    QString   m_onion;
    QString   m_error;
    bool      m_ready = false;
    /// Byte offset into hs.log from which a descriptor upload counts as "this publish".
    /// Without it, a stale upload line from the previous publish satisfies the readiness
    /// check and the service is announced reachable before the current descriptor exists.
    qint64    m_hsLogMark = 0;
    /// True while the current publish cycle followed a SIGHUP rather than a start. Tor is
    /// already bootstrapped in that case, so the "Bootstrapped 100%" readiness fallback
    /// would fire immediately and must be skipped.
    bool      m_afterHup = false;
    int       m_ticks = 0;
    int       m_bootstrappedAt = 0;
    QTimer    m_poll;
    quint16   m_localPort = 0;
};
