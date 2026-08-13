#include "onion_service.h"

#include <QCoreApplication>
#include <QDebug>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QProcessEnvironment>
#include <QStandardPaths>
#include <QTextStream>

#include <openssl/rand.h>

// POSIX, on every platform we target. NOT <csignal>: that header declares std::raise and
// std::signal, while kill() is POSIX and lives in <signal.h>. glibc happens to pull the
// POSIX declarations into the global namespace anyway, so the Linux build was fine and the
// mistake was invisible — libc++ on macOS does not, and the darwin build failed with
// "no member named 'kill' in the global namespace". It was also guarded behind Q_OS_LINUX,
// so macOS got no signal header at all.
#include <signal.h>
#include <sys/types.h>

#ifdef Q_OS_LINUX
#include <sys/prctl.h>   // PR_SET_PDEATHSIG — genuinely Linux-only
#endif

namespace {

// Owner-only on every directory that holds tor state or key material (Senty FINDING-4).
constexpr QFileDevice::Permissions kOwnerOnlyDir =
    QFileDevice::ReadOwner | QFileDevice::WriteOwner | QFileDevice::ExeOwner;

// Resolve tor: <moduleDir>/bin/tor first (the .lgx ships it there — see nix/tor-bundle.nix),
// then $NODE_REMOTE_TOR_BIN, then PATH.
//
// This comment used to claim the bundle existed when it did not. Nothing caught it because
// the PATH fallback finds /bin/tor on any Linux desktop; the gap only surfaced on a Mac,
// where there is usually no tor at all and the module simply cannot start its onion.
QString resolveTor()
{
    const QByteArray override = qgetenv("NODE_REMOTE_TOR_BIN");
    if (!override.isEmpty()) return QString::fromLocal8Bit(override);

    const QString bundled = QCoreApplication::applicationDirPath() + "/bin/tor";
    if (QFileInfo::exists(bundled)) return bundled;

    // Also try alongside this plugin — logos_host may run from a different cwd.
    const QString beside = QFileInfo(QString::fromLocal8Bit(qgetenv("NODE_REMOTE_MODULE_DIR")))
                               .absoluteFilePath() + "/bin/tor";
    if (QFileInfo::exists(beside)) return beside;

    return QStringLiteral("tor");
}

// The host's bundled libs break a spawned tor (it needs its own libevent/openssl). Strip
// the loader variables so the child resolves against its own bundle or the system.
//
// DYLD_* included: those are macOS's equivalents of LD_LIBRARY_PATH/LD_PRELOAD, and their
// absence here was a latent bug — the Linux path has worked around exactly this class of
// failure since the AppImage days, while macOS had no equivalent guard at all.
QProcessEnvironment cleanSpawnEnv()
{
    QProcessEnvironment env = QProcessEnvironment::systemEnvironment();
    for (const char* v : {"LD_LIBRARY_PATH", "LD_PRELOAD",
                          "DYLD_LIBRARY_PATH", "DYLD_INSERT_LIBRARIES",
                          "DYLD_FRAMEWORK_PATH", "DYLD_FALLBACK_LIBRARY_PATH",
                          "QT_PLUGIN_PATH", "QML2_IMPORT_PATH", "GST_PLUGIN_SYSTEM_PATH"})
        env.remove(QString::fromLatin1(v));
    return env;
}

void dieWithParent(QProcess* p)
{
#ifdef Q_OS_LINUX
    // Don't orphan tor if logos_host is killed with -9.
    p->setChildProcessModifier([] { ::prctl(PR_SET_PDEATHSIG, SIGKILL); });
#else
    Q_UNUSED(p);
#endif
}

// `fromOffset` scopes the search to what was written AFTER a known point. Without it,
// "has the descriptor been uploaded?" is answered by a line from a PREVIOUS publish, and
// the service is declared reachable while the current descriptor does not exist yet.
bool fileContains(const QString& path, const char* a, const char* b, qint64 fromOffset = 0)
{
    QFile f(path);
    if (!f.open(QIODevice::ReadOnly)) return false;
    // A shrunken file means tor rotated or truncated it; the offset is meaningless, so
    // fall back to reading the whole thing rather than seeking past the end.
    if (fromOffset > 0 && fromOffset <= f.size()) f.seek(fromOffset);
    const QString s = QString::fromUtf8(f.readAll());
    f.close();
    return s.contains(QLatin1String(a), Qt::CaseInsensitive)
        && (!b || s.contains(QLatin1String(b), Qt::CaseInsensitive));
}

}  // namespace

