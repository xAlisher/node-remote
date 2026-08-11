#include "onion_service.h"

#include <QCoreApplication>
#include <QDebug>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QProcessEnvironment>
#include <QStandardPaths>
#include <QTextStream>

#ifdef Q_OS_LINUX
#include <csignal>
#include <sys/prctl.h>
#endif

namespace {

// Owner-only on every directory that holds tor state or key material (Senty FINDING-4).
constexpr QFileDevice::Permissions kOwnerOnlyDir =
    QFileDevice::ReadOwner | QFileDevice::WriteOwner | QFileDevice::ExeOwner;

// Resolve a bundled helper: <moduleDir>/bin/<name> first (the .lgx ships tor there via the
// flake postInstall), then $NODE_REMOTE_TOR_BIN, then PATH.
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

// The AppImage's bundled libs break system tor (it needs system libevent/openssl).
// Strip the loader vars so the spawned process resolves against the system.
QProcessEnvironment cleanSpawnEnv()
{
    QProcessEnvironment env = QProcessEnvironment::systemEnvironment();
    for (const char* v : {"LD_LIBRARY_PATH", "LD_PRELOAD", "QT_PLUGIN_PATH",
                          "QML2_IMPORT_PATH", "GST_PLUGIN_SYSTEM_PATH"})
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

bool fileContains(const QString& path, const char* a, const char* b)
{
    QFile f(path);
    if (!f.open(QIODevice::ReadOnly)) return false;
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

bool OnionService::spawnTor(const QString& cfg, QString& errOut)
{
    const QString bin     = resolveTor();
    const QString dataDir = m_runDir + "/data";
    const QString torrc   = m_runDir + "/torrc";

    // Any failure leaves nothing behind (Senty ISSUE-5).
    auto fail = [&](const QString& code) {
        QDir(m_runDir).removeRecursively();
        errOut = code;
        return false;
    };

    if (!QDir().mkpath(dataDir)) return fail(QStringLiteral("tor_dir_failed"));
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
    if (m_tor && m_tor->state() == QProcess::Running) return QString();

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
      << "DataDirectory " << m_runDir << "/data\n"
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

    m_onion.clear();
    m_ready = false;
    m_error.clear();
    m_ticks = 0;
    m_bootstrappedAt = 0;
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

    // Bounded (Senty ISSUE-3): ~120s, then surface a real timeout rather than polling forever.
    if (++m_ticks > 60) {
        m_poll.stop();
        if (!m_ready) {
            m_error = QStringLiteral("publish_timeout");
            qWarning() << "OnionService: onion descriptor publish timed out";
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
    bool ok = fileContains(m_runDir + "/hs.log", "upload", "descriptor");
    // Fallback, robust to tor wording changes: once bootstrapped 100% the descriptor
    // publishes within tens of seconds — accept after a grace so a live onion is never missed.
    if (!ok && fileContains(m_runDir + "/tor.log", "Bootstrapped 100%", nullptr)) {
        if (m_bootstrappedAt == 0) m_bootstrappedAt = m_ticks;
        if (m_ticks - m_bootstrappedAt >= 12) ok = true;   // ~24s after 100%
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
    return QFile::remove(authClientsDir() + "/" + name + ".auth");
}

QStringList OnionService::authorizedClients() const
{
    QStringList out;
    for (const QString& f : QDir(authClientsDir()).entryList({"*.auth"}, QDir::Files))
        out << f.left(f.size() - 5);
    return out;
}

void OnionService::regenerateAddress()
{
    stop();
    QDir(persistentHsDir()).removeRecursively();
    m_onion.clear();
}
