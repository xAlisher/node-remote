#pragma once

// The leader-claim ledger, read from the file logos_node_1click already maintains.
//
// WHY WE READ A FILE INSTEAD OF CALLING FOR IT
// logos_node_1click is a `ui_qml` module, and UI plugins are never callable — only core
// modules register as QRO sources (basecamp MODULE-INTERACTION-GUIDE §1). So its
// getLeaderClaims() is reachable from its own QML and from nowhere else, this module
// included. What it CAN do is persist, and it does: claims-history.json, written beside
// the node's user_config.yaml, which node_probe already resolves.
//
// The rows carry ~700 lines of chain reconciliation we deliberately do not duplicate —
// event matching by tx hash, the fee note-value map, LIB-vs-tip reorg safety, and the
// backfill that recovers claims made before the ledger existed. Two implementations of
// that would be two things to keep in agreement, and the desktop's is the one with the
// dogfooding behind it (logos-blockchain-ui/docs/VOUCHER-STATE-MAP.md).
//
// WHAT THIS COSTS, STATED HONESTLY
// The file only advances while Basecamp runs with the Blockchain pane loaded — the scan
// is driven by a QML Timer in BlockchainView.qml, not by the core module. So the ledger
// can be behind the chain, and the payload says how far behind rather than implying it
// is live. `scanCaughtUp` and `lastScannedSlot` are surfaced for exactly that reason.

#include "pending_claims.h"

#include <QByteArray>
#include <QJsonObject>
#include <QString>

class ClaimsLedger
{
public:
    /// Read and derive the ledger. `userConfigPath` is the node's user_config.yaml —
    /// claims-history.json sits beside it. `libSlot` is the chain's last-immutable slot,
    /// used to age out submissions that never landed; pass 0 when unknown and no row is
    /// aged (an inference we decline to make without the input it needs).
    ///
    /// Returns {claims:[…], summary:{…}, stale:bool, error:"…"}. Never throws, never
    /// writes — the aging and fee backfill below are derivations for THIS answer only.
    /// Ownership of the file stays with logos_node_1click.
    QJsonObject read(const QString& userConfigPath, qint64 libSlot);

    /// Record a claim this module just submitted, so the press is evidenced immediately on
    /// BOTH surfaces rather than only after the chain backfills it. Returns false if the
    /// row could not be persisted — the caller must say so, because a claim that succeeded
    /// and went unrecorded is the one outcome with no trace anywhere.
    bool recordSubmitted(const QString& userConfigPath, const QString& tx, qint64 atSlot)
    {
        return m_pending.add(userConfigPath, tx, atSlot);
    }

private:
    // KEEP-LAST-GOOD. The file is rewritten by ui-host on its own 20s timer while we read
    // it on ours, so a read can land mid-write and parse to nothing. Blanking the ledger
    // on that would make a healthy node look like one that had never claimed — the exact
    // class of lie this feature exists to stop. A torn read reuses the last good parse and
    // reports itself as stale instead.
    QJsonObject m_lastGood;
    bool        m_haveGood = false;

    // Claims submitted from the phone, merged in until the desktop's ledger reports them.
    // Merged BEFORE the fee backfill and the expiry aging below, so a phone-submitted row
    // travels the same pipeline as one the desktop wrote — including aging to expired if
    // it never lands. See pending_claims.h for why it is a separate file.
    PendingClaims m_pending;
};