OnionService::OnionService(QObject* parent) : QObject(parent)
{
    connect(&m_poll, &QTimer::timeout, this, &OnionService::poll);
}

OnionService::~OnionService() { stop(); }

QString OnionService::persistentHsDir() const
{
    // Key material lives OUTSIDE the temp run dir so the .onion survives restarts.
    return QStandardPaths::writableLocation(QStandardPaths::AppDataLocation)
           + "/node_remote/hs";
}

QString OnionService::authClientsDir() const
{
    return persistentHsDir() + "/authorized_clients";
}

QString OnionService::torCacheDir() const
{
    return QStandardPaths::writableLocation(QStandardPaths::AppDataLocation)
           + "/node_remote/tor-cache";
}

bool OnionService::spawnTor(const QString& cfg, QString& errOut)
{
    const QString bin     = resolveTor();
    // PERSISTENT across restarts. m_runDir is a temp dir wiped on stop(), and putting
    // tor's DataDirectory there meant a COLD consensus fetch on every start — 30-60s at
    // best, and the directory authorities throttle repeated cold starts until tor sits
    // at "Bootstrapped 5%" indefinitely. That matters now that reload() restarts tor on
    // every pairing. Cached, it bootstraps in seconds.
    const QString dataDir = torCacheDir();
    const QString torrc   = m_runDir + "/torrc";

    // A failed start leaves no RUN state behind (Senty ISSUE-5). The tor cache is
    // deliberately kept — it holds no secrets of ours, only the public consensus.
    auto fail = [&](const QString& code) {
        QDir(m_runDir).removeRecursively();
        errOut = code;
        return false;
    };

    if (!QDir().mkpath(dataDir) || !QDir().mkpath(m_runDir))
        return fail(QStringLiteral("tor_dir_failed"));
    QFile::setPermissions(m_runDir, kOwnerOnlyDir);
    QFile::setPermissions(dataDir, kOwnerOnlyDir);

    QFile f(torrc);
    if (!f.open(QIODevice::WriteOnly | QIODevice::Truncate))
        return fail(QStringLiteral("tor_cfg_failed"));
    f.write(cfg.toUtf8());
    f.close();

    m_tor = new QProcess(this);
    m_tor->setProcessChannelMode(QProcess::MergedChannels);
    m_tor->setProcessEnvironment(cleanSpawnEnv());
    dieWithParent(m_tor);
    m_tor->start(bin, QStringList() << "-f" << torrc);

    if (!m_tor->waitForStarted(5000)) {
        const bool notFound = m_tor->error() == QProcess::FailedToStart;
        qWarning() << "OnionService: tor failed to start:" << m_tor->errorString();
        m_tor->deleteLater();
        m_tor = nullptr;
        return fail(notFound ? QStringLiteral("tor_not_found")
                             : QStringLiteral("tor_start_failed"));
    }

    // An immediate exit means bad config or the HS/control port is taken (Senty ISSUE-3).
    // Never proceed as if tor were healthy — that silently breaks the privacy transport.
    if (m_tor->waitForFinished(500)) {
        const QByteArray out = m_tor->readAll();
        qWarning() << "OnionService: tor exited immediately:" << out;
        // logos_host swallows child stderr — persist the real reason for diagnosis.
        const QString diag = QStandardPaths::writableLocation(QStandardPaths::TempLocation)
                             + "/node_remote/tor-fail.log";
        QDir().mkpath(QFileInfo(diag).absolutePath());
        QFile d(diag);
        if (d.open(QIODevice::WriteOnly | QIODevice::Append)) {
            d.write("---- tor exited immediately ----\n");
            d.write(out);
            d.write("\n");
            d.close();
        }
        m_tor->deleteLater();
        m_tor = nullptr;
        return fail(QStringLiteral("tor_port_in_use"));
    }
    return true;
}

