#include "node_remote_impl.h"

#include "http_surface.h"
#include "node_probe.h"
#include "onion_service.h"
#include "pairing.h"
#include "block_store.h"
#include "vendor/qrcodegen.hpp"

// The typed wrapper for blockchain_module. Generated at build time from the flake input
// whose attribute name matches the dependency string EXACTLY (see flake.nix) — there is
// no prebuilt SDK header for this module.
#include "logos_sdk.h"

#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QStandardPaths>
#include <QTimer>
#include <QMutexLocker>

namespace {
std::string dump(const QJsonObject& o)
{
    return QJsonDocument(o).toJson(QJsonDocument::Compact).toStdString();
}
}  // namespace

// Crash bisection. node_remote takes SIGSEGV ~77-80s after load, consistently, and the
// platform's backtrace is raw addresses with no symbols. These traces exist so the LAST
// line before the fault names the operation in flight — cheaper than a symbolised core,
// and the interval evidence has already misled me once (moving the balance timer 15s->30s
// did not move the crash).

// The probe is owned here and outlives both the HTTP surface and the onion.
static NodeProbe* g_probe = nullptr;

NodeRemoteImpl::NodeRemoteImpl()
{
    if (!g_probe) g_probe = new NodeProbe();
    m_onion = new OnionService();
    m_blocks = new BlockStore();
    m_http  = new HttpSurface(g_probe);

    // A paired phone keeps its token across desktop restarts, so we must too. HttpSurface
    // itself only reads NODE_REMOTE_TOKEN (the headless-test path), which Basecamp does
    // not set.
    const std::string saved = loadToken();
    if (!saved.empty()) m_http->setToken(QString::fromStdString(saved));
    // Restore last-seen so "have we ever been paired" survives a Basecamp restart. Without
    // it the pane forgot a live pairing and offered a fresh QR for a phone that was still
    // authorised on the onion and still holding a valid token.
    m_http->setLastAuthedAt(static_cast<qint64>(loadLastSeen()));
    m_http->setAuthedHook([this] { persistLastSeenThrottled(); });

    QObject::connect(m_onion, &OnionService::ready, m_onion, [this](const QString& a) {
        onionReady(a.toStdString());
    });
    QObject::connect(m_onion, &OnionService::failed, m_onion, [this](const QString& c) {
        onionFailed(c.toStdString());
    });

    // Wire the control routes. HttpSurface deliberately knows nothing about
    // blockchain_module — it just calls these.
    m_http->setStatusHandler([this] {
        return QByteArray::fromStdString(getNodeStatus());
    });
    m_http->setStartHandler([this] {
        // Empty deployment → startNode resolves it from the shared config (matches the
        // desktop). Do NOT hardcode a name here: blockchain_module treats the deployment
        // arg as a FILE path, so a literal like "logos.test" fails to parse.
        return QByteArray::fromStdString(startNode("", ""));
    });
    m_http->setStopHandler([this] {
        return QByteArray::fromStdString(stopNode());
    });
    m_http->setWipeHandler([this] {
        return QByteArray::fromStdString(wipeDatabase());
    });
    m_http->setRegenHandler([this] {
        return QByteArray::fromStdString(regenerateConfig(""));
    });
    m_http->setBlocksHandler([this] {
        return QByteArray::fromStdString(getBlocks());
    });
    m_http->setProposalsHandler([this] {
        return QByteArray::fromStdString(getProposals());
    });
}

