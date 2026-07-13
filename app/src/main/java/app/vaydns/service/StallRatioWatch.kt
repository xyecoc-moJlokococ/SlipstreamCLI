package app.vaydns.service

/**
 * Windowed detector for the "half-silent degradation" gap that the existing recovery triggers all
 * miss: the tunnel is native-ready, the app keeps generating upstream traffic (poll/request bytes
 * flowing out), but downstream responses come back as a thin, useless trickle -- non-zero, so
 * `traffic_no_response` (which requires responseDelta == 0) never accumulates because a single
 * 60-byte reply keeps `lastProgressAt` fresh; too few connection failures to trip the failure
 * storm/accumulated thresholds; and `slowResponse`/`lowBandwidth` deliberately no longer trigger
 * recovery (they flapped on genuinely-slow-but-working browsing). Observed live on Tele2 TCP: 100+
 * seconds of deltaReq≈40k vs deltaResp≈3k with recovering=false the whole time.
 *
 * The discriminator between "slow but working" and "starved" is the RATIO, not the absolute
 * downstream volume: a healthy DNS tunnel returns downstream bytes roughly commensurate with (or
 * exceeding) the upstream request/poll bytes, while a throttled carrier keeps the client re-polling
 * (requestDelta high) for responses that never really arrive (responseDelta a small fraction of it).
 *
 * Same arm-then-fire shape as [BridgeFailureWatch]. It arms on the first "starved" tick and fires
 * once the starvation has persisted for [windowMs] AND the caller is outside its recovery cooldown.
 * Critically it RE-ARMS (resets) on any healthy tick -- so normal slow browsing, which produces
 * commensurate downstream in between, never accumulates a full window; only a sustained, unbroken
 * starvation stretch (a real throttled/dead carrier) fires.
 */
class StallRatioWatch(
    private val minRequestBytes: Long,
    private val responseDivisor: Long,
    private val windowMs: Long
) {
    private var starvedSince = 0L

    class Fired(val windowMs: Long, val requestBytesDelta: Long, val responseBytesDelta: Long)

    /** Clear armed state -- call when a fresh tunnel session starts. */
    fun reset() {
        starvedSince = 0
    }

    /**
     * Call once per diagnostic tick. Returns [Fired] once starvation has persisted a full window
     * (and cooldown allows), or null otherwise.
     *
     * A tick counts as "starved" when the tunnel is running+ready, not already recovering, not in
     * the heavy-upload grace window (a legitimate upload also shows high upstream / low downstream),
     * the upstream request volume is meaningful (>= [minRequestBytes], so idle keepalive polls don't
     * qualify), and downstream is a small fraction of it (requestDelta >= [responseDivisor] *
     * responseDelta). Any tick that isn't starved re-arms the watch from scratch.
     */
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
