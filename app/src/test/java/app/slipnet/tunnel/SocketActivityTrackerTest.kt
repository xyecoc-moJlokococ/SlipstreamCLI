package app.slipnet.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the eviction-selection logic behind MiniSlipstreamSocksBridge's overload cleanup: it
 * used to always kill the OLDEST-opened sockets (`clients.take(N)`), which meant a long-lived but
 * actively-used connection (e.g. a game's persistent session) was consistently the first thing
 * sacrificed once the bridge hit its concurrent-connection cap -- simply for having been open a
 * while, regardless of whether it was still useful. This is keyed by plain Strings rather than
 * real Sockets: the selection logic is pure and doesn't care what the key type is.
 */
class SocketActivityTrackerTest {

    @Test
    fun an_untouched_candidate_is_treated_as_maximally_idle() {
        val tracker = SocketActivityTracker<String>()
        tracker.touch("recent", now = 1_000L)
        // "never-touched" has no recorded activity at all -- should still be selectable, and
        // should sort before "recent" since it defaults to activity=0.
        val victims = tracker.selectLeastRecentlyActive(listOf("recent", "never-touched"), count = 1)
        assertEquals(listOf("never-touched"), victims)
    }

    @Test
    fun the_least_recently_active_candidate_is_selected_first_regardless_of_insertion_order() {
        val tracker = SocketActivityTracker<String>()
        // Deliberately touch in an order that does NOT match insertion order below, to prove
        // this isn't secretly still "oldest opened" in disguise.
        tracker.touch("b", now = 100L)
        tracker.touch("a", now = 300L)
        tracker.touch("c", now = 50L)

        val victims = tracker.selectLeastRecentlyActive(listOf("a", "b", "c"), count = 2)

        assertEquals(listOf("c", "b"), victims)
    }

    @Test
    fun a_long_lived_but_recently_active_connection_survives_over_a_freshly_opened_idle_one() {
        val tracker = SocketActivityTracker<String>()
        // "game" opened long ago (activity recorded at t=0) but just sent/received data (t=900).
        // "idle-newcomer" opened much more recently (t=800) but has gone quiet since.
        tracker.touch("game", now = 0L)
        tracker.touch("idle-newcomer", now = 800L)
        tracker.touch("game", now = 900L) // fresh activity -- e.g. a keepalive or game update

        val victims = tracker.selectLeastRecentlyActive(listOf("game", "idle-newcomer"), count = 1)

        assertEquals(listOf("idle-newcomer"), victims)
    }

    @Test
    fun removed_keys_fall_back_to_maximally_idle() {
        val tracker = SocketActivityTracker<String>()
        tracker.touch("a", now = 1_000L)
        tracker.touch("b", now = 2_000L)
        tracker.remove("a") // simulates the connection having been torn down

        val victims = tracker.selectLeastRecentlyActive(listOf("a", "b"), count = 1)

        assertEquals(listOf("a"), victims)
    }

    @Test
    fun requesting_more_than_available_returns_everything_sorted() {
        val tracker = SocketActivityTracker<String>()
        tracker.touch("a", now = 200L)
        tracker.touch("b", now = 100L)

        val victims = tracker.selectLeastRecentlyActive(listOf("a", "b"), count = 10)

        assertEquals(listOf("b", "a"), victims)
    }

    @Test
    fun zero_or_negative_count_selects_nothing() {
        val tracker = SocketActivityTracker<String>()
        tracker.touch("a", now = 1L)

        assertTrue(tracker.selectLeastRecentlyActive(listOf("a"), count = 0).isEmpty())
        assertTrue(tracker.selectLeastRecentlyActive(listOf("a"), count = -1).isEmpty())
    }

    @Test
    fun empty_candidate_list_selects_nothing_even_with_a_positive_count() {
        val tracker = SocketActivityTracker<String>()
        assertTrue(tracker.selectLeastRecentlyActive(emptyList(), count = 5).isEmpty())
    }

    @Test
    fun clear_forgets_all_tracked_activity() {
        val tracker = SocketActivityTracker<String>()
        tracker.touch("a", now = 1_000L)
        tracker.touch("b", now = 2_000L)
        tracker.clear()

        // Both now default to activity=0 -- selection just falls back to list order for ties.
        val victims = tracker.selectLeastRecentlyActive(listOf("a", "b"), count = 2)
        assertEquals(listOf("a", "b"), victims)
    }
}