QString OnionService::start(quint16 localPort)
{
    m_localPort = localPort;
    if (m_tor && m_tor->state() == QProcess::Running) {
        // Tor is up, so there is nothing to spawn — but returning here used to be a DEAD END.
        // If the readiness poll had already given up, m_ready was false with the timer
        // stopped, so nothing would ever re-check: the pane's Pair button called straight
        // into this early return and did nothing at all, forever, while the onion was live.
        // The only escape was restarting Basecamp.
        //
        // So make the button mean something: if we are not ready and the poll is not running,
        // start looking again. Cheap, and it turns an unrecoverable state into a retry.
        if (!m_ready && !m_poll.isActive()) {
            qInfo() << "OnionService: re-arming the readiness poll after a previous timeout";
            m_error.clear();
            m_ticks = 0;
            m_bootstrappedAt = 0;
            m_afterHup = false;
            m_hsLogMark = 0;   // scan the whole log: the publish we missed is already in it
            m_poll.start(2000);
        }
        return QString();
    }

    m_runDir = QStandardPaths::writableLocation(QStandardPaths::TempLocation)
               + "/node_remote/tor";
    const QString hsDir = persistentHsDir();
    QDir().mkpath(hsDir);
    QFile::setPermissions(hsDir, kOwnerOnlyDir);

    // Only create authorized_clients/ if we have at least one client. An EMPTY
    // authorized_clients dir makes tor treat the service as client-auth-enabled with
    // nobody authorized — the onion then exists but nobody, including you, can reach it.
    // That failure mode is silent and very confusing, so we let start() run without it
    // and only add the dir when authorizeClient() is first called.

    QString cfg;
    QTextStream s(&cfg);
    s << "SocksPort 0\n"                                    // serve the HS only (Senty ISSUE-2)
      << "DataDirectory " << torCacheDir() << "\n"
      << "Log notice file " << m_runDir << "/tor.log\n"
      // Descriptor upload is logged at INFO in the [rend] domain. notice level NEVER logs it,
      // which is what made radio false-timeout on a perfectly reachable onion.
      << "Log [rend]info file " << m_runDir << "/hs.log\n"
      << "HiddenServiceDir " << hsDir << "\n"
      << "HiddenServicePort 80 127.0.0.1:" << localPort << "\n";

    QString err;
    if (!spawnTor(cfg, err)) {
        m_runDir.clear();
        m_error = err;
        emit failed(err);
        return err;
    }

    // Re-read the address from the PERSISTENT hostname file straight away. The .onion is
    // stable key material, so after a reload it is unchanged — clearing it and waiting
    // for the poll produced a pairing URI with an empty onion= field.
    m_onion.clear();
    {
        QFile hf(persistentHsDir() + "/hostname");
        if (hf.open(QIODevice::ReadOnly)) {
            m_onion = QString::fromUtf8(hf.readAll()).trimmed();
            hf.close();
        }
    }
    m_ready = false;
    m_error.clear();
    m_ticks = 0;
    m_bootstrappedAt = 0;
    // A fresh start begins a new publish cycle, but hs.log is APPENDED to across runs —
    // so the previous run's upload lines are still sitting there and would satisfy the
    // readiness check immediately. Start counting from the end of what already exists.
    m_hsLogMark = QFileInfo(m_runDir + "/hs.log").size();
    m_afterHup = false;
    m_poll.start(2000);
    return QString();
}

void OnionService::stop()
{
    m_poll.stop();
    if (m_tor) {
        m_tor->terminate();
        if (!m_tor->waitForFinished(3000)) m_tor->kill();
        m_tor->deleteLater();
        m_tor = nullptr;
    }
    if (!m_runDir.isEmpty()) QDir(m_runDir).removeRecursively();
    m_runDir.clear();
    m_ready = false;
}

