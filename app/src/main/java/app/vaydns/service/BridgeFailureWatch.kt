package app.vaydns.service

/**
 * Windowed accumulate-then-fire counter for bridge/native failures, extracted out of
 * TinyVpnService.updateBridgeFailureWatch so the arm/reset/fire decision can be driven with
 * hand-picked values instead of a running VPN. Same accumulate-then-fire shape as the Rust
 * client poll loop's no-progress detector (slipstream-client's runtime/stall.rs StallDetector).
 *
 * The watch arms on the first failure past its base, then fires once [recoveryTotal] failures
 * have accumulated AND [recoveryWindowMs] has elapsed since arming AND the caller is outside its
 * own recovery cooldown. It resets (re-arms fresh) whenever the tunnel isn't in a state where
 * failures should accumulate (not running/ready, or already recovering), or when failureTotal
 * drops back to/below the armed base -- a new episode, not the one being watched.
 */
class BridgeFailureWatch(
    private val recoveryTotal: Long,
    private val recoveryWindowMs: Long
) {
    private var watchStartAt = 0L
    private var watchBase = 0L

    class Fired(val accumulated: Long, val windowMs: Long)

    /** Clear armed state -- call when a fresh tunnel session starts so a new episode's failures
     * aren't measured against a base left over from the previous session. */
    fun reset() {
        watchStartAt = 0
        watchBase = 0
    }

    /** Call once per diagnostic tick. Returns [Fired] once the watch fires, or null otherwise. */
    fun tick(
        now: Long,
        running: Boolean,
        ready: Boolean,
        recovering: Boolean,
        failureTotal: Long,
        lastRecoveryAt: Long,
        recoveryCooldownMs: Long
    ): Fired? {
        if (!running || !ready || recovering) {
            watchStartAt = 0
            watchBase = failureTotal
            return null
        }
        if (failureTotal <= watchBase) {
            watchStartAt = 0
            watchBase = failureTotal
            return null
        }
        if (watchStartAt == 0L) {
            watchStartAt = now
            watchBase = (failureTotal - 1).coerceAtLeast(0)
            return null
        }
        val accumulated = failureTotal - watchBase
        val windowMs = now - watchStartAt
        if (
            accumulated >= recoveryTotal &&
            windowMs >= recoveryWindowMs &&
            now - lastRecoveryAt > recoveryCooldownMs
        ) {
            watchStartAt = 0
            watchBase = failureTotal
            return Fired(accumulated, windowMs)
        }
        return null
    }
}
