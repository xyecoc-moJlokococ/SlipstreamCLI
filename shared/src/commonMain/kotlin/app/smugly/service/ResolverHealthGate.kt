package app.smugly.service

/** Pure threshold/cooldown check for resolver health rotation. */
fun shouldRotateOnResolverHealthFailure(
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
