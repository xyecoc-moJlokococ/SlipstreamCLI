package app.vaydns.service

import app.vaydns.Config

/**
 * Pure classification of a recovery reason string into flags used by the tunnel restart path.
 * Multiplatform — no Android APIs.
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
        reason.startsWith("traffic_starved") ||
        reason.startsWith("resolver_speed_upgrade") ||
        reason.startsWith("bridge_failures")
    val failureStormRecovery = reason.startsWith("bridge_failure_storm")
    val resolverUnreachableRecovery = reason.startsWith("resolver_unreachable")
    val transportSwitchRecovery = reason.startsWith("transport_switch")
    val networkChangedRecovery = reason.startsWith("network_changed")
    val trafficRecovery = reason.startsWith("traffic_no_response") ||
        resolverUnreachableRecovery
    val bridgeFailureRecovery = reason.startsWith("bridge_failures") || failureStormRecovery
    val bridgeAccumulatedRecovery = reason.startsWith("bridge_failures_accumulated")
    val bridgeFailureFastRetry = bridgeAccumulatedRecovery || failureStormRecovery
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
