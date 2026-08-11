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

    /// The node's own log, mapped to a plain-language cause. Ported from
    /// logos_node_1click_backend::lastNodeError() so both surfaces explain a failure the
    /// same way. Empty when nothing recognisable is in the tail.
    QString lastNodeError() const;

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
