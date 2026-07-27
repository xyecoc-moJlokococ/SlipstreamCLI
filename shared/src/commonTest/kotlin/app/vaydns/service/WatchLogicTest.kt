package app.vaydns.service

import app.vaydns.Config
import app.vaydns.HostPlatform
import app.vaydns.currentHostPlatform
import app.slipnet.tunnel.ConnReaper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mirrors the Android unit tests for the same pure types (now hosted in :shared)
 * so desktop/iOS CI exercises identical recovery logic without Robolectric.
 */
class BridgeFailureWatchTest {
    private val watch = BridgeFailureWatch(recoveryTotal = 3, recoveryWindowMs = 1000)
    private val noCooldown = 0L

    @Test
    fun does_not_fire_on_first_failure_tick() {
        val fired = watch.tick(
            now = 1000, running = true, ready = true, recovering = false,
            failureTotal = 30, lastRecoveryAt = noCooldown, recoveryCooldownMs = 100
        )
        assertNull(fired)
    }

    @Test
    fun fires_once_count_and_window_thresholds_crossed() {
        watch.tick(1000, true, true, false, failureTotal = 1, lastRecoveryAt = noCooldown, recoveryCooldownMs = 100)
        assertNull(
            watch.tick(1500, true, true, false, failureTotal = 3, lastRecoveryAt = noCooldown, recoveryCooldownMs = 100)
        )
        val fired = watch.tick(2200, true, true, false, failureTotal = 3, lastRecoveryAt = noCooldown, recoveryCooldownMs = 100)
        assertNotNull(fired)
        assertEquals(3L, fired.accumulated)
        assertEquals(1200L, fired.windowMs)
    }

    @Test
    fun resets_when_not_ready() {
        watch.tick(1000, true, true, false, failureTotal = 1, lastRecoveryAt = noCooldown, recoveryCooldownMs = 100)
        assertNull(
            watch.tick(1500, true, false, false, failureTotal = 10, lastRecoveryAt = noCooldown, recoveryCooldownMs = 100)
        )
    }
}

class StallRatioWatchTest {
    private val minRequestBytes = 8_192L
    private val responseDivisor = 8L
    private val windowMs = 25_000L
    private val cooldownMs = 5_000L
    private val noCooldown = -1_000_000L

    private fun watch() = StallRatioWatch(minRequestBytes, responseDivisor, windowMs)

    private fun starved(w: StallRatioWatch, now: Long) = w.tick(
        now, running = true, ready = true, recovering = false, uploadGraceActive = false,
        requestBytesDelta = 40_000, responseBytesDelta = 3_000,
        lastRecoveryAt = noCooldown, recoveryCooldownMs = cooldownMs
    )

    @Test
    fun fires_once_starvation_persists_past_the_window() {
        val w = watch()
        assertNull(starved(w, 1_000))
        assertNull(starved(w, 20_000))
        val fired = starved(w, 27_000)
        assertNotNull(fired)
        assertEquals(26_000L, fired.windowMs)
    }

    @Test
    fun healthy_tick_rearms() {
        val w = watch()
        assertNull(starved(w, 1_000))
        assertNull(
            w.tick(
                5_000, true, true, false, false,
                requestBytesDelta = 40_000, responseBytesDelta = 40_000,
                lastRecoveryAt = noCooldown, recoveryCooldownMs = cooldownMs
            )
        )
        assertNull(starved(w, 10_000))
    }
}

class DetachedThreadWatchTest {
    @Test
    fun fires_after_threshold_in_window() {
        val w = DetachedThreadWatch(incidentThreshold = 3, windowMs = 10_000)
        assertFalse(w.onIncident(0))
        assertFalse(w.onIncident(1000))
        assertTrue(w.onIncident(2000))
        assertEquals(3, w.countInWindow())
    }

    @Test
    fun old_incidents_expire() {
        val w = DetachedThreadWatch(incidentThreshold = 2, windowMs = 1000)
        assertFalse(w.onIncident(0))
        assertFalse(w.onIncident(2000))
        assertTrue(w.onIncident(2100))
    }
}

class RecoveryReasonClassTest {
    @Test
    fun native_no_progress_reuses_resolver() {
        val c = classifyRecoveryReason(
            reason = "native_not_running",
            lastNativeError = "native no-progress for 30s",
            resolverMode = Config.ResolverMode.AUTO,
            hasCurrentResolver = true,
            currentResolverAlreadyFailed = false
        )
        assertTrue(c.isNativeNoProgress)
        assertTrue(c.reuseCurrentResolver)
        assertFalse(c.rotateResolver)
    }

    @Test
    fun traffic_no_response_retries_then_rotates_logic() {
        val retry = classifyRecoveryReason(
            "traffic_no_response",
            "",
            Config.ResolverMode.AUTO,
            hasCurrentResolver = true,
            currentResolverAlreadyFailed = false
        )
        assertTrue(retry.retryCurrentFirst)
        assertTrue(retry.reuseCurrentResolver)

        val rotate = classifyRecoveryReason(
            "traffic_no_response",
            "",
            Config.ResolverMode.AUTO,
            hasCurrentResolver = true,
            currentResolverAlreadyFailed = true
        )
        assertFalse(rotate.retryCurrentFirst)
        assertTrue(rotate.rotateResolver)
    }

    @Test
    fun transport_switch_does_not_rotate() {
        val c = classifyRecoveryReason(
            "transport_switch_udp_to_tcp",
            "",
            Config.ResolverMode.AUTO,
            hasCurrentResolver = true,
            currentResolverAlreadyFailed = false
        )
        assertTrue(c.transportSwitchRecovery)
        assertFalse(c.rotateResolver)
    }
}

class ResolverHealthGateTest {
    @Test
    fun rotate_only_when_threshold_and_cooldown_met() {
        assertFalse(
            shouldRotateOnResolverHealthFailure(2, 3, true, false, 1000, 0, 500)
        )
        assertTrue(
            shouldRotateOnResolverHealthFailure(3, 3, true, false, 1000, 0, 500)
        )
        assertFalse(
            shouldRotateOnResolverHealthFailure(5, 3, true, true, 1000, 0, 500)
        )
        assertFalse(
            shouldRotateOnResolverHealthFailure(5, 3, true, false, 1000, 800, 500)
        )
    }
}

class ConnReaperSharedTest {
    @Test
    fun full_idle() {
        assertTrue(
            ConnReaper.shouldReap(
                nowMs = 100_000,
                lastActivityMs = 0,
                halfClosedAtMs = 0,
                bufferStuckSinceMs = 0,
                fullIdleMs = 90_000,
                halfIdleMs = 30_000,
                halfMaxMs = 60_000,
                stuckMs = 20_000
            )
        )
    }

    @Test
    fun half_closed_shorter_idle() {
        assertTrue(
            ConnReaper.shouldReap(
                nowMs = 40_000,
                lastActivityMs = 20_000,
                halfClosedAtMs = 10_000,
                bufferStuckSinceMs = 0,
                fullIdleMs = 90_000,
                halfIdleMs = 15_000,
                halfMaxMs = 60_000,
                stuckMs = 20_000
            )
        )
    }
}

class PlatformInfoTest {
    @Test
    fun host_platform_is_defined() {
        val p = currentHostPlatform()
        assertTrue(p != HostPlatform.UNKNOWN)
    }
}
