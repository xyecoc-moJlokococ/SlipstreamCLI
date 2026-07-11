package app.vaydns.service

import app.vaydns.Config
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers restartSlipstreamPath's reason-classification block, including the overlaps between
 * flags that the inline version left implicit (e.g. a "bridge_failures_accumulated_*" reason
 * satisfies both fastPathRecovery and bridgeFailureRecovery at once) -- this had never been
 * exercised by anything but production traffic before.
 */
class RecoveryReasonClassTest {
    private fun classify(
        reason: String,
        lastNativeError: String = "",
        resolverMode: Config.ResolverMode = Config.ResolverMode.AUTO,
        hasCurrentResolver: Boolean = true,
        currentResolverAlreadyFailed: Boolean = false
    ) = classifyRecoveryReason(reason, lastNativeError, resolverMode, hasCurrentResolver, currentResolverAlreadyFailed)

    // -- bridge_failures_accumulated_*: the overlap the prompt called out explicitly --

    @Test
    fun bridge_failures_accumulated_is_both_a_fast_path_and_a_bridge_failure_reason() {
        val cls = classify("bridge_failures_accumulated_28")
        assertTrue(cls.fastPathRecovery)
        assertTrue(cls.bridgeFailureRecovery)
        assertTrue(cls.bridgeAccumulatedRecovery)
        assertTrue(cls.bridgeFailureFastRetry)
        assertFalse(cls.failureStormRecovery)
        // Quick same-resolver retry, no rotation, regardless of current-resolver state.
        assertTrue(cls.reuseCurrentResolver)
        assertFalse(cls.rotateResolver)
    }

    @Test
    fun bridge_failures_accumulated_reuses_even_without_a_current_resolver_to_retry() {
        // reuseCurrentResolver can be true with hasCurrentResolver=false -- the final null-check
        // gating the actual reuse happens at the call site, not in the classifier.
        val cls = classify("bridge_failures_accumulated_28", hasCurrentResolver = false)
        assertTrue(cls.reuseCurrentResolver)
        assertFalse(cls.rotateResolver)
    }

    // -- bridge_failures_..._no_response: silence variant, retries only if there's a live, un-failed resolver --

    @Test
    fun bridge_failures_no_response_retries_current_resolver_when_one_is_available_and_healthy() {
        val cls = classify("bridge_failures_no_response", hasCurrentResolver = true, currentResolverAlreadyFailed = false)
        assertTrue(cls.silenceRecovery)
        assertTrue(cls.retryCurrentFirst)
        assertTrue(cls.reuseCurrentResolver)
        assertFalse(cls.rotateResolver)
    }

    @Test
    fun bridge_failures_no_response_rotates_when_there_is_no_current_resolver() {
        val cls = classify("bridge_failures_no_response", hasCurrentResolver = false)
        assertFalse(cls.retryCurrentFirst)
        assertFalse(cls.reuseCurrentResolver)
        assertTrue(cls.rotateResolver)
    }

    @Test
    fun bridge_failures_no_response_rotates_when_the_current_resolver_already_failed() {
        val cls = classify("bridge_failures_no_response", hasCurrentResolver = true, currentResolverAlreadyFailed = true)
        assertFalse(cls.retryCurrentFirst)
        assertFalse(cls.reuseCurrentResolver)
        assertTrue(cls.rotateResolver)
    }

    // -- bridge_failure_storm: distinct prefix from bridge_failures (singular "failure"), NOT a fastPathRecovery --

    @Test
    fun bridge_failure_storm_is_not_a_fast_path_reason_despite_the_similar_name() {
        val cls = classify("bridge_failure_storm_15")
        assertFalse(cls.fastPathRecovery)
        assertTrue(cls.failureStormRecovery)
        assertTrue(cls.bridgeFailureRecovery)
        assertTrue(cls.bridgeFailureFastRetry)
        assertTrue(cls.reuseCurrentResolver)
        assertFalse(cls.rotateResolver)
    }

    @Test
    fun bridge_failure_storm_excludes_the_auto_fast_recovery_path() {
        val cls = classify("bridge_failure_storm_15", hasCurrentResolver = true, resolverMode = Config.ResolverMode.AUTO)
        assertFalse(cls.autoFastRecovery)
    }

    // -- resolver_unreachable: a failed reachability probe already proves the resolver is gone --

    @Test
    fun resolver_unreachable_always_rotates_never_reuses() {
        val healthy = classify("resolver_unreachable_1.2.3.4_2", hasCurrentResolver = true, currentResolverAlreadyFailed = false)
        assertTrue(healthy.trafficRecovery)
        assertFalse(healthy.reuseCurrentResolver)
        assertTrue(healthy.rotateResolver)

        val noResolver = classify("resolver_unreachable_1.2.3.4_2", hasCurrentResolver = false)
        assertFalse(noResolver.reuseCurrentResolver)
        assertTrue(noResolver.rotateResolver)
    }

    // -- traffic_no_response: silence-type, retries current resolver first like bridge_failures_no_response --

    @Test
    fun traffic_no_response_retries_current_resolver_when_healthy() {
        val cls = classify("traffic_no_response_5000ms", hasCurrentResolver = true, currentResolverAlreadyFailed = false)
        assertTrue(cls.silenceRecovery)
        assertTrue(cls.trafficRecovery)
        assertTrue(cls.retryCurrentFirst)
        assertTrue(cls.reuseCurrentResolver)
        assertFalse(cls.rotateResolver)
    }

