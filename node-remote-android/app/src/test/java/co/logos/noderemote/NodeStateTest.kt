package co.logos.noderemote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the honest-state contract (issues node-remote#1/#2). No Android
 * framework, no device — just the NodeState parse/helpers and the transition diff that
 * decides notifications.
 */
class NodeStateTest {

    private fun frame(status: String, extra: String = "") =
        NodeState.parse("""{"reachable":false,"status":"$status"$extra}""", 1000L)

    @Test fun stopped_is_neutral_never_error() {
        // A deliberately stopped node: the module emits status Stopped and NO error, even
        // though a stale fatal line may sit in the log. The phone must read it as stopped.
        val s = frame("Stopped", ""","intent":"stopped"""")
        assertTrue(s.isStopped())
        assertFalse(s.isRecovering())
        assertFalse(s.isStarting())
        assertEquals("", s.error)   // never inherits a stale error
    }

    @Test fun error_state_carries_the_cause() {
        val s = frame("Error", ""","intent":"started","error":"The node config couldn't be parsed."""")
        assertEquals("Error", s.status)
        assertTrue(s.error.isNotEmpty())
        assertFalse(s.isStopped())
    }

    @Test fun recovering_exposes_the_block_count() {
        val s = frame("Recovering",
            ""","recovering":{"active":true,"blocks":4242},"notice":"Replaying 4242 stored blocks…"""")
        assertTrue(s.isRecovering())
        assertEquals(4242, s.recoveryBlocks)
        // Recovering is NOT a plain start — the two must stay distinct so the label is honest.
        assertFalse(s.isStarting())
        assertFalse(s.isStopped())
    }

    @Test fun starting_is_distinct_from_recovering() {
        val s = frame("Starting", ""","intent":"started"""")
        assertTrue(s.isStarting())
        assertFalse(s.isRecovering())   // no "Recovering chain" label for a plain startup
    }

    @Test fun bootstrapping_is_running_ish() {
        val s = NodeState.parse(
            """{"reachable":true,"status":"Bootstrapping","state":"Bootstrapping"}""", 1000L)
        assertTrue(s.reachable)
        assertFalse(s.isStopped())
    }

    @Test fun deliberate_stop_fires_node_stopped_notification() {
        // The old both-reachable guard could never fire this: a stopped node has
        // reachable=false. A desktop-answered Stopped after a running frame IS a stop.
        val prev = NodeState.parse("""{"reachable":true,"status":"Running","state":"Online"}""", 1L)
        val cur = frame("Stopped", ""","intent":"stopped"""")
        val notices = Transitions.diff(prev, cur, null)
        assertTrue(notices.any { it.event == Event.NODE_STOPPED })
    }

    @Test fun link_loss_is_not_a_stop() {
        // Desktop unreachable (answered=false) must NOT read as a node stop.
        val prev = NodeState.parse("""{"reachable":true,"status":"Running","state":"Online"}""", 1L)
        val cur = NodeState.unreachable(2L, "timeout")
        val notices = Transitions.diff(prev, cur, null)
        assertFalse(notices.any { it.event == Event.NODE_STOPPED })
        assertTrue(notices.any { it.event == Event.LINK_LOST })
    }
}
