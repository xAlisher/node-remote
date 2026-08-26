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
#include <QRegularExpression>
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

QString NodeProbe::deploymentConfigPath() const
{
    // Same store logos-blockchain-ui writes deploymentConfig() to. Empty is the normal
    // testnet case (default embedded deployment) — return it verbatim, empty and all.
    QSettings s(QStringLiteral("Logos"), QStringLiteral("BlockchainUI"));
    return s.value(QStringLiteral("deploymentConfigPath")).toString();
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

// Marks a lastNodeError() result that describes a node WORKING, not failing. Kept as a
// sentinel rather than a second function so the single newest-first log scan still decides
// — two scans could disagree about which line is most recent.
static const QString kNoticePrefix = QStringLiteral("NOTICE:");

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

        // NOT an error — the node is replaying stored blocks. Prefixed so the caller can
        // tell it apart from the failures below without re-matching the log itself. It was
        // returned as a plain string like every other entry, so the phone rendered a healthy
        // recovering node in a red error card AND offered "Start node" for a node that was
        // already running.
        if (has(ln, "blocks to replay") || has(ln, "Chain recovery") || has(ln, "recovering chain state"))
            // Same wording 1-click puts under its status block (NodeDashboardView _subStatusText),
            // so the desktop and the phone describe the same moment identically.
            return kNoticePrefix + QStringLiteral("Replaying stored blocks…");

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

// Intent lives in the SAME QSettings logos-blockchain-ui uses (see userConfigPath()), so
// a Start/Stop on either surface is visible to the other. Single key, single node: keyed
// per-instance would be needed only once we run more than one node from one desktop.
static const QString kIntentKey = QStringLiteral("nodeIntent");

NodeProbe::Intent NodeProbe::readIntent() const
{
    QSettings s(QStringLiteral("Logos"), QStringLiteral("BlockchainUI"));
    const QString v = s.value(kIntentKey).toString();
    if (v == QLatin1String("started")) return Intent::Started;
    if (v == QLatin1String("stopped")) return Intent::Stopped;
    return Intent::Unknown;
}

void NodeProbe::writeIntent(Intent in) const
{
    if (in == Intent::Unknown) return;
    const QString v = (in == Intent::Started) ? QStringLiteral("started")
                                              : QStringLiteral("stopped");
    QSettings s(QStringLiteral("Logos"), QStringLiteral("BlockchainUI"));
    if (s.value(kIntentKey).toString() == v) return;   // no per-poll write churn
    s.setValue(kIntentKey, v);
}

NodeProbe::Recovery NodeProbe::recoveryStatus() const
{
    // Ported from logos_node_1click_backend::getRecoveryStatus() so the desktop and the
    // phone report the same block count. Newest marker wins: a completion after a start
    // means recovery is done.
    Recovery out;
    const QString cfg = userConfigPath();
    if (cfg.isEmpty()) return out;
    const QDir logsDir(QFileInfo(cfg).absoluteDir().filePath(QStringLiteral("logs")));
    if (!logsDir.exists()) return out;
    const QFileInfoList files = logsDir.entryInfoList(QDir::Files, QDir::Time);
    if (files.isEmpty()) return out;

    QFile f(files.first().absoluteFilePath());
    if (!f.open(QIODevice::ReadOnly | QIODevice::Text)) return out;
    const qint64 tail = qMin<qint64>(f.size(), 256 * 1024);
    f.seek(f.size() - tail);
    const QStringList lines = QString::fromUtf8(f.readAll()).split(QLatin1Char('\n'));
    f.close();

    static const QRegularExpression reFound(
        QStringLiteral("found (\\d+) stored blocks to replay"));
    for (int i = lines.size() - 1; i >= 0; --i) {
        const QString& ln = lines.at(i);
        if (ln.contains(QStringLiteral("Chain recovery finished"))
            || ln.contains(QStringLiteral("blocks replayed")))
            return out;   // completion is the most recent marker → done
        const QRegularExpressionMatch m = reFound.match(ln);
        if (m.hasMatch()) {
            out.active = true;
            out.blocks = m.captured(1).toInt();
            return out;
        }
    }
    return out;
}

QByteArray NodeProbe::statusJson()
{
    QJsonObject out;
    out["apiBase"] = apiBase();
    out["configPath"] = userConfigPath();

    bool ok = false;
    const QByteArray info = get(apiBase() + "/cryptarchia/info", 2000, &ok);
    if (!ok) {
        // The node's API is not answering. What that MEANS depends on intent (did the user
        // ask for it up?) and on whether the node is alive-but-replaying. Only a node that
        // was expected up and is genuinely failing gets an error — this is where the
        // "deploy config on stop" bug lived (a clean stop inherited a stale log line).
        out["reachable"] = false;
        const Intent intent = readIntent();
        out["intent"] = (intent == Intent::Started) ? "started"
                        : (intent == Intent::Stopped) ? "stopped"
                                                      : "unknown";

        const Recovery rec = recoveryStatus();
        const QString honest = lastNodeError();

        if (intent == Intent::Stopped) {
            // INTENT WINS. Checked FIRST, ahead of recovery, because recovery detection is
            // a LOG SCRAPE and a log line outlives the state it describes: after a stop the
            // "…stored blocks to replay…" lines from the previous startup are still sitting
            // in the tail, so a stopped node reported "Recovering chain" with the phone
            // offering "Stop node". Observed live — status "Recovering" with
            // "blocks": 0 (the zero count is the tell: nothing is actually replaying)
            // while intent was already "stopped".
            //
            // This is the same defect the intent latch was introduced to kill; recovery was
            // simply left as an ungated scrape. A node the user asked to stop is stopped —
            // it cannot be recovering, and it has no error.
            out["status"] = "Stopped";
        } else if (rec.active || honest.startsWith(kNoticePrefix)) {
            // Alive, replaying stored blocks before the API comes up. A DISTINCT state from
            // a plain start — the phone shows "Recovering chain" only here, with the count.
            out["status"] = "Recovering";
            QJsonObject r;
            r["active"] = true;
            r["blocks"] = rec.blocks;
            out["recovering"] = r;
            out["notice"] = rec.blocks > 0
                ? QStringLiteral("Replaying %1 stored blocks…").arg(rec.blocks)
                : QStringLiteral("Replaying stored blocks…");
        } else if (intent == Intent::Started && !honest.isEmpty()) {
            // Expected up, down with an actionable cause. The ONLY path that surfaces the
            // error table.
            out["status"] = "Error";
            out["error"] = honest;
        } else if (intent == Intent::Started) {
            // Expected up, no fatal log yet — coming up.
            out["status"] = "Starting";
        } else {
            // Unknown intent (e.g. stopped from the desktop before it wrote the shared
            // intent — see logos-blockchain-ui#40). Degrade to neutral, never invent an
            // error. A real recovery/bootstrap will correct this on the next poll.
            out["status"] = "Stopped";
        }
        return QJsonDocument(out).toJson(QJsonDocument::Compact);
    }

    out["reachable"] = true;
    // The node answered → it is up. Self-heal the shared intent so a node started on the
    // desktop (or that recovered on its own) reads as "started" on both surfaces even
    // before logos-blockchain-ui#40 lands.
    writeIntent(Intent::Started);
    out["intent"] = "started";

    // Verified live shape (node v0.2.1, 2026-08-11; re-verified UNCHANGED on module
    // 0.2.3, 2026-08-26, live against wild):
    //   {"cryptarchia_info":{"lib","lib_slot","tip","slot","height","state"},"phase":"Following"}
    // The consensus fields are NESTED under cryptarchia_info — reading them off the root
    // silently yields nulls, which is what a first pass here did. There is no "mode" key;
    // liveness is "state" ("Online" / "Bootstrapping") and "phase" ("Following").
    //
    // NOTE the `slot` here is the TIP's slot, not the wall clock: during IBD it is the
    // replay position, hours behind real time (logos-blockchain-ui#51). Wall-clock slot
    // and epoch come from /time/info below — never derive time from this field.
    const QJsonObject root = QJsonDocument::fromJson(info).object();
    const QJsonObject ci   = root.value(QStringLiteral("cryptarchia_info")).toObject();

    out["slot"]    = ci.value("slot");
    out["height"]  = ci.value("height");
    out["tip"]     = ci.value("tip");
    out["lib"]     = ci.value("lib");
    out["libSlot"] = ci.value("lib_slot");
    out["state"]   = ci.value("state");            // "Online" | "Bootstrapping"
    out["phase"]   = root.value("phase");          // e.g. "Following"

    // Wall-clock time base (module 0.2.3, verified live 2026-08-26):
    //   /time/info = {"slot_duration_ms","genesis_time_unix_ms","current_slot","current_epoch"}
    // This is what the phone needs for the epoch display and the "auto-claims at epoch
    // start · next in ~X" countdown (node-remote#18): epochs are exactly 36000 slots and
    // the desktop's scheduler claims in the first 600 s of each. Absent on 0.2.2 nodes —
    // the fields are simply omitted then, and the phone must degrade to not showing the
    // countdown rather than computing one from the tip slot.
    {
        bool tOk = false;
        const QByteArray t = get(apiBase() + "/time/info", 1500, &tOk);
        if (tOk) {
            const QJsonObject ti = QJsonDocument::fromJson(t).object();
            if (ti.contains(QStringLiteral("current_slot"))) {
                out["clockSlot"]      = ti.value("current_slot");
                out["currentEpoch"]   = ti.value("current_epoch");
                out["slotDurationMs"] = ti.value("slot_duration_ms");
                out["genesisTimeMs"]  = ti.value("genesis_time_unix_ms");
            }
        }
    }

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
    // Blend, following logos_node_1click_backend::refreshBlendStatus() rather than asking
    // /blend/info first and calling a non-answer "Unknown".
    //
    // Blend CANNOT run until the chain is Online — 1-click's own comment says so, and its
    // WaitingForOnline state renders as "Blend inactive" (gray), not "unknown". Ours asked
    // the API unconditionally, and while Bootstrapping that API does not answer, so every
    // syncing node reported "Unknown" — which reads as a fault when nothing is wrong. The
    // node is simply not blending yet, and that is a fact we KNOW, not one we are missing.
    //
    // "Unknown" is now reserved for its real meaning: the node is Online, so blend should
    // be answering, and it did not.
    if (state.compare(QLatin1String("Online"), Qt::CaseInsensitive) != 0) {
        out["blendStatus"] = QStringLiteral("Inactive");
        out["blendNote"]   = QStringLiteral("waiting for the node to reach Online");
    } else {
        bool blendOk = false;
        const QByteArray blend = get(apiBase() + "/blend/info", 1500, &blendOk);
        if (blendOk) {
            const QJsonObject b = QJsonDocument::fromJson(blend).object();
            const QJsonValue core = b.value(QStringLiteral("core_info"));
            if (core.isNull() || core.isUndefined()) {
                // Online and not core = edge by default, exactly as 1-click concludes.
                out["blendStatus"] = QStringLiteral("Edge");
                out["blendPeers"]  = 0;
                out["blendNote"]   = QStringLiteral("your proposals are being mixed");
            } else {
                const QJsonArray peers =
                    core.toObject().value(QStringLiteral("current_epoch_peers")).toArray();
                out["blendStatus"] = QStringLiteral("Core");
                out["blendPeers"]  = peers.size();
                out["blendNote"]   = QStringLiteral("%1 mix peers this epoch").arg(peers.size());
            }
            out["blendNodeId"] = b.value(QStringLiteral("node_id"));
        } else {
            out["blendStatus"] = QStringLiteral("Unknown");
        }
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
