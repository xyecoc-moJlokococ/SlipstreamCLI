package app.vaydns.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Small thresholds (recoveryTotal=3, recoveryWindowMs=1000) stand in for the real
 * ACCUMULATED_FAILURE_RECOVERY_TOTAL=24 / _WINDOW_MS=20000 so test times stay readable; the
 * arm/reset/fire shape being tested is identical regardless of the threshold values.
 */
class BridgeFailureWatchTest {
    private val watch = BridgeFailureWatch(recoveryTotal = 3, recoveryWindowMs = 1000)
    private val noCooldown = 0L // lastRecoveryAt far enough in the past that cooldown never gates

    @Test
    fun does_not_fire_on_the_first_tick_that_sees_a_failure_even_if_the_jump_is_already_large() {
        // Arming consumes one tick: it records the pre-jump baseline but can't yet know whether
        // the count will keep climbing, so it must not fire until a *later* tick confirms it.
        val fired = watch.tick(
            now = 1000, running = true, ready = true, recovering = false,
            failureTotal = 30, lastRecoveryAt = noCooldown, recoveryCooldownMs = 100
        )
        assertNull(fired)
    }

    @Test
    fun fires_once_the_count_and_window_thresholds_are_both_crossed() {
        watch.tick(1000, true, true, false, failureTotal = 1, lastRecoveryAt = noCooldown, recoveryCooldownMs = 100)
        // count crosses the threshold but the window hasn't elapsed yet
        val tooSoon = watch.tick(1500, true, true, false, failureTotal = 3, lastRecoveryAt = noCooldown, recoveryCooldownMs = 100)
        assertNull(tooSoon)
        // window elapses with the count still >= threshold
        val fired = watch.tick(2200, true, true, false, failureTotal = 3, lastRecoveryAt = noCooldown, recoveryCooldownMs = 100)
        requireNotNull(fired)
        assertEquals(3L, fired.accumulated)
        assertEquals(1200L, fired.windowMs)
    }

    @Test
    fun does_not_fire_below_the_count_threshold_no_matter_how_long_the_window() {
        watch.tick(1000, true, true, false, failureTotal = 1, lastRecoveryAt = noCooldown, recoveryCooldownMs = 100)
        val fired = watch.tick(60_000, true, true, false, failureTotal = 2, lastRecoveryAt = noCooldown, recoveryCooldownMs = 100)
        assertNull(fired)
    }

    @Test
    fun a_recovery_cooldown_blocks_the_fire_but_leaves_the_watch_armed_for_a_later_tick() {
        watch.tick(1000, true, true, false, failureTotal = 1, lastRecoveryAt = noCooldown, recoveryCooldownMs = 100)
        // thresholds met, but a recovery just happened (lastRecoveryAt close to now) -- gated
        val blocked = watch.tick(2200, true, true, false, failureTotal = 3, lastRecoveryAt = 2150, recoveryCooldownMs = 100)
        assertNull(blocked)
        // same accumulated/window state, cooldown has now passed -- fires without needing a fresh arm
        val fired = watch.tick(2300, true, true, false, failureTotal = 3, lastRecoveryAt = 2150, recoveryCooldownMs = 100)
        requireNotNull(fired)
        assertEquals(3L, fired.accumulated)
    }

    @Test
    fun resets_when_not_running() {
        watch.tick(1000, true, true, false, failureTotal = 1, lastRecoveryAt = noCooldown, recoveryCooldownMs = 100)
        watch.tick(1500, running = false, ready = true, recovering = false, failureTotal = 1, lastRecoveryAt = noCooldown, recoveryCooldownMs = 100)
        // re-arming from scratch: the same failureTotal must not instantly count as an increase
        val fired = watch.tick(3000, true, true, false, failureTotal = 1, lastRecoveryAt = noCooldown, recoveryCooldownMs = 100)
        assertNull(fired)
    }

    @Test
    fun resets_when_not_ready() {
        watch.tick(1000, true, true, false, failureTotal = 1, lastRecoveryAt = noCooldown, recoveryCooldownMs = 100)
        watch.tick(1500, running = true, ready = false, recovering = false, failureTotal = 1, lastRecoveryAt = noCooldown, recoveryCooldownMs = 100)
        val fired = watch.tick(3000, true, true, false, failureTotal = 1, lastRecoveryAt = noCooldown, recoveryCooldownMs = 100)
        assertNull(fired)
    }

    @Test
    fun resets_while_a_recovery_is_already_in_progress() {
        watch.tick(1000, true, true, false, failureTotal = 1, lastRecoveryAt = noCooldown, recoveryCooldownMs = 100)
        watch.tick(1500, running = true, ready = true, recovering = true, failureTotal = 1, lastRecoveryAt = noCooldown, recoveryCooldownMs = 100)
        val fired = watch.tick(3000, true, true, false, failureTotal = 1, lastRecoveryAt = noCooldown, recoveryCooldownMs = 100)
        assertNull(fired)
    }

    @Test
    fun resets_when_failure_total_drops_back_to_or_below_the_armed_base() {
        // e.g. a counter that got reset elsewhere (fresh episode), not the one being watched.
        watch.tick(1000, true, true, false, failureTotal = 5, lastRecoveryAt = noCooldown, recoveryCooldownMs = 100)
        val fired = watch.tick(1500, true, true, false, failureTotal = 4, lastRecoveryAt = noCooldown, recoveryCooldownMs = 100)
        assertNull(fired)
    }

    @Test
    fun a_fresh_episode_after_firing_requires_its_own_full_arm_and_window() {
        watch.tick(1000, true, true, false, failureTotal = 1, lastRecoveryAt = noCooldown, recoveryCooldownMs = 100)
        val firstFire = watch.tick(2200, true, true, false, failureTotal = 3, lastRecoveryAt = noCooldown, recoveryCooldownMs = 100)
        requireNotNull(firstFire)

        // Right after firing, watchBase == 3: the very next failure (4) only arms, it can't fire
        // immediately even though it's "past" the old threshold in absolute terms.
        val armOnly = watch.tick(2300, true, true, false, failureTotal = 4, lastRecoveryAt = noCooldown, recoveryCooldownMs = 100)
        assertNull(armOnly)
        val tooSoonAgain = watch.tick(2900, true, true, false, failureTotal = 6, lastRecoveryAt = noCooldown, recoveryCooldownMs = 100)
        assertNull(tooSoonAgain)
        val secondFire = watch.tick(3400, true, true, false, failureTotal = 6, lastRecoveryAt = noCooldown, recoveryCooldownMs = 100)
        requireNotNull(secondFire)
        assertEquals(3L, secondFire.accumulated)
    }

    @Test
    fun does_not_underflow_when_the_very_first_failure_total_seen_is_zero() {
        val watch2 = BridgeFailureWatch(recoveryTotal = 3, recoveryWindowMs = 1000)
        val fired = watch2.tick(1000, true, true, false, failureTotal = 0, lastRecoveryAt = noCooldown, recoveryCooldownMs = 100)
        assertNull(fired)
    }
}
