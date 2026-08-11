#include "node_probe.h"

#include <QDir>
#include <QEventLoop>
#include <QFile>
#include <QFileInfo>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QSettings>
#include <QStandardPaths>
#include <QTextStream>
#include <QTimer>

NodeProbe::NodeProbe(QObject* parent) : QObject(parent) {}

QString NodeProbe::userConfigPath() const
{
    // logos-blockchain-ui persists this with an explicit org/app, so the file is
    // ~/.config/Logos/BlockchainUI.conf and readable from any process in the session.
    QSettings s(QStringLiteral("Logos"), QStringLiteral("BlockchainUI"));
    const QString p = s.value(QStringLiteral("userConfigPath")).toString();
    if (!p.isEmpty() && QFileInfo::exists(p)) return p;

    // Fallback: the user may never have opened the Blockchain tab. Take the newest
    // generated config under the module's data dir.
    const QString base = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation)
                         + "/module_data/blockchain_module";
    QDir d(base);
    QString best;
    QDateTime bestT;
    for (const QString& sub : d.entryList(QDir::Dirs | QDir::NoDotAndDotDot)) {
        const QString cand = base + "/" + sub + "/user_config.yaml";
        QFileInfo fi(cand);
        if (!fi.exists()) continue;
        if (best.isEmpty() || fi.lastModified() > bestT) { best = cand; bestT = fi.lastModified(); }
    }
    return best;
}

QString NodeProbe::apiBase() const
{
    const QByteArray env = qgetenv("NODE_REMOTE_API_BASE");
    if (!env.isEmpty()) return QString::fromLocal8Bit(env);
    if (!m_cachedBase.isEmpty()) return m_cachedBase;

    // Parse api.backend.listen_address out of the node's YAML. A real YAML parser is
    // overkill for one nested scalar and would add a dependency; we walk indentation.
    const QString cfg = userConfigPath();
    QFile f(cfg);
    if (f.open(QIODevice::ReadOnly)) {
        QTextStream in(&f);
        bool inApi = false, inBackend = false;
        while (!in.atEnd()) {
            const QString raw = in.readLine();
            const QString t = raw.trimmed();
            if (t.isEmpty() || t.startsWith('#')) continue;

            // Indentation of the first non-space character tells us the nesting level.
            int lead = 0;
            while (lead < raw.size() && raw.at(lead).isSpace()) ++lead;

            if (lead == 0) {                       // a top-level key resets the scope
                inApi = t.startsWith(QLatin1String("api:"));
                inBackend = false;
                continue;
            }
            if (!inApi) continue;
            if (t.startsWith(QLatin1String("backend:"))) { inBackend = true; continue; }
            if (inBackend && t.startsWith(QLatin1String("listen_address:"))) {
                // Value is host:port, so take everything after the FIRST colon —
                // section(':', 1) would truncate at the port separator.
                QString v = t.mid(t.indexOf(':') + 1).trimmed();
                v.remove('"').remove('\'');
                if (!v.isEmpty()) {
                    m_cachedBase = v.startsWith(QLatin1String("http")) ? v : "http://" + v;
                    return m_cachedBase;
                }
            }
        }
        f.close();
    }
    m_cachedBase = QStringLiteral("http://127.0.0.1:8080");
    return m_cachedBase;
}

QByteArray NodeProbe::get(const QString& url, int timeoutMs, bool* ok)
{
    if (ok) *ok = false;
    QNetworkRequest req{QUrl(url)};
    req.setTransferTimeout(timeoutMs);
    QNetworkReply* r = m_nam.get(req);

    QEventLoop loop;
    QTimer guard;
    guard.setSingleShot(true);
    QObject::connect(&guard, &QTimer::timeout, &loop, &QEventLoop::quit);
    QObject::connect(r, &QNetworkReply::finished, &loop, &QEventLoop::quit);
    guard.start(timeoutMs + 250);
    loop.exec();

    QByteArray body;
    if (r->isFinished() && r->error() == QNetworkReply::NoError) {
        body = r->readAll();
        if (ok) *ok = true;
    }
    r->deleteLater();
    return body;
}

QByteArray NodeProbe::statusJson()
{
    QJsonObject out;
    out["apiBase"] = apiBase();
    out["configPath"] = userConfigPath();

    bool ok = false;
    const QByteArray info = get(apiBase() + "/cryptarchia/info", 2000, &ok);
    if (!ok) {
        // Honest failure: distinguish "node not running" from "we have no idea".
        out["reachable"] = false;
        out["status"] = "NotRunning";
        out["error"] = "node API unreachable";
        return QJsonDocument(out).toJson(QJsonDocument::Compact);
    }

    out["reachable"] = true;

    // Verified live shape (node v0.2.1, 2026-08-11):
    //   {"cryptarchia_info":{"lib","lib_slot","tip","slot","height","state"},"phase":"Following"}
    // The consensus fields are NESTED under cryptarchia_info — reading them off the root
    // silently yields nulls, which is what a first pass here did. There is no "mode" key;
    // liveness is "state" ("Online" / "Bootstrapping") and "phase" ("Following").
    const QJsonObject root = QJsonDocument::fromJson(info).object();
    const QJsonObject ci   = root.value(QStringLiteral("cryptarchia_info")).toObject();

    out["slot"]    = ci.value("slot");
    out["height"]  = ci.value("height");
    out["tip"]     = ci.value("tip");
    out["lib"]     = ci.value("lib");
    out["libSlot"] = ci.value("lib_slot");
    out["state"]   = ci.value("state");            // "Online" | "Bootstrapping"
    out["phase"]   = root.value("phase");          // e.g. "Following"

    // Coarse status the phone renders as a pill. Derived, not invented: if the API answered
    // at all the node is up; "Online" means it is actually following the chain.
    const QString state = ci.value("state").toString();
    out["status"] = state.compare(QLatin1String("Online"), Qt::CaseInsensitive) == 0
                        ? QStringLiteral("Running")
                        : (state.isEmpty() ? QStringLiteral("Starting") : state);

    bool netOk = false;
    const QByteArray net = get(apiBase() + "/network/info", 1500, &netOk);
    if (netOk) {
        const QJsonObject n = QJsonDocument::fromJson(net).object();
        out["peers"]       = n.value("n_peers");
        out["connections"] = n.value("n_connections");
        out["peerId"]      = n.value("peer_id");
    }

    return QJsonDocument(out).toJson(QJsonDocument::Compact);
}