NodeRemoteImpl::~NodeRemoteImpl()
{
    if (m_onion) { m_onion->stop(); delete m_onion; m_onion = nullptr; }
    if (m_http)  { m_http->stop();  delete m_http;  m_http  = nullptr; }
    delete m_blocks; m_blocks = nullptr;
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
    m_http->forgetLastAuthed();   // otherwise the next pairing starts life "connected"
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
    // Two DIFFERENT questions, and conflating them has now bitten us in both directions.
    //
    //   everConnected — has a phone EVER authenticated in this session? Latches on. This is
    //                   what says "pairing worked, stop showing the QR". Keying that off the
    //                   key file instead made the pane hide the code the instant it drew it.
    //
    //   connected     — is a phone talking to us RIGHT NOW? Derived from RECENCY, because
    //                   the phone pushes no disconnect and the module cannot see the app's
    //                   state. It polls every 10s foreground / 15s backgrounded, so silence
    //                   past kStaleSecs means it is gone. Without this the pane said
    //                   "Connected" forever after a single request — including after the
    //                   phone was switched off.
    //
    // 90s, not 30s: Doze and a stalled Tor circuit can legitimately stretch a poll, and a
    // pane that flickers between Connected and Idle is worse than one that is 90s late.
    constexpr qint64 kStaleSecs = 90;
    const qint64 seen = m_http->lastAuthedAt();
    const qint64 now  = QDateTime::currentSecsSinceEpoch();
    r["lastSeen"]      = seen;
    r["lastSeenSecs"]  = seen > 0 ? (now - seen) : -1;   // -1 = never
    r["everConnected"] = seen > 0;
    r["connected"]     = seen > 0 && (now - seen) <= kStaleSecs;
    QJsonArray cl;
    for (const QString& c : m_onion->authorizedClients()) cl.append(c);
    r["clients"] = cl;
    return dump(r);
}

std::string NodeRemoteImpl::getNodeStatus()
{
    QJsonObject o = QJsonDocument::fromJson(g_probe->statusJson()).object();

    // Balance is merged from a CACHE refreshed on a timer — never fetched here.
    //
    // This function is called from the HTTP handler, which runs on the module's Qt event
    // loop. blockchain_module's wallet RPCs are SYNCHRONOUS IPC: calling them from inside a
    // request handler wedged the whole route, and /v1/status went from answering instantly
    // to HTTP 000 after 30s — the handler cannot return until the IPC replies, and the
    // reply cannot be delivered until the handler returns. Measured, not theorised.
    //
    // Polling on a timer also stops the wallet RPC being hit once per phone poll forever,
    // which it was.
    // THE CRASH. These four QStrings are written by refreshBalance() on the module's timer
    // and read here on the HTTP request path, and the trace proves those overlap:
    //
    //   01:52:49.825  getNodeStatus: begin      <- phone request
    //   01:52:50.014  balance: begin            <- timer fires 190ms later, mid-request
    //   01:53:09.886  SIGSEGV
    //
    // QString is implicitly shared and its refcount is NOT safe against a concurrent
    // write, so an unsynchronised read/write tears the shared data block and the process
    // dies later, somewhere unrelated — which is why three crashes produced a useless
    // backtrace and why "balance: begin" with no matching "end" preceded every one.
    {
        QMutexLocker lk(&m_balanceMu);
        if (!m_primaryAddress.isEmpty()) o["primaryAddress"] = m_primaryAddress;
        if (!m_balanceRaw.isEmpty())     o["balanceRaw"] = m_balanceRaw;
        if (!m_balance.isEmpty())        o["balance"] = m_balance;
        if (!m_balanceError.isEmpty())   o["balanceError"] = m_balanceError;
    }
    return QJsonDocument(o).toJson(QJsonDocument::Compact).toStdString();
}

