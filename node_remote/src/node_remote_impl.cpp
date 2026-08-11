#include "node_remote_impl.h"

#include "http_surface.h"
#include "node_probe.h"
#include "onion_service.h"

// The typed wrapper for blockchain_module. Generated at build time from the flake input
// whose attribute name matches the dependency string EXACTLY (see flake.nix) — there is
// no prebuilt SDK header for this module.
#include "logos_sdk.h"

#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>

namespace {
std::string dump(const QJsonObject& o)
{
    return QJsonDocument(o).toJson(QJsonDocument::Compact).toStdString();
}
}  // namespace

// The probe is owned here and outlives both the HTTP surface and the onion.
static NodeProbe* g_probe = nullptr;

NodeRemoteImpl::NodeRemoteImpl()
{
    if (!g_probe) g_probe = new NodeProbe();
    m_onion = new OnionService();
    m_http  = new HttpSurface(g_probe);

    QObject::connect(m_onion, &OnionService::ready, m_onion, [this](const QString& a) {
        onionReady(a.toStdString());
    });
    QObject::connect(m_onion, &OnionService::failed, m_onion, [this](const QString& c) {
        onionFailed(c.toStdString());
    });

    // Wire the control routes. HttpSurface deliberately knows nothing about
    // blockchain_module — it just calls these.
    m_http->setStartHandler([this] {
        return QByteArray::fromStdString(startNode("", "logos.test"));
    });
    m_http->setStopHandler([this] {
        return QByteArray::fromStdString(stopNode());
    });
}

NodeRemoteImpl::~NodeRemoteImpl()
{
    if (m_onion) { m_onion->stop(); delete m_onion; m_onion = nullptr; }
    if (m_http)  { m_http->stop();  delete m_http;  m_http  = nullptr; }
}

std::string NodeRemoteImpl::startRemote()
{
    QJsonObject r;
    m_port = m_http->listen();
    if (m_port == 0) {
        r["ok"] = false;
        r["error"] = "http_bind_failed";
        return dump(r);
    }
    const QString err = m_onion->start(m_port);
    if (!err.isEmpty()) {
        m_http->stop();
        m_port = 0;
        r["ok"] = false;
        r["error"] = err;
        return dump(r);
    }
    r["ok"] = true;
    r["port"] = m_port;
    // Deliberately no onion here — the descriptor is not published yet. Handing out a
    // pairing QR now would burn its validity window waiting for the upload.
    r["note"] = "onion publishing; poll getRemoteInfo() until ready";
    return dump(r);
}

std::string NodeRemoteImpl::stopRemote()
{
    m_onion->stop();
    m_http->stop();
    m_port = 0;
    QJsonObject r;
    r["ok"] = true;
    return dump(r);
}

std::string NodeRemoteImpl::getRemoteInfo()
{
    QJsonObject r;
    r["running"] = m_port != 0;
    r["ready"]   = m_onion->isReady();
    r["onion"]   = m_onion->onion();
    r["port"]    = static_cast<int>(m_port);
    r["error"]   = m_onion->lastError();
    QJsonArray cl;
    for (const QString& c : m_onion->authorizedClients()) cl.append(c);
    r["clients"] = cl;
    return dump(r);
}

std::string NodeRemoteImpl::getNodeStatus()
{
    return g_probe->statusJson().toStdString();
}

std::string NodeRemoteImpl::startNode(const std::string& configPath,
                                      const std::string& deployment)
{
    QJsonObject r;
    // Fall back to the same config the desktop UI uses, so a phone-initiated start
    // brings up the SAME node the user already configured rather than a default one.
    const std::string cfg = configPath.empty()
                                ? g_probe->userConfigPath().toStdString()
                                : configPath;
    if (cfg.empty()) {
        r["ok"] = false;
        r["error"] = "no user_config.yaml found — configure the node on the desktop first";
        return dump(r);
    }

    const StdLogosResult res = modules().blockchain_module.start(cfg, deployment);
    r["ok"] = res.success;
    if (!res.success) r["error"] = QString::fromStdString(res.error);
    r["configPath"] = QString::fromStdString(cfg);
    return dump(r);
}

std::string NodeRemoteImpl::stopNode()
{
    const StdLogosResult res = modules().blockchain_module.stop();
    QJsonObject r;
    r["ok"] = res.success;
    if (!res.success) r["error"] = QString::fromStdString(res.error);
    return dump(r);
}

std::string NodeRemoteImpl::authorizeClient(const std::string& name,
                                            const std::string& x25519PubBase32)
{
    QJsonObject r;
    r["ok"] = m_onion->authorizeClient(QString::fromStdString(name),
                                       QString::fromStdString(x25519PubBase32));
    return dump(r);
}

std::string NodeRemoteImpl::revokeClient(const std::string& name)
{
    QJsonObject r;
    r["ok"] = m_onion->revokeClient(QString::fromStdString(name));
    return dump(r);
}

std::string NodeRemoteImpl::regenerateOnion()
{
    m_onion->regenerateAddress();
    QJsonObject r;
    r["ok"] = true;
    return dump(r);
}
