#include "block_store.h"

#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonParseError>
#include <QRegularExpression>
#include <QSet>

namespace {

QString prettify(const QJsonValue& v)
{
    if (v.isObject()) return QString::fromUtf8(QJsonDocument(v.toObject()).toJson(QJsonDocument::Indented));
    if (v.isArray())  return QString::fromUtf8(QJsonDocument(v.toArray()).toJson(QJsonDocument::Indented));
    return v.toString();
}

}  // namespace

void BlockStore::append(const QString& tsIso, const QString& rawPayload)
{
    BlockEntry e;
    e.timestamp = tsIso;

    // The payload is DOUBLE-ENCODED and arrives in three observed shapes:
    //   {"block": "<stringified block json>"}   (the common one)
    //   {"block": { … }}                        (already an object)
    //   {"header": …, "transactions": …}        (block sent directly)
    // Handling only the first is the trap — the others parse to an empty row with no error.
    QJsonObject block;
    bool ok = false;
    const QJsonDocument outer = QJsonDocument::fromJson(rawPayload.toUtf8());
    if (outer.isObject()) {
        const QJsonObject o = outer.object();
        if (o.contains(QStringLiteral("block"))) {
            const QJsonValue bv = o.value(QStringLiteral("block"));
            if (bv.isString()) {
                QJsonParseError err{};
                const QJsonDocument inner = QJsonDocument::fromJson(bv.toString().toUtf8(), &err);
                if (err.error == QJsonParseError::NoError && inner.isObject()) {
                    block = inner.object();
                    ok = true;
                }
            } else if (bv.isObject()) {
                block = bv.toObject();
                ok = true;
            }
        } else if (o.contains(QStringLiteral("header"))) {
            block = o;
            ok = true;
        }
    }

    if (ok) {
        e.parsed = true;
        const QJsonObject header = block.value(QStringLiteral("header")).toObject();
        e.version     = header.value(QStringLiteral("version")).toString();
        e.parentBlock = header.value(QStringLiteral("parent_block")).toString();
        e.blockRoot   = header.value(QStringLiteral("block_root")).toString();

        const QJsonValue slotV = header.value(QStringLiteral("slot"));
        e.slot = slotV.isDouble() ? QString::number(static_cast<qlonglong>(slotV.toDouble()))
                                  : slotV.toString();

        const QJsonObject pol = header.value(QStringLiteral("proof_of_leadership")).toObject();
        e.proof     = pol.value(QStringLiteral("proof")).toString();
        e.entropy   = pol.value(QStringLiteral("entropy_contribution")).toString();
        e.leaderKey = pol.value(QStringLiteral("leader_key")).toString();
        e.voucherCm = pol.value(QStringLiteral("voucher_cm")).toString();

        e.signature = block.value(QStringLiteral("signature")).toString();

        const QJsonArray txs = block.value(QStringLiteral("transactions")).toArray();
        e.txCount = txs.size();
        for (const QJsonValue tx : txs) e.transactions << prettify(tx);

        e.rawJson = QString::fromUtf8(QJsonDocument(block).toJson(QJsonDocument::Indented));
    } else {
        // Keep the row and mark parsed=false rather than dropping it — a block we cannot
        // read is information, and silently discarding it looks like the node went quiet.
        e.rawJson = rawPayload;
    }

    // The parse above ran on LOCALS. Only the list mutation needs the lock, so a slow
    // double-JSON parse never blocks a reader.
    QMutexLocker lk(&m_mu);
    m_blocks.prepend(e);                       // newest first, like BlockModel
    while (m_blocks.size() > kMax) m_blocks.removeLast();
}

QByteArray BlockStore::json(int limit) const
{
    // Snapshot under the lock, serialise outside it. Copying the entries takes atomic
    // refcount increments on their QStrings while the writer is excluded, so the snapshot
    // keeps its own valid references even if append() trims the originals afterwards.
    // Serialising under the lock would work too, but would stall the block callback for
    // the whole of a 100-entry JSON build.
    QList<BlockEntry> snap;
    {
        QMutexLocker lk(&m_mu);
        const int n = limit > 0 ? qMin(limit, m_blocks.size()) : m_blocks.size();
        snap.reserve(n);
        for (int i = 0; i < n; ++i) snap.append(m_blocks.at(i));
    }

    QJsonArray arr;
    for (int i = 0; i < snap.size(); ++i) {
        const BlockEntry& e = snap.at(i);
        QJsonArray txs;
        for (const QString& t : e.transactions) txs.append(t);
        arr.append(QJsonObject{
            {"timestamp", e.timestamp}, {"slot", e.slot}, {"version", e.version},
            {"parentBlock", e.parentBlock}, {"blockRoot", e.blockRoot},
            {"leaderKey", e.leaderKey}, {"entropy", e.entropy}, {"proof", e.proof},
            {"voucherCm", e.voucherCm}, {"signature", e.signature},
            {"txCount", e.txCount}, {"transactions", txs},
            {"rawJson", e.rawJson}, {"parsed", e.parsed},
        });
    }
    return QJsonDocument(arr).toJson(QJsonDocument::Compact);
}

QByteArray BlockStore::proposalsJson(const QString& logDir, int limit)
{
    QJsonArray arr;
    if (logDir.isEmpty()) return QJsonDocument(arr).toJson(QJsonDocument::Compact);

    static const QRegularExpression propRe(QStringLiteral(
        "proposed block HeaderId\\(([0-9a-f]+)\\) with (\\d+) transactions \\((\\d+) removed\\)"));
    static const QRegularExpression tsRe(QStringLiteral("(\\d{4}-\\d{2}-\\d{2}T[0-9:.]+)"));

    QDir d(logDir);
    QFileInfoList files = d.entryInfoList({"*.log", "*.txt", "*"}, QDir::Files, QDir::Time);

    QSet<QString> seen;
    int scanned = 0;
    for (const QFileInfo& fi : files) {
        if (scanned++ >= 240) break;                  // logs rotate hourly; bound the work
        QFile f(fi.absoluteFilePath());
        if (!f.open(QIODevice::ReadOnly | QIODevice::Text)) continue;
        // Only the tail: proposals we care about are recent, and a full read of a rotated
        // log set is unbounded work on a poll path.
        const qint64 tail = qMin<qint64>(f.size(), 1024 * 1024);
        f.seek(f.size() - tail);
        const QStringList lines = QString::fromUtf8(f.readAll()).split(QLatin1Char('\n'));
        f.close();

        for (const QString& ln : lines) {
            const auto m = propRe.match(ln);
            if (!m.hasMatch()) continue;
            const QString id = m.captured(1);
            if (seen.contains(id)) continue;
            seen.insert(id);
            const auto tm = tsRe.match(ln);
            arr.append(QJsonObject{
                {"id", id},
                {"txs", m.captured(2).toInt()},
                {"removed", m.captured(3).toInt()},
                {"time", tm.hasMatch()
                             ? QString(tm.captured(1)).replace(QLatin1Char('T'), QLatin1Char(' '))
                             : QString()},
            });
            if (limit > 0 && arr.size() >= limit) break;
        }
        if (limit > 0 && arr.size() >= limit) break;
    }
    return QJsonDocument(arr).toJson(QJsonDocument::Compact);
}
