#include "pending_claims.h"

#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonDocument>
#include <QJsonObject>
#include <QSaveFile>

namespace {
// Enough to cover the longest plausible settle plus a wide margin. Rows are normally
// removed the moment the ledger reports the tx, so this only bounds a file nobody is
// pruning any more — an uninstalled module, or a desktop that never runs.
constexpr int kMaxRows = 200;
}  // namespace

QString PendingClaims::path(const QString& userConfigPath)
{
    if (userConfigPath.isEmpty()) return {};
    // Beside the node's user_config.yaml, the same place logos_node_1click keeps its
    // ledger. That scopes both files to one node identity, so re-keying a node retires
    // them together instead of leaving ours pointing at a wallet that no longer exists.
    return QFileInfo(userConfigPath).absoluteDir().filePath(QStringLiteral("pending-claims.json"));
}

QJsonArray PendingClaims::load(const QString& p)
{
    QFile f(p);
    if (!f.open(QIODevice::ReadOnly)) return {};
    const QByteArray raw = f.readAll();
    f.close();
    const QJsonDocument doc = QJsonDocument::fromJson(raw);
    if (!doc.isObject()) return {};
    return doc.object().value(QStringLiteral("pending")).toArray();
}

bool PendingClaims::save(const QString& p, const QJsonArray& rows)
{
    // QSaveFile, NOT QFile. It writes a temporary beside the target and renames over it on
    // commit, and POSIX rename is atomic — a reader sees either the whole old file or the
    // whole new one, never a partial. QFile with Truncate empties the file on open(), so
    // any read landing in that window gets zero bytes. The desktop's own store still does
    // exactly that, which is why our reader keeps a last-good copy.
    QSaveFile f(p);
    if (!f.open(QIODevice::WriteOnly)) return false;
    QJsonObject root;
    root.insert(QStringLiteral("pending"), rows);
    if (f.write(QJsonDocument(root).toJson(QJsonDocument::Compact)) < 0) {
        f.cancelWriting();
        return false;
    }
    return f.commit();
}

bool PendingClaims::add(const QString& userConfigPath, const QString& tx, qint64 atSlot)
{
    const QString p = path(userConfigPath);
    if (p.isEmpty() || tx.isEmpty()) return false;

    QJsonArray rows = load(p);

    // Idempotent on tx. The phone retries a lost reply, and the node can only ever have
    // issued that hash once — a duplicate row would show the same claim twice and count
    // its reward twice in any total built from these rows.
    for (const QJsonValue& v : rows)
        if (v.toObject().value(QStringLiteral("tx")).toString() == tx) return true;

    QJsonObject row;
    row.insert(QStringLiteral("tx"), tx);
    // `submitted` is the desktop's own vocabulary for this state, so a merged row needs no
    // translation on either side and flows through the reconciliation and expiry logic
    // that already exists there.
    row.insert(QStringLiteral("status"), QStringLiteral("submitted"));
    row.insert(QStringLiteral("submittedAt"),
               QDateTime::currentDateTime().toString(Qt::ISODate));
    // The tip slot at submission. This is what the expiry inference measures against on
    // BOTH surfaces, so a row we write ages out on the desktop exactly as one it wrote
    // itself — including when this module is gone and nobody is pruning any more.
    row.insert(QStringLiteral("submittedAtSlot"), atSlot);
    // Marks the origin so either surface can say where a claim came from. Nothing depends
    // on it today; it costs one key and answers "did I do this from the phone?".
    row.insert(QStringLiteral("via"), QStringLiteral("node_remote"));

    rows.prepend(row);
    while (rows.size() > kMaxRows) rows.removeLast();

    return save(p, rows);
}

QJsonArray PendingClaims::takeUnsettled(const QString& userConfigPath,
                                        const QSet<QString>& ledgerTxs)
{
    const QString p = path(userConfigPath);
    if (p.isEmpty()) return {};

    const QJsonArray rows = load(p);
    if (rows.isEmpty()) return {};

    QJsonArray keep;
    for (const QJsonValue& v : rows) {
        const QJsonObject row = v.toObject();
        // THE LEDGER WINS. Once it knows a tx, its row carries the block, the slot, the
        // reward and the fee, and ours carries a hash and a guess. Keeping both would
        // double-count the claim in every total computed from the merged list.
        if (ledgerTxs.contains(row.value(QStringLiteral("tx")).toString())) continue;
        keep.append(row);
    }

    // Rewrite only when something actually went. This runs on a poll path, and rewriting
    // an unchanged file every few seconds is pointless disk churn that also widens the
    // window in which a reader could catch a rename.
    if (keep.size() != rows.size()) save(p, keep);

    return keep;
}
