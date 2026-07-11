package app.vaydns.service

/**
 * Pure threshold/cooldown check extracted out of maybeCheckResolverHealth's background reachability
 * probe callback: once enough consecutive reachability failures have piled up, should this
 * failure trigger a recovery (resolver rotation), or is the tunnel already mid-recovery / still in
 * its post-recovery cooldown? The actual network probe and the restartSlipstreamPath() call stay
 * in TinyVpnService -- only this decision is pure enough to unit test directly.
 */
internal fun shouldRotateOnResolverHealthFailure(
    failures: Int,
    failuresBeforeRotate: Int,
    tunnelActive: Boolean,
    recovering: Boolean,
    now: Long,
    lastRecoveryAt: Long,
    recoveryCooldownMs: Long
): Boolean =
    failures >= failuresBeforeRotate &&
        tunnelActive &&
        !recovering &&
        now - lastRecoveryAt > recoveryCooldownMs
