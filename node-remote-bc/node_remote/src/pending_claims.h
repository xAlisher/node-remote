#pragma once

// Claims submitted FROM THE PHONE, held until the desktop's ledger has seen them.
//
// WHY THIS FILE EXISTS
// `leader_claim()` returns a tx hash and nothing else, and the claim leaves no other trace
// on the machine — `leader_claim` appears zero times in the Basecamp log. The desktop's
// ledger records a write-ahead row when the DESKTOP button is pressed; a phone press has no
// such hook, so without this the claim would be invisible on both surfaces for the ~2 hours
// until chain backfill recovers it as settled. A press that produces no visible change is
// exactly what turned one intended claim into nine on the desktop.
//
// WHY A SEPARATE FILE AND NOT A ROW IN claims-history.json
// That file is written by `logos_node_1click` with truncate-then-write, on a 20s timer, in
// a load → mutate → save cycle. A second writer would race it two ways: a row appended
// between its load and its save is silently lost, and two unsynchronised truncating writes
// can interleave and leave a corrupt ledger — strictly worse than the gap being closed.
//
// So: ONE WRITER PER FILE. `logos_node_1click` owns claims-history.json; this module owns
// pending-claims.json. Each side reads both and merges, so both surfaces show the same
// rows, and there is no lock to get wrong because there is never contention.
//
// NEITHER SIDE REQUIRES THE OTHER. A missing file reads as an empty list on both sides. If
// this module is uninstalled with rows outstanding, the desktop ages them out through the
// expiry inference it already applies to any `submitted` row — an orphaned file cannot
// produce a permanent phantom.

#include <QJsonArray>
#include <QSet>
#include <QString>

class PendingClaims
{
public:
    /// Record a submitted claim. Written atomically; returns false if it could not be
    /// persisted, which the caller must surface — an unrecorded press is the one failure
    /// that leaves no evidence anywhere.
    bool add(const QString& userConfigPath, const QString& tx, qint64 atSlot);

    /// Rows not yet present in the desktop's ledger, as claim-shaped objects ready to merge.
    /// Any row whose tx `ledgerTxs` already contains is dropped and the file rewritten —
    /// the ledger is authoritative the moment it knows about a claim, and keeping our copy
    /// would double-count it.
    QJsonArray takeUnsettled(const QString& userConfigPath, const QSet<QString>& ledgerTxs);

private:
    static QString path(const QString& userConfigPath);
    static QJsonArray load(const QString& path);
    static bool       save(const QString& path, const QJsonArray& rows);
};
