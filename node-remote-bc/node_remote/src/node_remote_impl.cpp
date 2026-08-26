#include "node_remote_impl.h"

#include "http_surface.h"
#include "node_probe.h"
#include "onion_service.h"
#include "pairing.h"
#include "block_store.h"
#include "claims_ledger.h"
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
    m_ledger = new ClaimsLedger();
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
    m_http->setRewardsHandler([this] {
        return QByteArray::fromStdString(getRewards());
    });
    // POST, like /v1/start and /v1/stop: a claim spends money, and a GET would let a
    // prefetching client or a stray link burn a fee. HttpSurface enforces the method.
    m_http->setClaimHandler([this] {
        return QByteArray::fromStdString(claimRewards());
    });
}

NodeRemoteImpl::~NodeRemoteImpl()
{
    if (m_onion) { m_onion->stop(); delete m_onion; m_onion = nullptr; }
    if (m_http)  { m_http->stop();  delete m_http;  m_http  = nullptr; }
    delete m_blocks; m_blocks = nullptr;
    delete m_ledger; m_ledger = nullptr;
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
        // Reachability drives the balance timer's decision to spend an IPC call at all.
        // Recorded here because this runs on every phone poll.
        const bool reachable = o.value("reachable").toBool();
        if (m_lastReachable && !reachable) {
            // The node just went away: drop the cached figure. It stops /v1/status
            // reporting a balance for a node that is not running, AND it means the next
            // time the node comes up the timer sees an EMPTY balance and fetches at once
            // rather than waiting out the 30s steady-state backoff.
            m_balanceRaw.clear();
            m_balance.clear();
        }
        m_lastReachable = reachable;
        if (!m_primaryAddress.isEmpty()) o["primaryAddress"] = m_primaryAddress;
        if (!m_balanceRaw.isEmpty())     o["balanceRaw"] = m_balanceRaw;
        if (!m_balance.isEmpty())        o["balance"] = m_balance;
        if (!m_balanceError.isEmpty())   o["balanceError"] = m_balanceError;
    }
    return QJsonDocument(o).toJson(QJsonDocument::Compact).toStdString();
}

