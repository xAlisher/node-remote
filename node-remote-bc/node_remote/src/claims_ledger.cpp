#include "claims_ledger.h"

#include <QFile>
#include <QFileInfo>
#include <QDir>
#include <QJsonArray>
#include <QJsonDocument>
#include <QSet>

#include <algorithm>

namespace {

// All lifted from logos_node_1click_backend.cpp so the two surfaces classify a row
// identically. A phone that called a claim "expired" while the desktop still called it
// "submitted" would be worse than showing nothing.
constexpr int kExpiryLookaheadSlots = 20000;
constexpr int kMaxClaimRows         = 2000;
// The desktop's alarm recency window (~2 epochs): only failures this fresh count toward
// the stale-state alarm, and only settles this fresh veto it.
constexpr qint64 kAlarmWindowSlots  = 72000;

qint64 num(const QJsonObject& o, const char* k)
{
    return static_cast<qint64>(o.value(QLatin1String(k)).toDouble());
}

} // namespace

QJsonObject ClaimsLedger::read(const QString& userConfigPath, qint64 libSlot)
{
    QJsonObject out;

    if (userConfigPath.isEmpty()) {
        out["error"] = QStringLiteral("no user_config.yaml found — configure the node on the desktop first");
        out["claims"] = QJsonArray();
        out["summary"] = QJsonObject();
        return out;
    }

    // Beside user_config.yaml — the location logos_node_1click chose, which scopes the
    // ledger to that node identity. Following it rather than picking our own means a
    // re-keyed node gets a fresh ledger on both surfaces at the same moment.
    const QString path =
        QFileInfo(userConfigPath).absoluteDir().filePath(QStringLiteral("claims-history.json"));

    QJsonObject store;
    bool fresh = false;

    QFile f(path);
    if (f.open(QIODevice::ReadOnly)) {
        const QByteArray raw = f.readAll();
        f.close();
        QJsonParseError pe{};
        const QJsonDocument doc = QJsonDocument::fromJson(raw, &pe);
        if (pe.error == QJsonParseError::NoError && doc.isObject()) {
            store = doc.object();
            m_lastGood = store;
            m_haveGood = true;
            fresh = true;
        }
    }

    if (!fresh) {
        if (!m_haveGood) {
            // No file and nothing cached. This is the ordinary state on a machine where
            // the Blockchain pane has never been opened, so it is reported as a reason
            // rather than as an error — there is nothing wrong, just nothing recorded.
            out["error"] = QFile::exists(path)
                ? QStringLiteral("the claims ledger could not be read")
                : QStringLiteral("no claims ledger yet — open the Blockchain node app on "
                                 "the desktop once to start recording claims");
            out["claims"] = QJsonArray();
            out["summary"] = QJsonObject();
            out["stale"] = true;
            return out;
        }
        store = m_lastGood;
    }
    out["stale"] = !fresh;

    QJsonArray claims = store.value(QStringLiteral("claims")).toArray();
    const QJsonObject noteValues = store.value(QStringLiteral("noteValues")).toObject();

    // --- merge claims submitted from the phone ----------------------------
    // Merged HERE, immediately after the load and before everything below, so a
    // phone-submitted row is indistinguishable from one the desktop wrote by the time the
    // fee backfill, the expiry aging, the sort and the summary run over it.
    //
    // The desktop records a write-ahead row when ITS button is pressed; a phone press has
    // no such hook, so without this the claim is invisible on both surfaces for the ~2h
    // until chain backfill recovers it as settled — and a press that produces no visible
    // change is what turned one intended claim into nine over there.
    //
    // Every tx already in the ledger is dropped from our file as a side effect: the ledger
    // row carries the block, slot, reward and fee, ours carries a hash and a timestamp, so
    // holding both would count the claim twice in `claimed`.
    {
        QSet<QString> ledgerTxs;
        for (const QJsonValue& v : claims) {
            const QString tx = v.toObject().value(QStringLiteral("tx")).toString();
            if (!tx.isEmpty()) ledgerTxs.insert(tx);
        }
        // Rows the user archived with the desktop's Clear log (0.2.20,
        // logos-blockchain-ui#50) count as "already in the ledger" too — a cleared
        // claim must not resurrect here as a live phone-pending row.
        for (const QJsonValue& v : store.value(QStringLiteral("archived")).toArray()) {
            const QString tx = v.toObject().value(QStringLiteral("tx")).toString();
            if (!tx.isEmpty()) ledgerTxs.insert(tx);
        }
        const QJsonArray pending = m_pending.takeUnsettled(userConfigPath, ledgerTxs);
        for (const QJsonValue& v : pending) claims.append(v);
    }

    // --- fee backfill -----------------------------------------------------
    // A fee is the spent note minus its change, and the block records only the note's
    // ID — so a fee is knowable only while we still remember what that note was worth.
    // Rows the desktop has already priced arrive with `fee` set; this fills the ones it
    // priced after writing the row. A fee we cannot compute stays ABSENT, never zero:
    // the phone renders a missing fee as unknown, and a zero would be a false statement
    // about money (VOUCHER-STATE-MAP §3).
    for (int i = 0; i < claims.size(); ++i) {
        QJsonObject row = claims.at(i).toObject();
        if (row.contains(QStringLiteral("fee"))) continue;
        const QString in = row.value(QStringLiteral("feeInput")).toString();
        if (in.isEmpty() || !noteValues.contains(in)) continue;
        const qint64 spent  = static_cast<qint64>(noteValues.value(in).toDouble());
        const qint64 change = num(row, "feeChange");
        if (spent >= change && change >= 0) {
            row.insert(QStringLiteral("fee"), spent - change);
            claims.replace(i, row);
        }
    }

    // --- age out submissions that never landed ----------------------------
    // An unlanded claim produces nothing to observe, so aging can only be inferred —
    // and since 0.2.19 the desktop treats that inference as a SUSPICION, never a
    // verdict: the row becomes "checking" ("Confirming…" on screen) and only the
    // explorer pass may pronounce settled or failed. This block used to write
    // "expired" here, which put a verdict on the phone the desktop had already
    // abandoned for being wrong 20 times out of 137 (logos-blockchain-ui#47).
    // The row stays marked `inferred` so a renderer can say so.
    if (libSlot > 0) {
        for (int i = 0; i < claims.size(); ++i) {
            QJsonObject row = claims.at(i).toObject();
            if (row.value(QStringLiteral("status")).toString() != QLatin1String("submitted"))
                continue;
            const qint64 at = num(row, "submittedAtSlot");
            if (at > 0 && libSlot > at + kExpiryLookaheadSlots) {
                row.insert(QStringLiteral("status"), QStringLiteral("checking"));
                row.insert(QStringLiteral("inferred"), true);
                claims.replace(i, row);
            }
        }
    }

    // --- newest first -----------------------------------------------------
    // By the slot the row actually happened at: its block slot once settled, otherwise
    // the tip slot at submission. Both are the same clock, so an in-flight claim sorts
    // above older settled ones — which is how a ledger should read.
    {
        QList<QJsonObject> rows;
        rows.reserve(claims.size());
        for (const QJsonValue& v : claims) rows.append(v.toObject());
        const auto effSlot = [](const QJsonObject& r) {
            const qint64 s = num(r, "slot");
            return s > 0 ? s : num(r, "submittedAtSlot");
        };
        std::stable_sort(rows.begin(), rows.end(),
                         [&](const QJsonObject& a, const QJsonObject& b) {
                             return effSlot(a) > effSlot(b);
                         });
        claims = QJsonArray();
        for (const QJsonObject& r : rows) claims.append(r);
    }

    while (claims.size() > kMaxClaimRows) claims.removeLast();

    // --- summary ----------------------------------------------------------
    qint64 claimed = 0, fees = 0;
    int settled = 0, inFlight = 0, feesKnown = 0, checking = 0, failedVerified = 0;
    for (const QJsonValue& v : claims) {
        const QJsonObject r = v.toObject();
        const QString st = r.value(QStringLiteral("status")).toString();
        if (st == QLatin1String("settled")) {
            ++settled;
            claimed += num(r, "reward");
            if (r.contains(QStringLiteral("fee"))) { fees += num(r, "fee"); ++feesKnown; }
        } else if (st == QLatin1String("submitted") || st == QLatin1String("in_block")) {
            ++inFlight;
        } else if (st == QLatin1String("checking") || st == QLatin1String("expired")) {
            ++checking;
        } else if (st == QLatin1String("failed")) {
            // Only RECENT explorer-verified failures count toward the alarm —
            // historical ones are records, not a condition (desktop 0.2.20 rule).
            const qint64 at = num(r, "submittedAtSlot");
            if (at > 0 && libSlot > 0 && libSlot - at < kAlarmWindowSlots)
                ++failedVerified;
        }
    }

    // The alarm looks THROUGH the desktop's archive: clearing the list must not
    // silence a live failure streak — and a recent settle (or a claim verified in a
    // block at the tip) anywhere, archived included, VETOES it: stale wallet state
    // cannot land anything, so one landing disproves the diagnosis. Exactly the
    // desktop's computation, so the two surfaces can never disagree on the alarm.
    {
        const QJsonArray archived = store.value(QStringLiteral("archived")).toArray();
        for (const QJsonValue& v : archived) {
            const QJsonObject r = v.toObject();
            if (r.value(QStringLiteral("status")).toString() != QLatin1String("failed"))
                continue;
            const qint64 at = num(r, "submittedAtSlot");
            if (at > 0 && libSlot > 0 && libSlot - at < kAlarmWindowSlots)
                ++failedVerified;
        }
        const auto landedRecently = [&](const QJsonArray& rows) {
            for (const QJsonValue& v : rows) {
                const QJsonObject r = v.toObject();
                const QString rs = r.value(QStringLiteral("status")).toString();
                if (rs != QLatin1String("settled") && rs != QLatin1String("in_block"))
                    continue;
                qint64 at = num(r, "slot");
                if (at <= 0) at = num(r, "submittedAtSlot");
                if (at > 0 && libSlot > 0 && libSlot - at < kAlarmWindowSlots)
                    return true;
            }
            return false;
        };
        if (landedRecently(claims) || landedRecently(archived))
            failedVerified = 0;
    }

    const qint64 lastScanned = num(store, "lastScannedSlot");

    QJsonObject summary;
    summary.insert(QStringLiteral("settled"),  settled);
    summary.insert(QStringLiteral("inFlight"), inFlight);
    summary.insert(QStringLiteral("checking"), checking);
    // >=2 is the stale-wallet-state signature (the 08-24 incident); the phone renders
    // the rescan alarm from this and nothing else. Red is reserved for it.
    summary.insert(QStringLiteral("failedVerified"), failedVerified);
    summary.insert(QStringLiteral("claimed"),  claimed);
    summary.insert(QStringLiteral("fees"),     fees);
    // Net is honest only when every settled row has a known fee. When it is not, the
    // phone must present net as a ceiling — fees can only grow — not as a total.
    summary.insert(QStringLiteral("feesComplete"), feesKnown == settled);
    summary.insert(QStringLiteral("net"), claimed - fees);
    summary.insert(QStringLiteral("historyFromSlot"), num(store, "historyFromSlot"));
    summary.insert(QStringLiteral("lastScannedSlot"), lastScanned);
    summary.insert(QStringLiteral("libSlot"), libSlot);
    // Computed EXACTLY as logos_node_1click computes it, so the shared field can never
    // disagree between the two surfaces.
    summary.insert(QStringLiteral("scanCaughtUp"), libSlot > 0 && lastScanned >= libSlot);
    // ...but that flag alone is not enough to phrase anything with, and reading it as
    // "totals are partial" puts a warning on screen that never turns off. LIB advances
    // about a slot a second while the desktop's scan runs every 20s, so on a running node
    // `lastScanned >= libSlot` is essentially never true: measured live at 39 slots behind,
    // which is 39 seconds of chain and complete for every purpose a person has. A warning
    // that is always on is one nobody reads, and it understates a ledger that is in fact
    // current.
    //
    // So the DISTANCE goes out alongside the flag, and the phone phrases from the distance.
    // Adding a field rather than loosening scanCaughtUp keeps the desktop's semantics
    // untouched — the two surfaces still answer that question identically.
    summary.insert(QStringLiteral("slotsBehind"),
                   libSlot > 0 ? qMax<qint64>(0, libSlot - lastScanned) : -1);

    out.insert(QStringLiteral("claims"), claims);
    out.insert(QStringLiteral("summary"), summary);
    return out;
}
