package app.vaydns.service

import app.vaydns.Config

/**
 * Pure classification of a recovery [reason] string into the flags restartSlipstreamPath uses to
 * decide whether to reuse the current resolver, rotate to a new one, or take one of the fast
 * paths (native no-progress, bridge-failure quick retry, transport re-validation, network
 * change). Extracted out of restartSlipstreamPath's inline `val ... = reason.startsWith(...)`
 * block so the whole decision -- including the overlap between individual flags -- is one tested
 * unit instead of only being exercised by production traffic.
 *
 * [hasCurrentResolver] and [currentResolverAlreadyFailed] carry in the only two bits of resolver
 * state the classification needs (currentResolver != null, and whether its host is already in
 * failedAutoResolvers); everything else is a pure function of the reason string and config.
 */
data class RecoveryReasonClass(
    val isNativeNoProgress: Boolean,
    val fastPathRecovery: Boolean,
    val failureStormRecovery: Boolean,
    val resolverUnreachableRecovery: Boolean,
    val transportSwitchRecovery: Boolean,
    val networkChangedRecovery: Boolean,
    val trafficRecovery: Boolean,
    val bridgeFailureRecovery: Boolean,
    val bridgeAccumulatedRecovery: Boolean,
    val bridgeFailureFastRetry: Boolean,
    val silenceRecovery: Boolean,
    val retryCurrentFirst: Boolean,
    val nativeNotReadyRecovery: Boolean,
    val nativeDownFastRecovery: Boolean,
    val autoFastRecovery: Boolean,
    val reuseCurrentResolver: Boolean,
    val rotateResolver: Boolean
)

fun classifyRecoveryReason(
    reason: String,
    lastNativeError: String,
    resolverMode: Config.ResolverMode,
    hasCurrentResolver: Boolean,
    currentResolverAlreadyFailed: Boolean
): RecoveryReasonClass {
    val isNativeNoProgress = reason == "native_not_running" &&
        lastNativeError.startsWith("native no-progress")
    val fastPathRecovery = reason.startsWith("traffic_no_response") ||
        reason.startsWith("traffic_slow_response") ||
        reason.startsWith("traffic_low_bandwidth") ||
        reason.startsWith("resolver_speed_upgrade") ||
        reason.startsWith("bridge_failures")
    val failureStormRecovery = reason.startsWith("bridge_failure_storm")
    val resolverUnreachableRecovery = reason.startsWith("resolver_unreachable")
    // Reconnect the current resolver with the opposite DNS carrier transport (udp<->tcp).
    // The resolver itself is fine, so it must not be rotated or marked bad here.
    val transportSwitchRecovery = reason.startsWith("transport_switch")
    // Network/SIM change: the old resolver belongs to the previous network and may be
    // unreachable or slow now. Re-select a resolver for the CURRENT network from scratch and
    // re-validate transport + qtype (both can differ per operator).
    val networkChangedRecovery = reason.startsWith("network_changed")
    val trafficRecovery = reason.startsWith("traffic_no_response") ||
        resolverUnreachableRecovery
    val bridgeFailureRecovery = reason.startsWith("bridge_failures") || failureStormRecovery
    // "accumulated" fires from a rolling failure count, which a heavy burst can trip
    // even when the resolver itself is fine (it's just momentarily overwhelmed by a
    // wave of parallel SOCKS CONNECTs during an upload). Give it one quick same-resolver
    // retry before condemning the resolver and paying for a full multi-candidate rescan
    // (~20s of total tunnel downtime). The "..._no_response" bridge-failure variant
    // (sustained silence) still rotates immediately -- that's a real dead-resolver signal.
    val bridgeAccumulatedRecovery = reason.startsWith("bridge_failures_accumulated")
    // A storm (>=12 new bridge failures within one 5s diag tick) can likewise just be
    // many parallel CONNECTs timing out together during a legitimate large upload, not a
    // bad resolver. Give it the same quick same-resolver retry as "accumulated"; if the
    // quick retry also fails, the resolver still gets marked bad on the next tick.
    val bridgeFailureFastRetry = bridgeAccumulatedRecovery || failureStormRecovery
    // Silence-type failures (tunnel went quiet: traffic_no_response, or bridge failures
    // during sustained no-response) may be a transient blip rather than a dead resolver.
    // Retry the CURRENT resolver once before rotating; if it is genuinely dead, the
    // waitForSlipstreamReady failure below marks it bad, so the next diagnostic tick sees
    // currentAlreadyFailed and rotates to the next-best cached resolver. resolver_unreachable
    // is deliberately excluded -- a failed TCP reachability probe already proves the
    // resolver is gone, so there is nothing to retry and we rotate immediately.
    val silenceRecovery = reason.startsWith("traffic_no_response") ||
        (reason.startsWith("bridge_failures") && reason.endsWith("_no_response"))
    val retryCurrentFirst = silenceRecovery && hasCurrentResolver && !currentResolverAlreadyFailed
    val nativeNotReadyRecovery = reason.startsWith("native_not_ready")
    val nativeDownFastRecovery = reason == "native_not_running" &&
        resolverMode == Config.ResolverMode.AUTO &&
        hasCurrentResolver
    val autoFastRecovery = resolverMode == Config.ResolverMode.AUTO &&
        hasCurrentResolver &&
        !failureStormRecovery &&
        (nativeDownFastRecovery || nativeNotReadyRecovery)
    val reuseCurrentResolver = isNativeNoProgress ||
        bridgeFailureFastRetry ||
        retryCurrentFirst ||
        (fastPathRecovery && !trafficRecovery && !bridgeFailureRecovery)
    val rotateResolver = !retryCurrentFirst &&
        ((reason == "native_not_running" && !isNativeNoProgress) ||
            trafficRecovery ||
            (bridgeFailureRecovery && !bridgeFailureFastRetry) ||
            nativeNotReadyRecovery ||
            resolverUnreachableRecovery)
    return RecoveryReasonClass(
        isNativeNoProgress = isNativeNoProgress,
        fastPathRecovery = fastPathRecovery,
        failureStormRecovery = failureStormRecovery,
        resolverUnreachableRecovery = resolverUnreachableRecovery,
        transportSwitchRecovery = transportSwitchRecovery,
        networkChangedRecovery = networkChangedRecovery,
        trafficRecovery = trafficRecovery,
        bridgeFailureRecovery = bridgeFailureRecovery,
        bridgeAccumulatedRecovery = bridgeAccumulatedRecovery,
        bridgeFailureFastRetry = bridgeFailureFastRetry,
        silenceRecovery = silenceRecovery,
        retryCurrentFirst = retryCurrentFirst,
        nativeNotReadyRecovery = nativeNotReadyRecovery,
        nativeDownFastRecovery = nativeDownFastRecovery,
        autoFastRecovery = autoFastRecovery,
        reuseCurrentResolver = reuseCurrentResolver,
        rotateResolver = rotateResolver
    )
}
