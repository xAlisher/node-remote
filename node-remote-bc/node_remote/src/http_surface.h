#pragma once

// The loopback JSON API that the onion service publishes.
//
// Binds 127.0.0.1 ONLY. The onion is the sole path in from outside; binding 0.0.0.0
// would expose the node's control surface to the LAN and quietly defeat the point.

#include <QHttpServer>
#include <QObject>
#include <QString>
#include <QTcpServer>
#include <functional>

class NodeProbe;

class HttpSurface : public QObject
{
    Q_OBJECT

public:
    explicit HttpSurface(NodeProbe* probe, QObject* parent = nullptr);

    // Binds an ephemeral loopback port. Returns 0 on failure.
    quint16 listen();
    void    stop();
    quint16 port() const { return m_port; }

    // Bearer token required on every /v1 route except /v1/ping.
    // E1: supplied via NODE_REMOTE_TOKEN. Later replaced by the device store.
    void setToken(const QString& t) { m_token = t; }

    // Unix seconds of the last request that PASSED the bearer check, or 0 if no paired
    // device has ever spoken to us on this run.
    //
    // This exists because the desktop cannot otherwise tell pairing apart from paired.
    // Minting a client-auth key is step one of pairing — the key file appears the moment
    // the QR is rendered, long before any phone has scanned it. Treating that file as
    // proof of a connection made the UI declare "Connected" and hide the QR the instant
    // it was generated, so the code could never be scanned.
    qint64 lastAuthedAt() const { return m_lastAuthedAt; }
    void   forgetLastAuthed()   { m_lastAuthedAt = 0; }
    void   setLastAuthedAt(qint64 t) { m_lastAuthedAt = t; }   // restored from disk at start

    /// Called after a request passes the bearer check. The impl uses it to persist
    /// last-seen, throttled — HttpSurface has no business knowing about files.
    void setAuthedHook(std::function<void()> h) { m_onAuthed = std::move(h); }

    // Control handlers, injected by the impl so this class never includes logos_sdk.h.
    // Each returns the JSON body to send back.
    using Handler = std::function<QByteArray()>;
    // The status route MUST go through this when set. NodeProbe alone cannot produce
    // `balance`: the wallet RPCs live on blockchain_module, and HttpSurface deliberately
    // knows nothing about it. Without this the phone got probe-only status and balance
    // was structurally unreachable — the merging code existed and nothing ever called it.
    void setStatusHandler(Handler h) { m_status = std::move(h); }
    void setStartHandler(Handler h) { m_start = std::move(h); }
    void setBlocksHandler(Handler h) { m_blocks = std::move(h); }
    void setWipeHandler(Handler h) { m_wipe = std::move(h); }
    void setRegenHandler(Handler h) { m_regen = std::move(h); }
    void setProposalsHandler(Handler h) { m_proposals = std::move(h); }
    void setStopHandler(Handler h)  { m_stop  = std::move(h); }

private:
    bool authorized(const QHttpServerRequest& req) const;

    Handler m_status;
    Handler m_start;
    Handler m_blocks;
    Handler m_wipe;
    Handler m_regen;
    Handler m_proposals;
    Handler m_stop;

    QHttpServer* m_server = nullptr;
    QTcpServer*  m_tcp    = nullptr;
    NodeProbe*   m_probe  = nullptr;
    QString      m_token;
    quint16      m_port   = 0;
    // Written from authorized(), which is const — hence mutable, not a design smell.
    mutable qint64 m_lastAuthedAt = 0;
    std::function<void()> m_onAuthed;
};
