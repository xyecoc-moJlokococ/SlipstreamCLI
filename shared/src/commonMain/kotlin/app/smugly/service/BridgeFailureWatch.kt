package app.smugly.service

/**
 * Windowed accumulate-then-fire counter for bridge/native failures.
 * Pure multiplatform — no Android APIs.
 */
class BridgeFailureWatch(
    private val recoveryTotal: Long,
    private val recoveryWindowMs: Long
) {
    private var watchStartAt = 0L
    private var watchBase = 0L

    class Fired(val accumulated: Long, val windowMs: Long)

    fun reset() {
        watchStartAt = 0
        watchBase = 0
    }

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
