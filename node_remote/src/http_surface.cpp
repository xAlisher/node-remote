#include "http_surface.h"

#include "node_probe.h"

#include <QHostAddress>
#include <QHttpServerRequest>
#include <QHttpServerResponse>
#include <QJsonDocument>
#include <QJsonObject>

HttpSurface::HttpSurface(NodeProbe* probe, QObject* parent)
    : QObject(parent), m_probe(probe)
{
    m_token = QString::fromLocal8Bit(qgetenv("NODE_REMOTE_TOKEN"));
}

bool HttpSurface::authorized(const QHttpServerRequest& req) const
{
    if (m_token.isEmpty()) return false;      // fail closed: no token configured → no access
    const QByteArray h = req.value("Authorization");
    const QByteArray want = "Bearer " + m_token.toUtf8();
    if (h.size() != want.size()) return false;
    // Constant-time compare — a length-independent early return would leak the token
    // byte by byte to anyone who can reach the onion (i.e. an authorized-but-revoked peer).
    unsigned char diff = 0;
    for (int i = 0; i < h.size(); ++i) diff |= static_cast<unsigned char>(h[i] ^ want[i]);
    return diff == 0;
}

quint16 HttpSurface::listen()
{
    if (m_server) return m_port;

    m_server = new QHttpServer(this);

    // Liveness only — no token, no node data. Lets the phone distinguish "onion is up but
    // my token is wrong" from "the onion is unreachable", which are very different fixes.
    m_server->route("/v1/ping", [] {
        return QHttpServerResponse("application/json", QByteArrayLiteral(R"({"ok":true})"));
    });

    m_server->route("/v1/status", [this](const QHttpServerRequest& req) {
        if (!authorized(req))
            return QHttpServerResponse("application/json",
                                       QByteArrayLiteral(R"({"error":"unauthorized"})"),
                                       QHttpServerResponder::StatusCode::Unauthorized);
        return QHttpServerResponse("application/json", m_probe->statusJson());
    });

    m_tcp = new QTcpServer(this);
    // Loopback ONLY. The onion is the sole external path; binding Any would publish the
    // node's control surface to the LAN.
    if (!m_tcp->listen(QHostAddress::LocalHost, 0) || !m_server->bind(m_tcp)) {
        delete m_tcp;  m_tcp = nullptr;
        delete m_server; m_server = nullptr;
        return 0;
    }
    m_port = m_tcp->serverPort();
    return m_port;
}

void HttpSurface::stop()
{
    if (m_server) { m_server->deleteLater(); m_server = nullptr; }
    if (m_tcp)    { m_tcp->close(); m_tcp->deleteLater(); m_tcp = nullptr; }
    m_port = 0;
}
