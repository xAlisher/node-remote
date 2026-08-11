#pragma once

// Blocks + proposals, mirroring logos_node_1click's data structures EXACTLY so the phone
// and the desktop tab show the same fields under the same names.
//
// Blocks come from blockchain_module's `newBlock` event; proposals are scraped out of the
// node's own logs, because blockchain_module exposes no proposals API. Both parsers are
// lifted from logos-blockchain-ui (BlockModel.cpp / backend getProposals) rather than
// re-derived — the block payload in particular is double-encoded JSON with three observed
// shapes, and getting that subtly wrong yields silently empty rows.

#include <QByteArray>
#include <QJsonObject>
#include <QList>
#include <QString>

struct BlockEntry {
    // Field names match BlockModel's roleNames() one-for-one.
    QString timestamp, slot, version, parentBlock, blockRoot;
    QString leaderKey, entropy, proof, voucherCm, signature;
    int     txCount = 0;
    QStringList transactions;   // prettified JSON per tx
    QString rawJson;
    bool    parsed = false;
};

class BlockStore
{
public:
    // 100 matches logos_node_1click's BlockModel::kMaxBlocks. Newest first.
    static constexpr int kMax = 100;

    void append(const QString& tsIso, const QString& rawPayload);
    void clear() { m_blocks.clear(); }
    int  count() const { return m_blocks.size(); }

    /// [{timestamp,slot,version,parentBlock,blockRoot,leaderKey,entropy,proof,
    ///   voucherCm,signature,txCount,transactions[],rawJson,parsed}, …]
    QByteArray json(int limit) const;

    /// Scrape "proposed block HeaderId(...) with N transactions (M removed)" out of the
    /// node's logs. [{id,txs,removed,time}, …] — same four fields the desktop shows.
    static QByteArray proposalsJson(const QString& logDir, int limit);

private:
    QList<BlockEntry> m_blocks;   // index 0 == newest
};