void OnionService::poll()
{
    if (m_runDir.isEmpty()) { m_poll.stop(); return; }

    ++m_ticks;

    // Detect bootstrap completion INDEPENDENTLY of the readiness fallback below, because the
    // timeout has to know about it. (It used to be set only inside that fallback.)
    if (m_bootstrappedAt == 0 && !m_afterHup
        && fileContains(m_runDir + "/tor.log", "Bootstrapped 100%", nullptr))
        m_bootstrappedAt = m_ticks;

    // TWO CLOCKS, because "tor is still bootstrapping" and "tor is bootstrapped but the
    // descriptor will not publish" are different failures with wildly different budgets.
    //
    // This was one flat 120s clock started when we spawned tor, and it MISFIRED ON EVERY
    // FIRST RUN. A fresh install has an empty tor cache, so tor must fetch a full consensus:
    // measured at 125s on a normal connection. The poll gave up at 120s — five seconds before
    // tor finished — called m_poll.stop(), and left m_ready false permanently while the onion
    // published two seconds later and sat there live. The pane showed no QR and no way
    // forward; a Basecamp restart "fixed" it only because the cache was then warm, which is
    // exactly why this survived testing. Same trap the test harness hit, in the product.
    //
    // A cold bootstrap can legitimately take many minutes on a slow, metered or censored
    // link, so it gets a wide ceiling. Publishing, once tor is actually ready, is fast — and
    // if it has not happened in three minutes something is genuinely wrong.
    constexpr int kBootstrapTicks = 450;   // 15 min — cold consensus, slow link, bridges
    constexpr int kPublishTicks   = 90;    // 3 min AFTER bootstrap completes
    const bool timedOut = (m_bootstrappedAt > 0)
                              ? (m_ticks - m_bootstrappedAt > kPublishTicks)
                              : (m_ticks > kBootstrapTicks);
    if (timedOut) {
        m_poll.stop();
        if (!m_ready) {
            m_error = m_bootstrappedAt > 0 ? QStringLiteral("publish_timeout")
                                           : QStringLiteral("tor_bootstrap_timeout");
            qWarning() << "OnionService: giving up —" << m_error
                       << "after" << (m_ticks * 2) << "s";
            emit failed(m_error);
        }
        return;
    }

    if (m_onion.isEmpty()) {
        QFile hf(persistentHsDir() + "/hostname");
        if (hf.open(QIODevice::ReadOnly)) {
            m_onion = QString::fromUtf8(hf.readAll()).trimmed();
            hf.close();
        }
    }
    if (m_onion.isEmpty() || m_ready) return;

    // Precise signal: the [rend]info log records the descriptor upload to the HSDirs.
    bool ok = fileContains(m_runDir + "/hs.log", "upload", "descriptor", m_hsLogMark);
    // Fallback, robust to tor wording changes: once bootstrapped 100% the descriptor
    // publishes within tens of seconds — accept after a grace so a live onion is never missed.
    // Skipped after a HUP: tor is ALREADY bootstrapped, so "Bootstrapped 100%" is always
    // present and this fallback would declare readiness instantly — defeating the mark and
    // re-announcing the onion before the new client's descriptor is up. On a fresh start it
    // stays, as the guard against tor changing its log wording.
    if (!ok && !m_afterHup && m_bootstrappedAt > 0
        && m_ticks - m_bootstrappedAt >= 12) {
        ok = true;   // ~24s after 100%
    }

    if (ok) {
        m_ready = true;
        m_poll.stop();
        qInfo() << "OnionService: descriptor published — reachable";
        emit ready(m_onion);
    }
}

bool OnionService::authorizeClient(const QString& name, const QString& x25519PubBase32)
{
    if (name.isEmpty() || x25519PubBase32.isEmpty()) return false;
    // Guard the filename: this lands on disk, and `name` comes from a pairing request.
    for (const QChar c : name)
        if (!c.isLetterOrNumber() && c != '-' && c != '_') return false;

    const QString dir = authClientsDir();
    if (!QDir().mkpath(dir)) return false;
    QFile::setPermissions(dir, kOwnerOnlyDir);

    // Drop the deny-all sentinel: it exists only to keep the service closed while NO real
    // client is authorized, and one is being authorized right now. Leaving it behind put a
    // second, permanently undecryptable slot in every descriptor — harmless to correctness
    // but pure noise when reading authorized_clients to diagnose a pairing.
    QFile::remove(dir + "/_sealed.auth");

    QFile f(dir + "/" + name + ".auth");
    if (!f.open(QIODevice::WriteOnly | QIODevice::Truncate)) return false;
    // Format per rend-spec-v3: descriptor:x25519:<base32 public key>
    f.write(QStringLiteral("descriptor:x25519:%1\n").arg(x25519PubBase32).toUtf8());
    f.close();
    QFile::setPermissions(f.fileName(), QFileDevice::ReadOwner | QFileDevice::WriteOwner);
    return true;
}

bool OnionService::revokeClient(const QString& name)
{
    const bool removed = QFile::remove(authClientsDir() + "/" + name + ".auth");
    sealClosed();
    return removed;
}

