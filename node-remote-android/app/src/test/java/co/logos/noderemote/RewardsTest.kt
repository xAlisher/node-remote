package co.logos.noderemote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rewards contract, pinned. Every case here is a statement the screen makes about
 * money, and each one has a specific way of being subtly false — see
 * logos-blockchain-ui/docs/VOUCHER-STATE-MAP.md.
 */
class RewardsTest {

    // ── units (issue #12) ──────────────────────────────────────────────────────────────
    // The chain's raw u64 IS LGO. There is no sub-unit. These values are read from a real
    // ledger row on this machine, so if anyone reintroduces a base-unit division the
    // numbers stop matching what the desktop prints for the same claim.

    @Test fun raw_u64_is_lgo_and_is_never_divided() {
        assertEquals("9,664 LGO", fmtLgo("9664"))
        assertEquals("4,602 LGO", fmtLgo("4602"))
        // The old code divided by 10,000 and this same input printed "0.97 LGO".
        assertTrue(fmtLgo("9664") != "0.97 LGO")
    }

    @Test fun small_amounts_print_in_full_not_abbreviated() {
        // A reward is ~9,664. "9.66K LGO" is both uglier and less precise than the number,
        // and amounts.js deliberately shows everything under 1e6 in full.
        assertEquals("9,664 LGO", fmtLgo("9664"))
        assertEquals("999,999 LGO", fmtLgo("999999"))
    }

    @Test fun large_balances_still_abbreviate() {
        // 2e12 raw. Under the old base-unit division this printed "200M LGO"; it is 2T.
        assertEquals("2T LGO", fmtLgo("2000000000000"))
        assertEquals("1M LGO", fmtLgo("1000000"))
    }

    @Test fun no_figure_is_not_zero() {
        // Claiming a wallet holds zero when we simply have no figure is a different and
        // wrong statement.
        assertEquals("— LGO", fmtLgo(""))
        assertEquals("— LGO", fmtLgo("not-a-number"))
    }

    // ── an unknown fee is unknown, never zero ─────────────────────────────────────────

    @Test fun missing_fee_stays_null_and_net_is_unknown() {
        val c = Claim.list(
            org.json.JSONArray("""[{"tx":"ab","status":"settled","reward":9664}]""")
        ).single()
        assertNull(c.fee)
        // net must be unknown too — not 9664. A claim certainly paid a fee; we just
        // cannot price it, because the chain records only the spent note's id.
        assertNull(c.net)
    }

    @Test fun present_fee_gives_a_real_net() {
        val c = Claim.list(
            org.json.JSONArray("""[{"tx":"ab","status":"settled","reward":9664,"fee":4602}]""")
        ).single()
        assertEquals(4602L, c.fee)
        assertEquals(5062L, c.net)
    }

    @Test fun a_zero_fee_is_a_real_zero_and_not_confused_with_unknown() {
        val c = Claim.list(
            org.json.JSONArray("""[{"tx":"ab","status":"settled","reward":10,"fee":0}]""")
        ).single()
        assertEquals(0L, c.fee)
        assertEquals(10L, c.net)
    }

    // ── the badge ─────────────────────────────────────────────────────────────────────

    @Test fun badge_is_absent_when_nothing_is_ready_and_when_nothing_is_known() {
        // 0 ready and "not read yet" are DIFFERENT states, but neither of them means
        // "you have vouchers waiting", which is the only thing a badge should say.
        assertNull(Rewards.parse("""{"ready":0}""").badge)
        assertNull(Rewards.parse("""{"ready":-1}""").badge)
        assertNull(Rewards.parse("""{}""").badge)
        assertEquals(12, Rewards.parse("""{"ready":12}""").badge)
    }

    // ── the summary must not overstate ────────────────────────────────────────────────

    @Test fun partial_scan_and_incomplete_fees_are_carried_through() {
        val r = Rewards.parse(
            """{"ready":3,"summary":{"settled":10,"claimed":95188,"fees":0,
                 "feesComplete":false,"net":95188,"lastScannedSlot":900,"libSlot":1000,
                 "scanCaughtUp":false}}"""
        )
        assertEquals(false, r.feesComplete)   // net is a ceiling, not a total
        assertEquals(false, r.scanCaughtUp)   // claimed is partial
        assertEquals(900L, r.lastScannedSlot)
        assertEquals(1000L, r.libSlot)
    }

    @Test fun a_ledger_a_few_slots_behind_lib_is_current_not_partial() {
        // The strict flag is essentially never true on a running node: LIB advances about a
        // slot a second while the desktop's scan runs every 20s. Measured live at 39 behind.
        // Reading the strict flag as "totals are partial" puts a warning on the screen that
        // never turns off, which understates a ledger that is in fact current.
        val near = Rewards.parse("{\"summary\":{\"scanCaughtUp\":false,\"slotsBehind\":39}}")
        assertTrue(near.ledgerCurrent)

        // Genuinely behind stays genuinely behind, and the reader gets the distance.
        val far = Rewards.parse("{\"summary\":{\"scanCaughtUp\":false,\"slotsBehind\":5000}}")
        assertFalse(far.ledgerCurrent)

        // Exactly caught up is current whatever the distance field says.
        val exact = Rewards.parse("{\"summary\":{\"scanCaughtUp\":true,\"slotsBehind\":0}}")
        assertTrue(exact.ledgerCurrent)

        // Unknown chain position must NOT read as current: -1 means "we do not know",
        // which is not the same as "nothing is outstanding".
        val unknown = Rewards.parse("{\"summary\":{\"scanCaughtUp\":false,\"slotsBehind\":-1}}")
        assertFalse(unknown.ledgerCurrent)
    }

    @Test fun an_unparseable_frame_reports_itself_rather_than_reading_as_empty() {
        val r = Rewards.parse("not json")
        assertTrue(r.answered)
        assertEquals("unparseable", r.error)
        assertNull(r.badge)
    }

    @Test fun expiry_is_marked_as_inferred() {
        // An unlanded claim leaves nothing on chain to observe, so expiry is a deduction
        // and the row says so instead of asserting it.
        val c = Claim.list(
            org.json.JSONArray("""[{"tx":"ab","status":"expired","inferred":true}]""")
        ).single()
        assertEquals("expired", c.status)
        assertTrue(c.inferred)
    }
}
