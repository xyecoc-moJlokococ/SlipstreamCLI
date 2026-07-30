package app.smugly.service

/**
 * Windowed detector for half-silent tunnel degradation (high request / low response ratio).
 * Pure multiplatform.
 */
class StallRatioWatch(
    private val minRequestBytes: Long,
    private val responseDivisor: Long,
    private val windowMs: Long
) {
    private var starvedSince = 0L

    class Fired(val windowMs: Long, val requestBytesDelta: Long, val responseBytesDelta: Long)

    fun reset() {
        starvedSince = 0
    }

    fun tick(
        now: Long,
        running: Boolean,
        ready: Boolean,
        recovering: Boolean,
        uploadGraceActive: Boolean,
        requestBytesDelta: Long,
        responseBytesDelta: Long,
        lastRecoveryAt: Long,
        recoveryCooldownMs: Long
    ): Fired? {
        if (!running || !ready || recovering || uploadGraceActive) {
            starvedSince = 0
            return null
        }
        val starved = requestBytesDelta >= minRequestBytes &&
            requestBytesDelta >= responseDivisor * responseBytesDelta
        if (!starved) {
            starvedSince = 0
            return null
        }
        if (starvedSince == 0L) {
            starvedSince = now
            return null
        }
        val elapsed = now - starvedSince
        if (elapsed >= windowMs && now - lastRecoveryAt > recoveryCooldownMs) {
            starvedSince = 0
            return Fired(elapsed, requestBytesDelta, responseBytesDelta)
        }
        return null
    }
}
