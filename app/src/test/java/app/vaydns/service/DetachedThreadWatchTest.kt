package app.vaydns.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers DetachedThreadWatch, the escalation added after a live incident where a recovery's native
 * thread wedged in a synchronous picoquic reassembly loop (100% of a core, 0 syscalls) and could
 * never be reaped -- each recovery that hit it leaked another burned core, and the EADDRINUSE
 * fresh-port escape only papered over the port conflict. The watch decides when the spiral is real
 * enough to warrant the one thing that actually reclaims a wedged native thread: a process restart.
 *
 * Uses the real production thresholds (3 incidents / 120_000ms window).
 */
class DetachedThreadWatchTest {
    private val threshold = 3
    private val windowMs = 120_000L

    private fun watch() = DetachedThreadWatch(threshold, windowMs)

    @Test
    fun does_not_fire_below_the_threshold() {
        val w = watch()
        assertFalse(w.onIncident(0))
        assertFalse(w.onIncident(1_000))
        assertEquals(2, w.countInWindow())
    }

    @Test
    fun fires_exactly_on_the_threshold_incident_within_the_window() {
        val w = watch()
        assertFalse(w.onIncident(0))
        assertFalse(w.onIncident(10_000))
        assertTrue(w.onIncident(20_000)) // 3rd within 120s -> fire
    }

    @Test
    fun old_incidents_outside_the_window_do_not_count_toward_the_threshold() {
        val w = watch()
        assertFalse(w.onIncident(0))
        assertFalse(w.onIncident(10_000))
        // 3rd incident lands >120s after the first, so the first has aged out -- only 2 in window.
        assertFalse(w.onIncident(130_000))
        assertEquals(2, w.countInWindow())
    }

    @Test
    fun a_slow_drip_of_isolated_incidents_never_fires() {
        val w = watch()
        var fired = false
        // One incident every ~90s: never 3 within any 120s window.
        for (i in 0 until 20) {
            fired = fired || w.onIncident(i * 90_000L)
        }
        assertFalse("isolated incidents spread out must not escalate to a restart", fired)
        assertTrue(w.countInWindow() <= 2)
    }

    @Test
    fun a_tight_burst_at_the_window_edge_still_fires() {
        val w = watch()
        assertFalse(w.onIncident(1_000))
        assertFalse(w.onIncident(60_000))
        // 120_000 - 1_000 = 119_000 <= window from the first incident, so all three are in-window.
        assertTrue(w.onIncident(120_000))
    }

    @Test
    fun reset_clears_the_incident_history() {
        val w = watch()
        assertFalse(w.onIncident(0))
        assertFalse(w.onIncident(10_000))
        w.reset()
        assertEquals(0, w.countInWindow())
        // After reset it takes a fresh full threshold to fire again.
        assertFalse(w.onIncident(20_000))
        assertFalse(w.onIncident(21_000))
        assertTrue(w.onIncident(22_000))
    }

    @Test
    fun keeps_firing_on_a_sustained_storm_but_only_once_per_fresh_threshold() {
        // Models a real spiral: incidents keep coming; the window stays saturated so every
        // subsequent incident (>= threshold in window) reports fire. The caller de-dupes the actual
        // restart via its own one-shot guard -- the watch just keeps telling the truth.
        val w = watch()
        assertFalse(w.onIncident(0))
        assertFalse(w.onIncident(1_000))
        assertTrue(w.onIncident(2_000))
        assertTrue(w.onIncident(3_000))
        assertTrue(w.onIncident(4_000))
    }
}
