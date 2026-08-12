#pragma once

// Reads live node state from the node's own REST API on loopback.
//
// Why HTTP and not blockchain_module: blockchain_module ships no generated SDK header
// (verified — no blockchain_module_api.h in /nix/store), so it cannot be a typed dep.
// Both logos-blockchain-ui and logos-node-basecamp/NodePlugin already read this data
// over HTTP anyway, so this is the established path, not a workaround.
//
// The API base is NOT hardcoded. logos-blockchain-ui hardcodes 127.0.0.1:8080 in four
// places, which is a real bug there; here we parse api.backend.listen_address out of the
// node's user_config.yaml, with an env override and a last-resort default.

#include <QByteArray>
#include <QNetworkAccessManager>
#include <QObject>
#include <QString>

class NodeProbe : public QObject
{
    Q_OBJECT

public:
    explicit NodeProbe(QObject* parent = nullptr);

    /// e.g. "http://127.0.0.1:8080" — resolution order:
    ///   1. $NODE_REMOTE_API_BASE
    ///   2. api.backend.listen_address from the node's user_config.yaml
    ///   3. http://127.0.0.1:8080
    QString apiBase() const;

    /// Path to the node's user_config.yaml, discovered via the same QSettings that
    /// logos-blockchain-ui persists (org "Logos", app "BlockchainUI", key userConfigPath),
    /// falling back to the newest module_data/blockchain_module/*/user_config.yaml.
    QString userConfigPath() const;

    /// The deployment file path the desktop UI persists (QSettings key deploymentConfigPath).
    /// EMPTY is normal and valid — it means the node's default embedded deployment, which is
    /// exactly what logos-blockchain-ui passes to blockchain_module.start(). A phone-initiated
    /// start must use the SAME value, not a hardcoded name that blockchain_module then tries
    /// to open as a file.
    QString deploymentConfigPath() const;

    /// The node's own log, mapped to a plain-language cause. Ported from
    /// logos_node_1click_backend::lastNodeError() so both surfaces explain a failure the
    /// same way. Empty when nothing recognisable is in the tail.
    QString lastNodeError() const;

    /// Binary user intent, SHARED with logos-blockchain-ui through the QSettings both
    /// modules already use (org "Logos", app "BlockchainUI", key "nodeIntent"). Start/Stop
    /// is the only thing a user commands; every richer state is node-driven and observed.
    /// This lets a Stop on the phone read as "Stopped" on the desktop, and vice versa.
    enum class Intent { Unknown, Started, Stopped };
    Intent readIntent() const;
    void   writeIntent(Intent) const;   // writes only on change — no per-poll churn

    /// Active chain-recovery (block replay after an unclean restart), parsed from the node
    /// log exactly as logos_node_1click_backend::getRecoveryStatus() does, so the phone can
    /// show the SAME "Replaying N stored blocks…" the desktop shows.
    struct Recovery { bool active = false; int blocks = 0; };
    Recovery recoveryStatus() const;

    /// Combined status payload. Never throws; on any failure the JSON carries
    /// "reachable":false plus an "error" string, so the phone can render honestly
    /// rather than showing a blank screen.
    QByteArray statusJson();

private:
    // Synchronous GET with a hard timeout — this runs off the module's own call, and a
    // hung node must not wedge the HTTP surface.
    QByteArray get(const QString& url, int timeoutMs, bool* ok = nullptr);

    QNetworkAccessManager m_nam;
    mutable QString m_cachedBase;
};