QString OnionService::reload(bool hard)
{
    // SIGHUP, not a restart. Tor re-reads its configuration on HUP, and that INCLUDES the
    // onion service's authorized_clients directory — adding a client does not require
    // taking the service down.
    //
    // The difference is the whole pairing experience. A restart destroys the descriptor
    // and every introduction point, so the onion is unreachable until tor bootstraps and
    // republishes from nothing: measured at 2m20s from minting a key to the phone's first
    // successful request, with the app showing "Connected" and no data throughout. A HUP
    // keeps the circuits and intro points alive and only re-uploads the descriptor with
    // the new client set.
    //
    // The .onion address survives either way — the service keys live in a persistent dir.
    // REVOCATION MUST BE HARD. A HUP makes tor re-read the client set, but it does NOT
    // tear down the circuits a revoked client already holds: it keeps its cached
    // descriptor and the live introduction points, so it can still REACH the service and
    // is stopped only by the bearer token. Measured directly — the revoked key went from
    // 000 (could not reach the onion at all) to 401 (reached it, rejected) the moment
    // reload() started HUPing. 401 leaks that the service exists and is up, which is the
    // property client auth is there to hide. A restart destroys those circuits and forces
    // a descriptor the revoked key cannot decrypt.
    if (!hard && m_tor && m_tor->state() == QProcess::Running) {
        const qint64 pid = m_tor->processId();
        if (pid > 0 && ::kill(static_cast<pid_t>(pid), SIGHUP) == 0) {
            // The descriptor still has to be re-uploaded for the new client set, so mark
            // where hs.log currently ends: readiness must be decided by an upload that
            // happens AFTER this point, never by the one that published the old set.
            m_hsLogMark = QFileInfo(m_runDir + "/hs.log").size();
            m_afterHup = true;
            m_ready = false;
            m_bootstrappedAt = 0;
            m_ticks = 0;
            m_poll.start(2000);
            qInfo() << "OnionService: SIGHUP — re-reading authorized_clients without a restart";
            return QString();
        }
        qWarning() << "OnionService: SIGHUP failed, falling back to a full restart";
    }

    // Fallback: tor is not running, or the signal failed. A restart is slow but correct.
    const quint16 p = m_localPort;
    stop();
    return start(p);
}

void OnionService::sealClosed()
{
    // An authorized_clients dir with no valid entries does NOT mean "nobody may connect".
    // Tor treats the service as having no client auth at all and serves it to the world.
    // That is a fail-OPEN, and it is what P4 caught: revoking the last device silently
    // reopened the service.
    //
    // So when the set would become empty, install a sentinel authorizing a freshly
    // generated key that is immediately discarded. Nobody holds the private half, so the
    // descriptor stays encrypted and the service stays shut. Fail closed.
    QDir d(authClientsDir());
    if (!d.exists()) return;                       // never paired: open by design
    if (!d.entryList({"*.auth"}, QDir::Files).isEmpty()) return;

    unsigned char pub[32];
    if (RAND_bytes(pub, sizeof pub) != 1) return;
    // base32 (RFC 4648, lowercase, unpadded) — same alphabet tor uses.
    static const char* A = "abcdefghijklmnopqrstuvwxyz234567";
    QString b32; int bits = 0; quint32 acc = 0;
    for (unsigned char ch : pub) {
        acc = (acc << 8) | ch; bits += 8;
        while (bits >= 5) { b32.append(QLatin1Char(A[(acc >> (bits - 5)) & 0x1F])); bits -= 5; }
    }
    if (bits > 0) b32.append(QLatin1Char(A[(acc << (5 - bits)) & 0x1F]));

    QFile f(authClientsDir() + "/_sealed.auth");
    if (f.open(QIODevice::WriteOnly | QIODevice::Truncate)) {
        f.write(QStringLiteral("descriptor:x25519:%1\n").arg(b32).toUtf8());
        f.close();
        QFile::setPermissions(f.fileName(), QFileDevice::ReadOwner | QFileDevice::WriteOwner);
        qInfo() << "OnionService: last client revoked — sealed closed with a discarded key";
    }
}

QStringList OnionService::authorizedClients() const
{
    QStringList out;
    for (const QString& f : QDir(authClientsDir()).entryList({"*.auth"}, QDir::Files)) {
        const QString n = f.left(f.size() - 5);
        if (n == QLatin1String("_sealed")) continue;   // internal deny-all sentinel
        out << n;
    }
    return out;
}

void OnionService::regenerateAddress()
{
    stop();
    QDir(persistentHsDir()).removeRecursively();
    m_onion.clear();
}