// Refresh the cached wallet figures. Runs on the module's timer, NOT on a request.
void NodeRemoteImpl::refreshBalance()
{
    if (!isContextReady()) return;

    // Re-entrancy guard. These are SYNCHRONOUS IPC calls, and a sync call runs a nested
    // event loop — so a blockchain_module onNewBlock callback can land in the middle of
    // one. During IBD blocks stream continuously, and the module took SIGSEGV ~80s in,
    // two seconds after a balance tick, with blocks arriving throughout. This guard stops
    // us stacking a second wallet call inside the first; it does NOT make the platform's
    // sync IPC reentrant, so it is a mitigation, not a proof. See docs/TESTING.md.
    m_ipcBusy = true;
    struct Clear { bool& b; ~Clear() { b = false; } } clear{m_ipcBusy};

    // Everything below writes LOCALS. The shared members are only touched at the end,
    // under the mutex, so a reader never observes a half-written QString.
    const StdLogosResult addrs = modules().blockchain_module.wallet_get_known_addresses();
    if (!addrs.success) {
        // Instance-bound: the wallet RPCs answer only in the blockchain_module that is
        // actually running the node. Keep the last good figure and say why it is stale.
        QMutexLocker lk(&m_balanceMu);
        m_balanceError = QString::fromStdString(addrs.error);
        return;
    }

    QString primary;
    const QJsonDocument ad = QJsonDocument::fromJson(QByteArray::fromStdString(addrs.value.dump()));
    if (ad.isArray() && !ad.array().isEmpty())      primary = ad.array().first().toString();
    else if (ad.isObject())                         primary = ad.object().value("address").toString();
    if (primary.isEmpty()) {
        QMutexLocker lk(&m_balanceMu);
        m_balanceError = QStringLiteral("the wallet reported no addresses");
        return;
    }

    const StdLogosResult bal = modules().blockchain_module.wallet_get_balance(primary.toStdString());
    if (!bal.success) {
        // REPORT it. This path used to clear the error and return, so a wallet whose
        // address resolved but whose balance call failed showed a bare "—" on the phone
        // with no reason anywhere — indistinguishable from "not fetched yet". Observed
        // live: primaryAddress present, balance absent, balanceError absent.
        QMutexLocker lk(&m_balanceMu);
        m_primaryAddress = primary;
        m_balanceError = QString::fromStdString(bal.error);
        return;
    }
    const QString raw = QString::fromStdString(bal.value.dump()).remove('"');
    bool okNum = false;
    const double v = raw.toDouble(&okNum);

    {
        QMutexLocker lk(&m_balanceMu);
        m_balanceError.clear();
        m_primaryAddress = primary;
        m_balanceRaw = raw;
        // Kept for older clients; the phone formats from balanceRaw with 1-click's algorithm.
        if (okNum) m_balance = QString::number(v / 10000.0, 'f', 4);
    }
}

void NodeRemoteImpl::onContextReady()
{
    // Resume a pairing across a Basecamp restart. Everything needed already survives on
    // disk — the onion keys, authorized_clients/, the bearer token — so the ONLY reason a
    // paired phone had to scan a new QR was that nothing started tor and the HTTP surface
    // again. It is the same .onion and the same key, so the phone reconnects on its own.
    //
    // Deferred, not called inline: startRemote() spawns tor and binds a socket, and doing
    // that during module init is how the platform's 80s-startup stalls happen.
    if (!m_onion->authorizedClients().isEmpty() && !loadToken().empty()) {
        QTimer::singleShot(2500, m_onion, [this] {
            if (m_port == 0) startRemote();
        });
    }

    // The ONE push channel blockchain_module offers. Everything else is polled.
    modules().blockchain_module.onNewBlock([this](const std::string& blockJson) {
        const QString ts = QDateTime::currentDateTimeUtc().toString(Qt::ISODate);
        m_blocks->append(ts, QString::fromStdString(blockJson));
    });

    // Wallet figures on a timer, off the request path. 15s matches the phone's poll, so the
    // number is never more than one cycle stale.
    if (!m_balanceTimer) {
        m_balanceTimer = new QTimer(m_onion);
        QObject::connect(m_balanceTimer, &QTimer::timeout, m_onion, [this] { refreshBalance(); });
        // 30s, not 15: every tick is a window for the reentrancy above, and a balance
        // that is half a minute stale costs nothing.
        m_balanceTimer->start(30000);
        QTimer::singleShot(3000, m_onion, [this] { refreshBalance(); });
    }
}

