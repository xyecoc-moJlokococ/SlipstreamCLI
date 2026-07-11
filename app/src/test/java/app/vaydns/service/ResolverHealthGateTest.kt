package app.vaydns.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolverHealthGateTest {
    @Test
    fun rotates_once_failures_reach_the_threshold_outside_cooldown_while_active_and_not_recovering() {
        assertTrue(
            shouldRotateOnResolverHealthFailure(
                failures = 2, failuresBeforeRotate = 2, tunnelActive = true, recovering = false,
                now = 10_000, lastRecoveryAt = 0, recoveryCooldownMs = 5_000
            )
        )
    }

    @Test
    fun does_not_rotate_below_the_failure_threshold() {
        assertFalse(
            shouldRotateOnResolverHealthFailure(
                failures = 1, failuresBeforeRotate = 2, tunnelActive = true, recovering = false,
                now = 10_000, lastRecoveryAt = 0, recoveryCooldownMs = 5_000
            )
        )
    }

    @Test
    fun does_not_rotate_when_the_tunnel_is_not_active() {
        assertFalse(
            shouldRotateOnResolverHealthFailure(
                failures = 5, failuresBeforeRotate = 2, tunnelActive = false, recovering = false,
                now = 10_000, lastRecoveryAt = 0, recoveryCooldownMs = 5_000
            )
        )
    }

    @Test
    fun does_not_rotate_while_a_recovery_is_already_in_progress() {
        assertFalse(
            shouldRotateOnResolverHealthFailure(
                failures = 5, failuresBeforeRotate = 2, tunnelActive = true, recovering = true,
                now = 10_000, lastRecoveryAt = 0, recoveryCooldownMs = 5_000
            )
        )
    }

    @Test
    fun does_not_rotate_inside_the_post_recovery_cooldown() {
        assertFalse(
            shouldRotateOnResolverHealthFailure(
                failures = 5, failuresBeforeRotate = 2, tunnelActive = true, recovering = false,
                now = 10_000, lastRecoveryAt = 6_000, recoveryCooldownMs = 5_000
            )
        )
    }

    @Test
    fun rotates_right_after_the_cooldown_elapses() {
        assertTrue(
            shouldRotateOnResolverHealthFailure(
                failures = 5, failuresBeforeRotate = 2, tunnelActive = true, recovering = false,
                now = 10_001, lastRecoveryAt = 5_000, recoveryCooldownMs = 5_000
            )
        )
    }
}
