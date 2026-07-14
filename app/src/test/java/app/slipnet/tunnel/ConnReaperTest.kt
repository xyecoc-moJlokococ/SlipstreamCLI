package app.slipnet.tunnel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the reap-decision policy behind the NIO bridge's leak fixes:
 *  - jammed-buffer reap: the root cause of renewed CLOSE-WAIT growth -- under write-backpressure the
 *    bridge stops reading the client and never sees its FIN, so only a stuck (non-draining) relay
 *    buffer reveals the connection is dead;
 *  - half-closed reap: quick timeout even while a byte-trickle download refreshes activity;
 *  - fully-open connections keep the generous idle window.
 */
class ConnReaperTest {
    private val full = 90_000L
    private val halfIdle = 15_000L
    private val halfMax = 45_000L
    private val stuck = 30_000L

    private fun reap(now: Long, last: Long, halfAt: Long, bufStuck: Long = 0) =
        ConnReaper.shouldReap(now, last, halfAt, bufStuck, full, halfIdle, halfMax, stuck)

    @Test
    fun fully_open_keeps_the_generous_idle_window() {
        assertFalse(reap(now = 100_000, last = 50_000, halfAt = 0))
        assertTrue(reap(now = 100_000, last = 100_000 - full, halfAt = 0))
    }

    @Test
    fun half_closed_reaped_on_short_idle() {
        val now = 100_000L
        assertTrue(reap(now = now, last = now - 16_000, halfAt = now - 20_000))
        assertFalse(reap(now = now, last = now - 5_000, halfAt = now - 5_000))
    }

    @Test
    fun half_closed_absolute_cap_stops_endless_trickle() {
        val now = 100_000L
        assertTrue(reap(now = now, last = now - 500, halfAt = now - (halfMax + 1)))
        assertFalse(reap(now = now, last = now - 500, halfAt = now - (halfMax - 1_000)))
    }

    @Test
    fun jammed_buffer_reaped_even_when_fully_open_and_recently_active() {
        // The exact field scenario: connection looks "active" (a byte trickled out just now, so
        // lastActivity is fresh) and is not marked half-closed (its FIN was never read because
        // backpressure stopped us reading the client). Only the stuck buffer reveals it's dead.
        val now = 100_000L
        assertTrue(reap(now = now, last = now - 100 /* just trickled */, halfAt = 0, bufStuck = now - (stuck + 1)))
        // A buffer that's been non-empty only briefly (normal in-flight relay) must NOT reap.
        assertFalse(reap(now = now, last = now - 100, halfAt = 0, bufStuck = now - 2_000))
    }

    @Test
    fun empty_buffer_never_triggers_stuck_reap() {
        val now = 100_000L
        assertFalse(reap(now = now, last = now - 1_000, halfAt = 0, bufStuck = 0))
    }

    @Test
    fun boundaries_are_inclusive() {
        val now = 100_000L
        assertTrue(reap(now, now - full, 0))
        assertTrue(reap(now, now - halfIdle, now - 1))
        assertTrue(reap(now, now, now - halfMax))
        assertTrue(reap(now, now, 0, bufStuck = now - stuck)) // exactly stuckMs
    }
}