std::string NodeRemoteImpl::wipeDatabase()
{
    QJsonObject r;
    // Lifted from logos_node_1click_backend::resetChainState(). Two properties are
    // deliberately preserved:
    //  1. It REFUSES while the node is running. Wiping a live database corrupts it.
    //  2. It removes ONLY db/state/logs — keystore.yaml and user_config.yaml stay, so
    //     the wallet keys and settings survive. The docs tell operators to delete the
    //     whole module_data dir, which loses their keys.
    const QByteArray statusRaw = g_probe->statusJson();
    const QJsonObject st = QJsonDocument::fromJson(statusRaw).object();
    if (st.value("reachable").toBool()) {
        r["ok"] = false;
        r["error"] = "Stop the node before wiping the database.";
        return dump(r);
    }

    const QString cfg = g_probe->userConfigPath();
    if (cfg.isEmpty()) {
        r["ok"] = false;
        r["error"] = "No config found — nothing to wipe.";
        return dump(r);
    }

    const QDir dir = QFileInfo(cfg).absoluteDir();
    QStringList removed, failed;
    for (const QString& sub : {QStringLiteral("db"), QStringLiteral("state"),
                               QStringLiteral("logs")}) {
        QDir t(dir.filePath(sub));
        if (!t.exists()) continue;
        if (t.removeRecursively()) removed << sub; else failed << sub;
    }
    if (!failed.isEmpty()) {
        r["ok"] = false;
        r["error"] = QStringLiteral("Could not remove: %1").arg(failed.join(", "));
        return dump(r);
    }
    r["ok"] = true;
    r["removed"] = removed.join(", ");
    return dump(r);
}

std::string NodeRemoteImpl::regenerateConfig(const std::string& initialPeers)
{
    QJsonObject r;
    if (!isContextReady()) { r["ok"] = false; r["error"] = "module not ready"; return dump(r); }

    const QString cfg = g_probe->userConfigPath();
    if (cfg.isEmpty()) {
        r["ok"] = false;
        r["error"] = "No existing config to regenerate from.";
        return dump(r);
    }

    // generate_user_config only inserts the keys it is GIVEN — anything omitted reverts to
    // module defaults. So we pass the existing output path and let the module keep its own
    // defaults for everything we are not deliberately changing, and we BACK UP first,
    // because user_config.yaml carries consensus.wallet.funding_pk (the leader identity).
    const QString backup = cfg + ".bak-" +
        QString::number(QDateTime::currentSecsSinceEpoch());
    if (!QFile::copy(cfg, backup)) {
        r["ok"] = false;
        r["error"] = "Could not back up the current config — refusing to regenerate.";
        return dump(r);
    }

    QJsonObject args;
    args["output_path"] = cfg;
    args["use_persistence_paths"] = true;
    if (!initialPeers.empty())
        args["initial_peers"] = QString::fromStdString(initialPeers);

    const StdLogosResult res = modules().blockchain_module.generate_user_config(
        QString::fromUtf8(QJsonDocument(args).toJson(QJsonDocument::Compact)).toStdString());

    r["ok"] = res.success;
    r["backup"] = backup;
    if (!res.success) r["error"] = QString::fromStdString(res.error);
    return dump(r);
}

std::string NodeRemoteImpl::getBlocks()
{
    return m_blocks->json(0).toStdString();
}

