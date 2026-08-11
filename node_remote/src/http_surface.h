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

    // Control handlers, injected by the impl so this class never includes logos_sdk.h.
    // Each returns the JSON body to send back.
    using Handler = std::function<QByteArray()>;
    void setStartHandler(Handler h) { m_start = std::move(h); }
    void setBlocksHandler(Handler h) { m_blocks = std::move(h); }
    void setWipeHandler(Handler h) { m_wipe = std::move(h); }
    void setRegenHandler(Handler h) { m_regen = std::move(h); }
    void setProposalsHandler(Handler h) { m_proposals = std::move(h); }
    void setStopHandler(Handler h)  { m_stop  = std::move(h); }

private:
    bool authorized(const QHttpServerRequest& req) const;

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
};