// consensus.wallet.funding_pk out of user_config.yaml. A line scan, not a YAML parser:
// the key is unique in the file and the value is a bare hex string on the same line.
static QString fundingPkFromConfig(const QString& cfg)
{
    if (cfg.isEmpty()) return {};
    QFile f(cfg);
    if (!f.open(QIODevice::ReadOnly | QIODevice::Text)) return {};
    while (!f.atEnd()) {
        const QString ln = QString::fromUtf8(f.readLine());
        const int i = ln.indexOf(QLatin1String("funding_pk:"));
        if (i < 0) continue;
        const QString v = ln.mid(i + 11).trimmed();
        if (v.size() == 64) return v;
    }
    return {};
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
    // Spend an IPC call only when it can achieve something:
    //   node down         -> nothing at all (the RPC would just fail on every tick)
    //   up, no figure yet -> every tick (3s), so the balance lands right after a start
    //   up, have a figure -> at most every 30s
    //
    // The timer fires at 3s and THIS decides. Making the timer fast and the work
    // conditional keeps every wallet IPC on the one thread that may safely do it, which is
    // what the SIGSEGV fix requires.
    bool reachable = false;
    bool haveFigure = false;
    {
        QMutexLocker lk(&m_balanceMu);
        reachable = m_lastReachable;
        haveFigure = !m_balanceRaw.isEmpty();
    }
    if (!reachable) return;
    const qint64 nowSecs = QDateTime::currentSecsSinceEpoch();
    if (haveFigure && (nowSecs - m_lastBalanceAt) < 30) return;

    // RESTORED. This check was lost when the diagnostic tracing was stripped: the guard had
    // been written as one line, `if (m_ipcBusy) { NR_TRACE(...); return; }`, and the filter
    // that removed every NR_TRACE line took the whole thing. m_ipcBusy has been set and
    // never read since 033525f — the re-entrancy guard was dead code.
    if (m_ipcBusy) return;
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

    // Which address IS "the node's balance"? Not addrs.first(). The wallet holds (at
    // least) two keys with different balances: the account key, and the LEADER FUNDING
    // key (consensus.wallet.funding_pk) — the one rewards land in and claim fees are
    // paid from. The desktop's dashboard tile and claim gate both read the funding key
    // for exactly that reason (logos_node_1click_backend: setLeaderKey), and this module
    // reading addrs.first() instead is why the phone said 2T while the desktop said 5T
    // (verified live 2026-08-26: account e10e04b0… = 2,000,000,000,000 untouched;
    // funding 5da62d70… = 5,000,000,279,476 where every reward settled). Prefer the
    // funding key from user_config.yaml; the address list is the fallback.
    QString primary = fundingPkFromConfig(g_probe->userConfigPath());
    if (primary.isEmpty()) {
        const QJsonDocument ad =
            QJsonDocument::fromJson(QByteArray::fromStdString(addrs.value.dump()));
        if (ad.isArray() && !ad.array().isEmpty())      primary = ad.array().first().toString();
        else if (ad.isObject())                         primary = ad.object().value("address").toString();
    }
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
    m_lastBalanceAt = nowSecs;
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
        QObject::connect(m_balanceTimer, &QTimer::timeout, m_onion, [this] {
            refreshBalance();
            // Same tick, same thread, same re-entrancy guard. Sequential rather than a
            // second timer: two timers could interleave two synchronous wallet calls, and
            // stacking sync IPC is the shape that took the module down at ~80s.
            refreshRewards();
        });
        // 3s TICK, but refreshBalance() decides whether to act — see the guards there.
        // A bare 30s timer meant the balance could take half a minute to appear after a
        // start, because nothing observed the node coming up; a bare 3s timer would hammer
        // the wallet RPC against a stopped node forever.
        m_balanceTimer->start(3000);
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
    // GUARD: refuse unless the node is BOTH deliberately stopped AND not answering.
    //
    // `reachable` alone was not enough, and the gap is the dangerous one: a node that is
    // REPLAYING has not brought its API up, so reachable is false — while the process is
    // very much alive with open RocksDB handles. Deleting db/ underneath it is how you
    // corrupt a database. logos_node_1click's resetChainState() refuses on
    // Running || Starting || Stopping; this was lifted from it and narrowed to one
    // condition, losing exactly that window.
    //
    // Intent is the missing half: it says whether the user wants the node up, which covers
    // starting and replaying, neither of which is observable through `reachable`.
    //
    // The Android UI already greys the button (nodeRunning = reachable || starting ||
    // recovering) but that is CLIENT-side: this route is reachable over the onion with a
    // token, an older app build has no such gate, and a retry can land after the state
    // changed. A destructive action must refuse on its own.
    const QByteArray statusRaw = g_probe->statusJson();
    const QJsonObject st = QJsonDocument::fromJson(statusRaw).object();
    if (st.value("reachable").toBool()) {
        r["ok"] = false;
        r["code"] = "node_running";
        r["error"] = "Stop the node before wiping the database.";
        return dump(r);
    }
    // Refuse only while the PROCESS is alive. The derived status already distinguishes the
    // three down-states, and they are not equivalent:
    //
    //   Recovering / Starting  process alive, API not up yet  -> REFUSE (the real hazard)
    //   Error                  process died with a cause      -> ALLOW
    //   Stopped                user stopped it                -> ALLOW
    //
    // Error must be allowed: recovering a node wedged after an unclean shutdown is the
    // PRIMARY reason this action exists (logos_node_1click's resetChainState says exactly
    // that). An earlier version of this guard required intent==Stopped, which reads as
    // "started" in the Error state — so it blocked the one case the feature is for.
    const QString derived = st.value("status").toString();
    if (derived == QLatin1String("Recovering") || derived == QLatin1String("Starting")) {
        r["ok"] = false;
        r["code"] = "node_not_stopped";
        r["error"] = "The node is starting or replaying its database. Stop it first — "
                     "wiping now would delete the database out from under a live process.";
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
    // Record the resulting state. logos_node_1click does the equivalent (setStatus(NotStarted)).
    // Without it the intent stayed whatever it was, and since the wipe just deleted logs/
    // there is no error to scrape either — so statusJson() reported "Starting" and the phone
    // showed "Starting…" indefinitely for a node that was never started.
    g_probe->writeIntent(NodeProbe::Intent::Stopped);

    r["ok"] = true;
    r["removed"] = removed.join(", ");
    return dump(r);
}

std::string NodeRemoteImpl::regenerateConfig(const std::string& initialPeers)
{
    QJsonObject r;
    if (!isContextReady()) { r["ok"] = false; r["error"] = "module not ready"; return dump(r); }

    // Same guard as wipeDatabase(), and for the same reason: this rewrites the file the node
    // reads its identity from. It previously had NO state check at all — server-side it
    // would happily rewrite the config of a running node.
    const QJsonObject st = QJsonDocument::fromJson(g_probe->statusJson()).object();
    const QString derived = st.value("status").toString();
    if (st.value("reachable").toBool()
        || derived == QLatin1String("Recovering") || derived == QLatin1String("Starting")) {
        r["ok"] = false;
        r["code"] = "node_not_stopped";
        r["error"] = "Stop the node before regenerating its config.";
        return dump(r);
    }

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
    if (!res.success) {
        r["error"] = QString::fromStdString(res.error);
        return dump(r);
    }

    // REPORT WHAT MOVED. generate_user_config only inserts the keys it is GIVEN, and we pass
    // two — so everything the operator customised reverts to module defaults. The most
    // consequential of those is the leader identity: user_config.yaml carries
    // cryptarchia.leader…funding_pk, and whether the module preserves or re-mints it is not
    // knowable from here. Returning the before/after lets the caller SEE it rather than
    // trust a comment, and the backup path is right there if it changed.
    const QStringList before = configIdentityKeys(backup);
    const QStringList after  = configIdentityKeys(cfg);
    if (before != after) {
        r["identityChanged"] = true;
        r["identityBefore"] = before.join(", ");
        r["identityAfter"] = after.join(", ");
    } else if (!before.isEmpty()) {
        r["identityChanged"] = false;
    }
    return dump(r);
}

// The identity-bearing values in user_config.yaml, in file order. Deliberately a plain
// line scan and not a YAML parse: the module owns this file's schema, we only need to know
// whether these specific values survived a regenerate, and a dependency-free comparison
// cannot itself break the regenerate path.
QStringList NodeRemoteImpl::configIdentityKeys(const QString& path)
{
    static const QStringList kKeys{QStringLiteral("funding_pk"),
                                   QStringLiteral("non_ephemeral_signing_key_id"),
                                   QStringLiteral("secret_key_kms_id")};
    QStringList out;
    QFile f(path);
    if (!f.open(QIODevice::ReadOnly | QIODevice::Text)) return out;
    const QStringList lines = QString::fromUtf8(f.readAll()).split(QLatin1Char('\n'));
    f.close();
    for (const QString& ln : lines) {
        for (const QString& k : kKeys) {
            const int i = ln.indexOf(k + QLatin1Char(':'));
            if (i < 0) continue;
            out << ln.mid(i).trimmed();
            break;
        }
    }
    return out;
}

std::string NodeRemoteImpl::getBlocks()
{
    return m_blocks->json(0).toStdString();
}

std::string NodeRemoteImpl::getProposals()
{
    // PREFER the desktop's proposals-history.json (beside user_config.yaml, like the
    // claims ledger): it is the LIFETIME record logos_node_1click maintains, in exactly
    // the row shape the phone renders ({id,txs,removed,time}). The log grep below only
    // ever saw the retained rotated logs — and on module 0.2.3 it sees nothing at all
    // (verified live 2026-08-26: 235 rows in the file, zero "proposed block" lines in
    // logs/), which is why the phone's Blocks-led tile went blank while the desktop
    // said 235. Same rule as everywhere else in this module: read what the desktop
    // wrote, don't re-derive it.
    const QString cfg = g_probe->userConfigPath();
    if (!cfg.isEmpty()) {
        QFile f(QFileInfo(cfg).absoluteDir().filePath(QStringLiteral("proposals-history.json")));
        if (f.open(QIODevice::ReadOnly)) {
            const QJsonDocument doc = QJsonDocument::fromJson(f.readAll());
            f.close();
            if (doc.isArray() && !doc.array().isEmpty()) {
                QJsonArray arr = doc.array();
                while (arr.size() > 200) arr.removeLast();   // rows arrive newest-first
                return QJsonDocument(arr).toJson(QJsonDocument::Compact).toStdString();
            }
        }
    }
    // Fallback for a machine where the Blockchain pane never ran: the node's own logs,
    // beside its config in `logs` — the same place logos_node_1click's resetChainState()
    // wipes. Recent-only, and possibly silent on 0.2.3's wording.
    const QString dir = cfg.isEmpty() ? QString()
                                      : QFileInfo(cfg).absolutePath() + QStringLiteral("/logs");
    return BlockStore::proposalsJson(dir, 200).toStdString();
}

// The LIFETIME blocks-led count. getProposals() caps at 200 rows for the tab; the
// Rewards tile must not inherit that cap or 235 reads as 200. File-local, NOT a class
// method: node_remote_impl.h is the LIDL interface, and a helper declared there would
// be published as IPC (and rejected — LIDL numbers are 64-bit only).
static int blocksLedFromHistory(const QString& cfg)
{
    if (cfg.isEmpty()) return -1;
    QFile f(QFileInfo(cfg).absoluteDir().filePath(QStringLiteral("proposals-history.json")));
    if (!f.open(QIODevice::ReadOnly)) return -1;
    const QJsonDocument doc = QJsonDocument::fromJson(f.readAll());
    f.close();
    return doc.isArray() && !doc.array().isEmpty() ? doc.array().size() : -1;
}

// Refresh the claimable-voucher count. Runs on the module's timer, NOT on a request —
// same constraint as refreshBalance(), for the same reason: wallet_get_claimable_vouchers
// is synchronous IPC, and a handler that blocks on it cannot return, so the reply it is
// waiting for can never be delivered.
void NodeRemoteImpl::refreshRewards()
{
    if (!isContextReady()) return;

    bool reachable = false;
    {
        QMutexLocker lk(&m_balanceMu);
        reachable = m_lastReachable;
        if (!reachable && m_vouchersReady >= 0) {
            // The node went away. Drop the count rather than let the phone show a pool
            // for a node that is not running — and a claim button gated on it.
            m_vouchersReady = -1;
            m_vouchersError.clear();
        }
    }
    if (!reachable) return;

    // Slower than the balance: the pool moves once per block led, which is minutes apart
    // at best, and every tick here is a synchronous IPC call on the one thread allowed to
    // make them. 30s is well inside the ~2h a claim takes to settle.
    const qint64 nowSecs = QDateTime::currentSecsSinceEpoch();
    if (m_vouchersReady >= 0 && (nowSecs - m_lastVouchersAt) < 30) return;

    if (m_ipcBusy) return;
    m_ipcBusy = true;
    struct Clear { bool& b; ~Clear() { b = false; } } clear{m_ipcBusy};

    const StdLogosResult v = modules().blockchain_module.wallet_get_claimable_vouchers();
    if (!v.success) {
        // Instance-bound, exactly like the balance RPCs: these answer only inside the
        // blockchain_module that is actually running the node. Keep the last count and
        // say why it is stale rather than reporting an empty pool, which would read as
        // "nothing to claim" — a different and wrong statement.
        QMutexLocker lk(&m_balanceMu);
        m_vouchersError = QString::fromStdString(v.error);
        return;
    }

    // UNWRAP FIRST. StdLogosResult::value is an nlohmann::json holding ANY JSON value, and
    // this module hands back a *string* whose contents are the JSON — so dump() re-encodes
    // it as a quoted, escaped string and parsing that yields neither an array nor an
    // object. refreshBalance() above meets the same thing and strips the quotes by hand.
    // Measured, not assumed: this route first reported "unrecognised" against a live node
    // with a healthy wallet.
    const std::string body = v.value.is_string() ? v.value.get<std::string>() : v.value.dump();

    // The node returns its `available` bucket only. Two shapes are in the wild — a bare
    // array, and an object wrapping one — so both are accepted rather than assuming the
    // one this machine happens to return. VOUCHER-STATE-MAP §7: a shape that "obviously"
    // matches has been wrong four times out of four here, and this made five.
    const QJsonDocument vd = QJsonDocument::fromJson(QByteArray::fromStdString(body));
    int n = -1;
    if (vd.isArray()) {
        n = vd.array().size();
    } else if (vd.isObject()) {
        const QJsonObject o = vd.object();
        for (const char* k : {"available", "vouchers", "claimable"}) {
            if (o.value(QLatin1String(k)).isArray()) { n = o.value(QLatin1String(k)).toArray().size(); break; }
        }
        if (n < 0 && o.value(QStringLiteral("count")).isDouble())
            n = o.value(QStringLiteral("count")).toInt();
    }

    QMutexLocker lk(&m_balanceMu);
    if (n < 0) {
        // Parsed, but not into anything we recognise. Report that instead of guessing a
        // number — a wrong pool count gates the claim button.
        //
        // CARRY THE EVIDENCE. The first version of this said only "unrecognised", which is
        // exactly the silent-error shape VOUCHER-STATE-MAP §7 calls out: it named the
        // symptom and cost a round of guessing to find the cause. qWarning is no use here
        // (a module's stderr is never captured by logos_host), so the prefix rides out on
        // the wire where it can actually be read.
        m_vouchersError = QStringLiteral("unrecognised claimable-voucher response: %1")
                              .arg(QString::fromStdString(body.substr(0, 120)));
        return;
    }
    m_vouchersError.clear();
    m_vouchersReady = n;
    m_lastVouchersAt = nowSecs;
}

std::string NodeRemoteImpl::getRewards()
{
    // libSlot comes from the probe's cheap cached status read (the node's own REST API on
    // loopback), not from IPC — this runs on the request path.
    const QJsonObject st = QJsonDocument::fromJson(g_probe->statusJson()).object();
    const qint64 libSlot = static_cast<qint64>(st.value(QStringLiteral("libSlot")).toDouble());

    QJsonObject r = m_ledger->read(g_probe->userConfigPath(), libSlot);

    int ready = -1;
    QString vErr;
    {
        QMutexLocker lk(&m_balanceMu);
        ready = m_vouchersReady;
        vErr  = m_vouchersError;
    }
    r["ready"] = ready;                       // -1 = not read yet, distinct from an empty pool
    if (!vErr.isEmpty()) r["readyError"] = vErr;

    // The value of the pool is an ESTIMATE and is labelled as one all the way to the
    // screen. The reward is read from ledger state when a claim executes and it does move
    // (9,517 then 9,535 then 9,664 observed on this chain), so this is "vouchers x the
    // most recent settled reward", never a promise of what a claim will pay.
    qint64 lastReward = 0, lastFee = 0;
    bool haveFee = false;
    const QJsonArray claims = r.value(QStringLiteral("claims")).toArray();
    for (const QJsonValue& v : claims) {
        const QJsonObject row = v.toObject();
        if (row.value(QStringLiteral("status")).toString() != QLatin1String("settled")) continue;
        const qint64 rw = static_cast<qint64>(row.value(QStringLiteral("reward")).toDouble());
        if (rw <= 0) continue;
        lastReward = rw;
        if (row.contains(QStringLiteral("fee"))) {
            lastFee = static_cast<qint64>(row.value(QStringLiteral("fee")).toDouble());
            haveFee = true;
        }
        break;                                // claims are newest-first
    }
    r["lastReward"] = lastReward;
    if (haveFee) r["lastFee"] = lastFee;      // ABSENT, never 0, when no settled row prices it
    if (ready > 0 && lastReward > 0) r["readyEstimate"] = ready * lastReward;

    // Blocks led is NOT vouchers earned and the gap is not explainable from here: the
    // wallet hides any voucher it cannot prove at the current tip and no API lists them.
    // Sent so the phone can show the gap as a gap rather than implying the pool is the
    // whole of what was earned (111 led / 10 claimed / 12 claimable, measured).
    {
        const int led = blocksLedFromHistory(g_probe->userConfigPath());
        r["blocksLed"] = led >= 0
            ? led
            : static_cast<int>(QJsonDocument::fromJson(
                  QByteArray::fromStdString(getProposals())).array().size());
    }

    return dump(r);
}

std::string NodeRemoteImpl::claimRewards()
{
    QJsonObject r;

    // Refuse before spending anything if the pool is empty or unknown. The node would
    // reject it anyway, but a local refusal costs no IPC and gives the phone a reason
    // instead of a bare failure.
    int ready = -1;
    {
        QMutexLocker lk(&m_balanceMu);
        ready = m_vouchersReady;
    }
    if (ready == 0) {
        r["ok"] = false;
        r["code"] = "none_ready";
        r["error"] = QStringLiteral("no vouchers ready to claim");
        return dump(r);
    }

    // Sync IPC from the request path, the same as /v1/start and /v1/stop. That is
    // acceptable HERE and not in getNodeStatus() because this is a rare, user-initiated
    // action rather than a per-poll fetch: it cannot wedge a route that something else is
    // about to poll, and the caller is waiting for exactly this answer.
    if (m_ipcBusy) {
        r["ok"] = false;
        r["code"] = "busy";
        r["error"] = QStringLiteral("the wallet is busy — try again in a moment");
        return dump(r);
    }
    m_ipcBusy = true;
    struct Clear { bool& b; ~Clear() { b = false; } } clear{m_ipcBusy};

    const StdLogosResult res = modules().blockchain_module.leader_claim();
    if (!res.success) {
        r["ok"] = false;
        r["code"] = "claim_failed";
        // Pass the node's own words through. InsufficientFunds is the common one and it
        // reports `available` without `required`, so the phone cannot compute the
        // shortfall — it must not invent one.
        r["error"] = QString::fromStdString(res.error);
        return dump(r);
    }

    // leader_claim returns a tx hash and nothing else. The voucher it consumed and the
    // fee it paid are both unknown until settlement, so neither is reported here.
    const QString tx = QString::fromStdString(res.value.dump()).remove('"').trimmed();
    r["ok"] = true;
    r["code"] = "submitted";
    r["tx"] = tx;

    // WRITE-AHEAD, and it must happen now. The claim leaves no other trace on this
    // machine — `leader_claim` appears zero times in the Basecamp log — so if this row is
    // not written, no evidence that the press happened exists anywhere until the chain
    // backfills it as settled roughly two hours later.
    //
    // Written to OUR file, never to the desktop's ledger: that one is rewritten by
    // ui-host on a 20s load-mutate-save cycle, and a second writer would both lose rows
    // and risk corrupting it. Both surfaces read both files. See pending_claims.h.
    const QJsonObject st = QJsonDocument::fromJson(g_probe->statusJson()).object();
    const qint64 atSlot = static_cast<qint64>(st.value(QStringLiteral("slot")).toDouble());
    if (!m_ledger->recordSubmitted(g_probe->userConfigPath(), tx, atSlot)) {
        // The claim SUCCEEDED — it is on the network and the fee is spent — but we could
        // not record it. Report both, because the two facts need different actions: there
        // is nothing to retry, and the row simply will not appear until settlement.
        r["warning"] = QStringLiteral(
            "The claim was submitted, but it could not be recorded locally — it will not "
            "appear in the claims list until it settles.");
    }

    // Force the pool to re-read on the next tick. The claimed voucher moves into the
    // node's `pending` bucket immediately, so `available` drops by one — and a stale
    // count would leave the phone offering a claim for a voucher already reserved.
    {
        QMutexLocker lk(&m_balanceMu);
        m_lastVouchersAt = 0;
    }

    // Deliberately NOT written to claims-history.json. That file is logos_node_1click's,
    // and two writers on one file is how a ledger gets corrupted; the desktop's chain
    // backfill recovers this claim as settled from the block events under our pk (see
    // VOUCHER-STATE-MAP §5). What is lost by not writing is the submitted-at timestamp,
    // so a phone-initiated claim appears in the ledger only once it settles. That is a
    // real gap and the phone says so rather than pretending the row is there.
    return dump(r);
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
            // VERIFY before believing it. blockchain_module answers "not running" for two
            // very different situations, and treating them alike reports a false success:
            //
            //   (a) the node really is stopped        -> idempotent success
            //   (b) the node is RUNNING but was started by a DIFFERENT blockchain_module
            //       client (e.g. the 1-click desktop UI). Node lifecycle is INSTANCE-BOUND,
            //       the same way the wallet RPCs are, so this module cannot see — or stop —
            //       a node it did not start.
            //
            // Observed live: /v1/stop returned {"ok":true,"code":"already_stopped"} while
            // the node was serving at height 23018. The caller trusted that success and
            // waited for a stop that was never attempted. Worse, it wrote intent=stopped
            // for a running node, which the reachability self-heal then flipped back to
            // started — so the two fought each other every poll.
            const QJsonObject st = QJsonDocument::fromJson(g_probe->statusJson()).object();
            if (st.value("reachable").toBool()) {
                r["ok"] = false;
                r["code"] = "not_owned";
                r["error"] = QStringLiteral(
                    "This node was started outside Node Remote, so it can only be stopped "
                    "where it was started — use Stop node in the Blockchain node app on the "
                    "desktop.");
            } else {
                // Genuinely stopped. Idempotent: stopping an already-stopped node is
                // success, not an error — this is what surfaced the scary "deploy config"
                // card on the phone.
                g_probe->writeIntent(NodeProbe::Intent::Stopped);
                r["ok"] = true;
                r["code"] = "already_stopped";
            }
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

    // REFUSE TO CLOBBER A LIVE PAIRING.
    //
    // This function is destructive and did not say so. It mints a NEW x25519 keypair,
    // TRUNCATES authorized_clients/<label>.auth over the old public key, and rotates the
    // bearer token. A phone that is already paired holds the OLD private key and the OLD
    // token, and nothing tells it otherwise — so after a stray call it can no longer
    // decrypt the descriptor, every request times out, last_seen stays NEVER, and both
    // ends sit there looking healthy. There is no error to observe because from tor's
    // point of view an un-authorized client is indistinguishable from no client at all.
    //
    // That is not hypothetical: a rotation at 17:58:53 left phone.auth holding
    // 76a2yia3… while the phone still held the private half of 4swheovo…, and the only
    // way to see it was to derive the public key from the phone's stored key by hand.
    //
    // Rotation is still available — it is just spelled out: revoke, then pair. "Unpair"
    // already does the revoke, so the destructive step is one the user asks for by name
    // rather than one a poll loop can perform by accident.
    // The test is "has a device actually USED this key", not "does a key exist". A key is
    // written the instant the QR is drawn, so `authorizedClients()` is non-empty for every
    // code still on screen; refusing on that alone would strand the user with an expired
    // code and no way to mint another. An unscanned key belongs to nobody and is free to
    // replace. lastAuthedAt() > 0 means a real device completed an authenticated request
    // with it — that is the one worth protecting.
    const QStringList existing = m_onion->authorizedClients();
    if (!existing.isEmpty() && m_http && m_http->lastAuthedAt() > 0) {
        r["ok"] = false;
        r["code"] = "already_paired";
        r["clients"] = QJsonArray::fromStringList(existing);
        r["error"] = QStringLiteral("Already paired with %1. Unpair first to pair a "
                                    "different device — issuing a new code would silently "
                                    "cut off the paired one.").arg(existing.join(", "));
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
    // Hard reload: tor must drop the revoked client's circuits and republish a descriptor
    // that key cannot decrypt. Without it the revocation is only as strong as the bearer
    // token, and the onion stays reachable to the device that was just unpaired.
    if (r["ok"].toBool()) m_onion->reload(/*hard=*/true);
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