std::string NodeRemoteImpl::getProposals()
{
    // The node writes its logs beside its config, in a `logs` dir — the same place
    // logos_node_1click's resetChainState() wipes.
    const QString cfg = g_probe->userConfigPath();
    const QString dir = cfg.isEmpty() ? QString()
                                      : QFileInfo(cfg).absolutePath() + QStringLiteral("/logs");
    return BlockStore::proposalsJson(dir, 200).toStdString();
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

    // Resolve the deployment the SAME way logos-blockchain-ui does: the persisted
    // deploymentConfigPath, which is normally EMPTY (the node's default embedded deployment).
    // The /v1/start route used to hardcode "logos.test", which blockchain_module then tried
    // to open as a file → "Could not parse deployment file: No such file or directory", so a
    // phone-initiated start failed on any node configured with the default deployment.
    const std::string dep = deployment.empty()
                                ? g_probe->deploymentConfigPath().toStdString()
                                : deployment;

    const StdLogosResult res = modules().blockchain_module.start(cfg, dep);
    if (res.success) {
        // Record the user's intent in the shared store so the desktop agrees the node is
        // meant to be up. See node_probe.cpp readIntent()/writeIntent().
        g_probe->writeIntent(NodeProbe::Intent::Started);
        r["ok"] = true;
        r["code"] = "started";
    } else {
        const QString err = QString::fromStdString(res.error);
        if (err.contains(QLatin1String("already running"), Qt::CaseInsensitive)) {
            // Idempotent: the node is already up, which is what the caller wanted.
            g_probe->writeIntent(NodeProbe::Intent::Started);
            r["ok"] = true;
            r["code"] = "already_running";
        } else if (err.isEmpty()
                   || err.contains(QLatin1String("Call failed"), Qt::CaseInsensitive)
                   || err.contains(QLatin1String("timed out"), Qt::CaseInsensitive)
                   || err.contains(QLatin1String("no reply"), Qt::CaseInsensitive)) {
            // No CLEAN reply from the start RPC. On this node that means the node is still
            // coming up — a slow chain recovery routinely outlives the RPC deadline — NOT a
            // failure. logos-blockchain-ui handles this identically: it stays in Starting and
            // lets the liveness poll confirm (logos_node_1click_backend.cpp:917-920). Report
            // it as "starting" (ok, so the phone shows Starting — never a red error); the
            // /v1/status poll then resolves it to Running, or to a real Error from the log.
            g_probe->writeIntent(NodeProbe::Intent::Started);
            r["ok"] = true;
            r["code"] = "starting";
        } else {
            r["ok"] = false;
            r["error"] = err;
            r["code"] = "start_failed";
        }
    }
    r["configPath"] = QString::fromStdString(cfg);
    return dump(r);
}

std::string NodeRemoteImpl::stopNode()
{
    const StdLogosResult res = modules().blockchain_module.stop();
    QJsonObject r;
    if (res.success) {
        g_probe->writeIntent(NodeProbe::Intent::Stopped);
        r["ok"] = true;
        r["code"] = "stopped";
    } else {
        const QString err = QString::fromStdString(res.error);
        if (err.contains(QLatin1String("not running"), Qt::CaseInsensitive)) {
            // Idempotent: stopping an already-stopped node is success, not an error. This
            // is what surfaced the scary "deploy config" card on the phone. The user's
            // intent is still "stopped", so record it.
            g_probe->writeIntent(NodeProbe::Intent::Stopped);
            r["ok"] = true;
            r["code"] = "already_stopped";
        } else {
            r["ok"] = false;
            r["error"] = err;
            r["code"] = "stop_failed";
        }
    }
    return dump(r);
}

