package app.vaydns.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BridgeFailureWatchTest already covers the arm/fire/reset shape for one or two episodes. This
 * covers the thing that prompted it: a real report of ~40 recovery cycles in a single day on a
 * flaky mobile connection (LTE/LTE+ tower handoffs the app can't see coming -- see
 * ResolverSelectorTest / networkSignature's blind spot to same-operator handoffs). The question
 * isn't "can the watch fire once" -- it's "does the arm/fire/reset state machine stay accurate
 * over DOZENS of repeated episodes in one long-running session, or does it drift, get stuck, or
 * double-fire once it's been through the cycle many times."
 *
 * Uses the real production thresholds (ACCUMULATED_FAILURE_RECOVERY_TOTAL=24,
 * ACCUMULATED_FAILURE_RECOVERY_WINDOW_MS=20_000, RECOVERY_COOLDOWN_MS=5_000 -- mirrored here as
 * literals since TinyVpnService's companion constants are private) rather than the smaller
 * stand-ins BridgeFailureWatchTest uses, so the episode counts below are directly comparable to a
 * real day's recovery count.
 */
class BridgeFailureWatchLongSessionTest {
    private val recoveryTotal = 24L
    private val recoveryWindowMs = 20_000L
    private val recoveryCooldownMs = 5_000L

    /**
     * Simulates one "tower handoff" episode: a healthy baseline tick, then a burst of
     * [burstSize] failures that arrives fast enough to still be within one window, then the tick
     * that lets the window fully elapse. Returns the Fired result from that final tick (null if
     * it didn't cross threshold) along with the failureTotal/time cursor for the next episode.
     */
    private fun runEpisode(
        watch: BridgeFailureWatch,
        startAt: Long,
        priorFailureTotal: Long,
        burstSize: Long,
        lastRecoveryAt: Long
    ): Triple<BridgeFailureWatch.Fired?, Long, Long> {
        // Healthy baseline right before the handoff -- re-bases the watch at the current total,
        // exactly like a quiet tunnel would between episodes.
        watch.tick(startAt, true, true, false, priorFailureTotal, lastRecoveryAt, recoveryCooldownMs)
        // The handoff causes a fast burst of bridge failures -- arms on the first one.
        val armedAt = startAt + 1_000
        watch.tick(armedAt, true, true, false, priorFailureTotal + 1, lastRecoveryAt, recoveryCooldownMs)
        // Window elapses with the burst fully accumulated.
        val checkAt = armedAt + recoveryWindowMs
        val newTotal = priorFailureTotal + burstSize
        val fired = watch.tick(checkAt, true, true, false, newTotal, lastRecoveryAt, recoveryCooldownMs)
        return Triple(fired, newTotal, checkAt)
    }

    @Test
    fun fires_exactly_once_per_isolated_episode_across_a_simulated_day_of_repeated_handoffs() {
        val watch = BridgeFailureWatch(recoveryTotal, recoveryWindowMs)
        val episodeCount = 45 // a bit above the reported "~40 recoveries in a day"
        val gapMs = 30 * 60_000L // ~30 minutes apart -- 45 of these already spans most of a day

        var failureTotal = 0L
        var lastRecoveryAt = -recoveryCooldownMs * 10 // far enough in the past to never gate episode 1
        var fireCount = 0
        val accumulatedSeen = mutableListOf<Long>()

        for (i in 0 until episodeCount) {
            val startAt = i * gapMs
            val (fired, newTotal, firedAt) = runEpisode(watch, startAt, failureTotal, burstSize = 26L, lastRecoveryAt)
            requireNotNull(fired) { "episode $i (starting at t=${startAt}ms) failed to fire" }
            fireCount++
            accumulatedSeen += fired.accumulated
            failureTotal = newTotal
            lastRecoveryAt = firedAt
        }

        assertEquals(
            "expected exactly one fire per episode with no drift over $episodeCount repeats",
            episodeCount,
            fireCount
        )
        // Every episode injected the same 26-failure burst -- the reported "accumulated" must stay
        // exactly 26 every single time, not creep up or down as episodes pile up.
        assertTrue(accumulatedSeen.all { it == 26L })
    }

    @Test
    fun keeps_firing_correctly_back_to_back_right_at_the_cooldown_boundary() {
        // The worst case for a "flaky all day" report: episodes packed as tightly as the
        // cooldown allows, not spread out. If arm/fire/reset state ever leaked or drifted, this
        // is where it would show up first.
        val watch = BridgeFailureWatch(recoveryTotal, recoveryWindowMs)
        val episodeCount = 40

        var failureTotal = 0L
        var lastRecoveryAt = -recoveryCooldownMs * 10
        // Start each next episode just AFTER the previous fire's cooldown expires.
        var cursor = 0L
        var fireCount = 0

        for (i in 0 until episodeCount) {
            val startAt = cursor + recoveryCooldownMs + 1
            val (fired, newTotal, firedAt) = runEpisode(watch, startAt, failureTotal, burstSize = 24L, lastRecoveryAt)
            requireNotNull(fired) { "episode $i (starting at t=${startAt}ms) failed to fire back-to-back" }
            fireCount++
            failureTotal = newTotal
            lastRecoveryAt = firedAt
            cursor = firedAt
        }

        assertEquals(episodeCount, fireCount)
    }

    @Test
    fun idle_ticks_between_episodes_never_cause_a_spurious_extra_fire() {
        // Sprinkle the normal ~5s diagnostic cadence of "nothing new happened" ticks between two
        // real episodes -- none of those should ever return Fired.
        val watch = BridgeFailureWatch(recoveryTotal, recoveryWindowMs)
        val (firstFired, totalAfterFirst, firedAt) = runEpisode(watch, startAt = 0L, priorFailureTotal = 0L, burstSize = 26L, lastRecoveryAt = -999_999L)
        requireNotNull(firstFired)

        var idleTicks = 0
        var t = firedAt
        val quietUntil = firedAt + 20 * 60_000L // 20 quiet minutes before the next real episode
        while (t < quietUntil) {
            t += 5_000L
            val result = watch.tick(t, true, true, false, totalAfterFirst, firedAt, recoveryCooldownMs)
            assertNull("idle tick at t=$t spuriously fired", result)
            idleTicks++
        }
        assertTrue("expected a meaningful number of idle ticks to actually exercise this", idleTicks > 200)

        val (secondFired, _, _) = runEpisode(watch, startAt = quietUntil, priorFailureTotal = totalAfterFirst, burstSize = 26L, lastRecoveryAt = firedAt)
        requireNotNull(secondFired) { "the next real episode after a long quiet stretch must still fire normally" }
    }
}