    @Test
    fun traffic_no_response_rotates_when_no_healthy_current_resolver() {
        val cls = classify("traffic_no_response_5000ms", hasCurrentResolver = false)
        assertFalse(cls.reuseCurrentResolver)
        assertTrue(cls.rotateResolver)
    }

    // -- traffic_slow_response / traffic_low_bandwidth / resolver_speed_upgrade: unconditional reuse --

    @Test
    fun traffic_slow_response_always_reuses_never_rotates() {
        val cls = classify("traffic_slow_response_120ms", hasCurrentResolver = false)
        assertTrue(cls.fastPathRecovery)
        assertFalse(cls.trafficRecovery)
        assertTrue(cls.reuseCurrentResolver)
        assertFalse(cls.rotateResolver)
    }

    @Test
    fun traffic_low_bandwidth_always_reuses_never_rotates() {
        val cls = classify("traffic_low_bandwidth_800bps")
        assertTrue(cls.fastPathRecovery)
        assertTrue(cls.reuseCurrentResolver)
        assertFalse(cls.rotateResolver)
    }

    @Test
    fun resolver_speed_upgrade_always_reuses_never_rotates() {
        val cls = classify("resolver_speed_upgrade_found_faster")
        assertTrue(cls.fastPathRecovery)
        assertTrue(cls.reuseCurrentResolver)
        assertFalse(cls.rotateResolver)
    }

    // -- transport_switch / network_changed: neither reuse nor rotate (handled by their own branch) --

    @Test
    fun transport_switch_is_flagged_but_neither_reuses_nor_rotates() {
        val cls = classify("transport_switch_udp_to_tcp")
        assertTrue(cls.transportSwitchRecovery)
        assertFalse(cls.reuseCurrentResolver)
        assertFalse(cls.rotateResolver)
    }

    @Test
    fun network_changed_is_flagged_but_neither_reuses_nor_rotates() {
        val cls = classify("network_changed_wifi_to_cell")
        assertTrue(cls.networkChangedRecovery)
        assertFalse(cls.reuseCurrentResolver)
        assertFalse(cls.rotateResolver)
    }

    // -- native_not_running: rotates unless it's a no-progress error (which reuses) --

    @Test
    fun native_not_running_rotates_when_not_a_no_progress_error() {
        val cls = classify("native_not_running", lastNativeError = "some other native error")
        assertFalse(cls.isNativeNoProgress)
        assertFalse(cls.reuseCurrentResolver)
        assertTrue(cls.rotateResolver)
    }

    @Test
    fun native_not_running_reuses_when_the_native_error_is_no_progress() {
        val cls = classify("native_not_running", lastNativeError = "native no-progress: no ack in 5s")
        assertTrue(cls.isNativeNoProgress)
        assertTrue(cls.reuseCurrentResolver)
        assertFalse(cls.rotateResolver)
    }

    @Test
    fun native_not_running_auto_fast_recovery_requires_auto_mode_and_a_current_resolver() {
        val autoWithResolver = classify(
            "native_not_running", lastNativeError = "native no-progress: no ack in 5s",
            resolverMode = Config.ResolverMode.AUTO, hasCurrentResolver = true
        )
        assertTrue(autoWithResolver.nativeDownFastRecovery)
        assertTrue(autoWithResolver.autoFastRecovery)

        val manualMode = classify(
            "native_not_running", lastNativeError = "native no-progress: no ack in 5s",
            resolverMode = Config.ResolverMode.MANUAL, hasCurrentResolver = true
        )
        assertFalse(manualMode.nativeDownFastRecovery)
        assertFalse(manualMode.autoFastRecovery)
        // Rotation doesn't depend on resolver mode, only reuse/auto-fast-recovery does.
        assertTrue(manualMode.reuseCurrentResolver) // still reuses: isNativeNoProgress alone drives this

        val noResolver = classify(
            "native_not_running", lastNativeError = "native no-progress: no ack in 5s",
            resolverMode = Config.ResolverMode.AUTO, hasCurrentResolver = false
        )
        assertFalse(noResolver.nativeDownFastRecovery)
        assertFalse(noResolver.autoFastRecovery)
    }

    // -- native_not_ready: always rotates, never reuses, but can auto-fast-recover --

    @Test
    fun native_not_ready_rotates_and_can_auto_fast_recover() {
        val cls = classify("native_not_ready_3000ms", resolverMode = Config.ResolverMode.AUTO, hasCurrentResolver = true)
        assertTrue(cls.nativeNotReadyRecovery)
        assertFalse(cls.reuseCurrentResolver)
        assertTrue(cls.rotateResolver)
        assertTrue(cls.autoFastRecovery)
    }

    // -- an unrecognized reason classifies to all-false --

    @Test
    fun an_unrecognized_reason_reuses_and_rotates_neither() {
        val cls = classify("some_other_reason")
        assertFalse(cls.fastPathRecovery)
        assertFalse(cls.failureStormRecovery)
        assertFalse(cls.resolverUnreachableRecovery)
        assertFalse(cls.transportSwitchRecovery)
        assertFalse(cls.networkChangedRecovery)
        assertFalse(cls.bridgeFailureRecovery)
        assertFalse(cls.nativeNotReadyRecovery)
        assertFalse(cls.isNativeNoProgress)
        assertFalse(cls.reuseCurrentResolver)
        assertFalse(cls.rotateResolver)
    }
}