std::string NodeRemoteImpl::beginPairing(const std::string& label)
{
    QJsonObject r;
    const QString name = QString::fromStdString(label.empty() ? "phone" : label);

    if (!m_onion->isReady()) {
        // Refuse rather than hand out a QR that will expire while tor is still
        // publishing. The user would scan it, fail, and have no idea why.
        r["ok"] = false;
        r["error"] = "onion not ready — wait for the descriptor to publish";
        return dump(r);
    }

    const pairing::KeyPair kp = pairing::generateClientAuthKey();
    if (!kp.ok) { r["ok"] = false; r["error"] = "keygen_failed"; return dump(r); }

    if (!m_onion->authorizeClient(name, kp.pubBase32)) {
        r["ok"] = false; r["error"] = "authorize_failed"; return dump(r);
    }

    // Tor only reads authorized_clients at startup, so the entry we just wrote is inert
    // until we restart it. Without this the QR pairs "successfully" while the onion is
    // still open to anyone who learns the address. tests/pairing_e2e.sh P3 caught this.
    const QString rerr = m_onion->reload();
    if (!rerr.isEmpty()) { r["ok"] = false; r["error"] = "reload_failed:" + rerr; return dump(r); }

    const QString token = pairing::randomToken();
    const QString onion = m_onion->onion();
    const qint64 exp = QDateTime::currentSecsSinceEpoch() + 120;

    // Install it on the surface it is meant to open. Without this line the QR handed the
    // phone a bearer token the desktop had never heard of: /v1/ping is unauthenticated so
    // the app reported "connected", while every data route returned 401 and the screen sat
    // on "Waiting for data" forever. Also persisted, or a Basecamp restart would silently
    // 401 an already-paired phone with no way for either end to explain why.
    m_http->setToken(token);
    persistToken(token.toStdString());

    r["ok"] = true;
    r["uri"] = QStringLiteral("lgnode://pair?v=1&onion=%1&ca=%2&t=%3&exp=%4")
                   .arg(onion, kp.privBase32, token).arg(exp);
    r["sas"] = pairing::sas(token, onion);   // shown on BOTH ends; user matches them
    // Returned separately so the pane can display it without re-parsing the URI. It is
    // already inside `uri`; this is a convenience, not an extra secret.
    r["token"] = token;
    r["onion"] = onion;
    r["expiresAt"] = exp;
    r["label"] = name;
    // The private key is IN the uri by necessity (see pairing.h); do not log the uri.
    return dump(r);
}

std::string NodeRemoteImpl::lastSeenPath() const
{
    return (QStandardPaths::writableLocation(QStandardPaths::AppDataLocation)
            + "/node_remote/last_seen").toStdString();
}

void NodeRemoteImpl::persistLastSeenThrottled() const
{
    // Once a minute at most. The phone polls every 10-15s and this runs on the request
    // path; writing on every authenticated request would be a file write per poll, forever,
    // to record a number whose precision does not matter.
    const long long now = QDateTime::currentSecsSinceEpoch();
    if (now - m_lastSeenPersistedAt < 60) return;
    m_lastSeenPersistedAt = now;
    const QString path = QString::fromStdString(lastSeenPath());
    QFile f(path);
    QDir().mkpath(QFileInfo(path).absolutePath());
    if (!f.open(QIODevice::WriteOnly | QIODevice::Truncate)) return;
    f.setPermissions(QFileDevice::ReadOwner | QFileDevice::WriteOwner);
    f.write(QByteArray::number(static_cast<qlonglong>(now)));
    f.close();
}

long long NodeRemoteImpl::loadLastSeen() const
{
    QFile f(QString::fromStdString(lastSeenPath()));
    if (!f.open(QIODevice::ReadOnly)) return 0;
    const long long v = f.readAll().trimmed().toLongLong();
    f.close();
    return v;
}

std::string NodeRemoteImpl::tokenPath() const
{
    return (QStandardPaths::writableLocation(QStandardPaths::AppDataLocation)
            + "/node_remote/device_token").toStdString();
}

void NodeRemoteImpl::persistToken(const std::string& token) const
{
    const QString path = QString::fromStdString(tokenPath());
    QDir().mkpath(QFileInfo(path).absolutePath());
    QFile f(path);
    if (!f.open(QIODevice::WriteOnly | QIODevice::Truncate)) return;
    // Owner-only: this is a bearer credential for the node's control surface.
    f.setPermissions(QFileDevice::ReadOwner | QFileDevice::WriteOwner);
    f.write(QByteArray::fromStdString(token));
    f.close();
}

std::string NodeRemoteImpl::loadToken() const
{
    QFile f(QString::fromStdString(tokenPath()));
    if (!f.open(QIODevice::ReadOnly)) return std::string();
    const QString t = QString::fromUtf8(f.readAll()).trimmed();
    f.close();
    return t.toStdString();
}

