#include "node_probe.h"

#include <QDir>
#include <QEventLoop>
#include <QFile>
#include <QFileInfo>
#include <QJsonDocument>
#include <QJsonArray>
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

QString NodeProbe::lastNodeError() const
{
    // Ported from logos_node_1click_backend.cpp:36-120. The value of this table is that
    // every entry is ACTIONABLE — "the disk is full" tells you what to do, whereas the raw
    // log line it came from does not. Order matters: recovery is checked FIRST because it
    // is not a failure at all, just a slow start.
    const QString cfg = userConfigPath();
    if (cfg.isEmpty()) return {};
    const QDir logsDir(QFileInfo(cfg).absoluteDir().filePath(QStringLiteral("logs")));
    if (!logsDir.exists()) return {};
    const QFileInfoList files = logsDir.entryInfoList(QDir::Files, QDir::Time);
    if (files.isEmpty()) return {};

    QFile f(files.first().absoluteFilePath());
    if (!f.open(QIODevice::ReadOnly | QIODevice::Text)) return {};
    const qint64 tail = qMin<qint64>(f.size(), 128 * 1024);
    f.seek(f.size() - tail);
    const QStringList lines = QString::fromUtf8(f.readAll()).split(QLatin1Char('\n'));
    f.close();

    auto has = [](const QString& l, const char* s) {
        return l.contains(QLatin1String(s), Qt::CaseInsensitive);
    };

    for (int i = lines.size() - 1; i >= 0; --i) {
        const QString& ln = lines.at(i);

        // NOT an error — the node is replaying stored blocks.
        if (has(ln, "blocks to replay") || has(ln, "Chain recovery") || has(ln, "recovering chain state"))
            return QStringLiteral("The node is replaying stored blocks to catch up — this can take a few minutes.");

        if (has(ln, "crashed (signal") || has(ln, "panicked") || has(ln, "SIGABRT") || has(ln, "SIGSEGV"))
            return QStringLiteral("The node process crashed. Wipe the database and start over to recover.");

        if (has(ln, "Storage backend error") || has(ln, "from storage") || has(ln, "Storage request failed"))
            return QStringLiteral("The chain database is in a bad state. Wipe the database and start over.");

        if (has(ln, "No locks available") || has(ln, "Resource temporarily unavailable") || has(ln, "IO error"))
            return QStringLiteral("The database is locked (another node may be running) or the disk had an I/O error.");

        if (has(ln, "No space left") || has(ln, "ENOSPC"))
            return QStringLiteral("The disk is full — free up space, then wipe and start over.");

        if (has(ln, "AddrInUse") || has(ln, "address already in use") || has(ln, "EADDRINUSE")
            || has(ln, "failed to bind"))
            return QStringLiteral("A required network port is already in use — another node may still be running.");

        if (has(ln, "genesis") && (has(ln, "mismatch") || has(ln, "does not match")))
            return QStringLiteral("This database is from a different network (genesis mismatch).");

        if (has(ln, "AllPeersFailed") || has(ln, "does not support")
            || (has(ln, "protocol") && has(ln, "mismatch")))
            return QStringLiteral("Couldn't sync from the configured peers (unreachable, or a different network).");

        if (has(ln, "missing field") || has(ln, "invalid type") || has(ln, "failed to parse")
            || has(ln, "deserialize"))
            return QStringLiteral("The node config couldn't be parsed. Regenerate it on the desktop.");
    }
    return {};
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
        // Ask the node's own log WHY instead of reporting a generic unreachable. A stopped
        // node is a normal state, so only attach an error when the log actually explains
        // a failure — otherwise the phone shows "Not Started", which is the truth.
        const QString honest = lastNodeError();
        if (!honest.isEmpty()) out["error"] = honest;
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

    // Blend: /blend/info tells us whether this node is a Core (mix-relaying) node.
    // core_info is null for an Edge node — that is normal, not an error, and the 1-click
    // module's vocabulary for it is Edge vs Core (BlendStatus enum:
    // Off/WaitingForOnline/Edge/Core/Broadcast/NodeError/BlendError/Unknown).
    bool blendOk = false;
    const QByteArray blend = get(apiBase() + "/blend/info", 1500, &blendOk);
    if (blendOk) {
        const QJsonObject b = QJsonDocument::fromJson(blend).object();
        const QJsonValue core = b.value(QStringLiteral("core_info"));
        if (core.isNull() || core.isUndefined()) {
            out["blendStatus"] = QStringLiteral("Edge");
            out["blendPeers"]  = 0;
        } else {
            const QJsonArray peers =
                core.toObject().value(QStringLiteral("current_epoch_peers")).toArray();
            out["blendStatus"] = QStringLiteral("Core");
            out["blendPeers"]  = peers.size();
        }
        out["blendNodeId"] = b.value(QStringLiteral("node_id"));
    } else {
        out["blendStatus"] = QStringLiteral("Unknown");
    }

    // Peers/connections use logos_node_1click's EXACT derivation
    // (logos_node_1click_backend.cpp:446-471) so both surfaces report the same numbers
    // from the same payload:
    //   peers       = n_peers, else connected_peers.length, else -1
    //   connections = n_connections, else FALL BACK TO peers
    // The -1 sentinel matters: it means "unknown", which is not the same as zero peers.
    int peers = -1, connections = -1;
    bool netOk = false;
    const QByteArray net = get(apiBase() + "/network/info", 1500, &netOk);
    if (netOk) {
        const QJsonObject n = QJsonDocument::fromJson(net).object();
        if (n.contains(QStringLiteral("n_peers")))
            peers = n.value(QStringLiteral("n_peers")).toInt();
        else if (n.contains(QStringLiteral("connected_peers")))
            peers = n.value(QStringLiteral("connected_peers")).toArray().size();

        connections = n.contains(QStringLiteral("n_connections"))
                          ? n.value(QStringLiteral("n_connections")).toInt()
                          : peers;

        out["peerId"] = n.value("peer_id");
    }
    out["peers"]       = peers;
    out["connections"] = connections;

    return QJsonDocument(out).toJson(QJsonDocument::Compact);
}