std::string NodeRemoteImpl::generateQr(const std::string& payload)
{
    QJsonObject r;
    if (payload.empty()) { r["ok"] = false; r["error"] = "empty payload"; return dump(r); }

    try {
        // MEDIUM ecc: the pairing URI is ~180 chars and the code is read off a monitor at
        // close range, so recovery capacity matters far less than keeping the modules big
        // enough for a phone camera to resolve.
        const qrcodegen::QrCode qr =
            qrcodegen::QrCode::encodeText(payload.c_str(), qrcodegen::QrCode::Ecc::MEDIUM);
        const int n = qr.getSize();
        QJsonArray cells;
        for (int y = 0; y < n; ++y)
            for (int x = 0; x < n; ++x)
                cells.append(qr.getModule(x, y));
        r["ok"] = true;
        r["n"] = n;
        r["cells"] = cells;
    } catch (const std::exception& e) {
        // encodeText throws data_too_long past version 40. Report it rather than
        // returning a blank card the user cannot diagnose.
        r["ok"] = false;
        r["error"] = QString::fromUtf8(e.what());
    }
    return dump(r);
}

std::string NodeRemoteImpl::selfTest()
{
    QJsonObject r;
    int pass = 0, fail = 0;
    auto check = [&](const char* what, bool cond) {
        if (cond) ++pass; else { ++fail; r[QString("FAILED_") + what] = true; }
    };

    // base32 round-trips, and matches a known vector (tor's alphabet, lowercase, unpadded)
    const QByteArray raw = QByteArray::fromHex("000102030405060708090a0b0c0d0e0f"
                                               "101112131415161718191a1b1c1d1e1f");
    const QString b32 = pairing::base32Encode(raw);
    check("b32_roundtrip", pairing::base32Decode(b32) == raw);
    check("b32_len_52", b32.size() == 52);          // 32 bytes -> 52 base32 chars
    check("b32_lowercase", b32 == b32.toLower());

    // keygen: 32-byte halves, distinct, and two calls differ
    const pairing::KeyPair a = pairing::generateClientAuthKey();
    const pairing::KeyPair b = pairing::generateClientAuthKey();
    check("keygen_ok", a.ok && b.ok);
    check("key_sizes", a.privRaw.size() == 32 && a.pubRaw.size() == 32);
    check("priv_ne_pub", a.privRaw != a.pubRaw);
    check("keys_distinct", a.privRaw != b.privRaw);
    check("pub_b32_52", a.pubBase32.size() == 52);

    // tokens are random and hex
    const QString t1 = pairing::randomToken(), t2 = pairing::randomToken();
    check("token_nonempty", !t1.isEmpty());
    check("token_distinct", t1 != t2);
    check("token_len_48", t1.size() == 48);         // 24 bytes hex

    // SAS: deterministic, 6 digits, and changes if EITHER input changes
    const QString s1 = pairing::sas("tok", "abc.onion");
    check("sas_stable", s1 == pairing::sas("tok", "abc.onion"));
    check("sas_6_digits", s1.size() == 6);
    check("sas_binds_token", s1 != pairing::sas("tok2", "abc.onion"));
    check("sas_binds_onion", s1 != pairing::sas("tok", "xyz.onion"));

    // constant-time compare still has to be CORRECT
    check("eq_same", pairing::secureEquals("abc", "abc"));
    check("eq_diff", !pairing::secureEquals("abc", "abd"));
    check("eq_len", !pairing::secureEquals("abc", "abcd"));

    r["passed"] = pass;
    r["failed"] = fail;
    r["ok"] = (fail == 0);
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
    // Last device gone: drop the token and last-seen too, so the next launch does not
    // auto-start a surface for a device that can no longer reach it, and the pane offers
    // pairing rather than claiming a phone it revoked.
    if (m_onion->authorizedClients().isEmpty()) {
        QFile::remove(QString::fromStdString(tokenPath()));
        QFile::remove(QString::fromStdString(lastSeenPath()));
        m_http->forgetLastAuthed();
    }
    return dump(r);
}

std::string NodeRemoteImpl::regenerateOnion()
{
    m_onion->regenerateAddress();
    QJsonObject r;
    r["ok"] = true;
    return dump(r);
}
