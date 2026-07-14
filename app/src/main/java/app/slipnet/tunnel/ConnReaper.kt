package app.slipnet.tunnel

/**
 * Pure reap-decision logic for the NIO bridge, extracted so the timeout policy is testable without
 * sockets or a running selector.
 *
 * The motivating incident: after the NIO rewrite removed the thread leak, CLOSE-WAIT sockets still
 * accumulated. Root cause seen live (monitor showed the idle reaper never firing): once one side of
 * a relay half-closes (tun2socks closes its upload), a byte-trickle download over the degraded DNS
 * carrier keeps refreshing lastActivity, so the plain 90s idle timeout never triggers and the
 * half-open connection lingers for minutes, piling up under connection churn.
 *
 * Policy:
 *  - Absolute age cap: any connection older than [maxAgeMs] is reaped regardless of activity. Bounds
 *    pathological cases (silent half-open, backpressure-blind FIN) that escape the other rules.
 *  - Jammed buffer: a relay buffer (toRemote or toClient) that has been non-empty continuously for
 *    [stuckMs] means data isn't draining -- on a healthy localhost hop it clears in microseconds, so
 *    a buffer stuck for tens of seconds means the far side (slipstream over a dead carrier, or a
 *    gone client) isn't accepting. Crucially this ALSO covers the case the other rules miss: under
 *    write-backpressure the bridge stops reading the client and never observes its FIN, so neither
 *    idle nor half-closed timers ever arm -- the connection would linger in CLOSE-WAIT for minutes
 *    while the buffer trickles out. This was the root cause of renewed CLOSE-WAIT growth in the field.
 *  - Half-closed connection (one side already EOF): reap on a much shorter idle ([halfIdleMs]) OR
 *    once it has been half-closed for [halfMaxMs] regardless of trickle.
 *  - Fully-open connection: reap only after [fullIdleMs] of no progress (unchanged, generous).
 */
object ConnReaper {
    fun shouldReap(
        nowMs: Long,
        lastActivityMs: Long,
        halfClosedAtMs: Long,   // 0 if not half-closed
        bufferStuckSinceMs: Long, // 0 if both relay buffers are currently empty
        fullIdleMs: Long,
        halfIdleMs: Long,
        halfMaxMs: Long,
        stuckMs: Long,
        createdAtMs: Long = 0L, // 0 = age cap disabled
        maxAgeMs: Long = 0L     // 0 = age cap disabled
    ): Boolean {
        if (maxAgeMs > 0L && createdAtMs > 0L && nowMs - createdAtMs >= maxAgeMs) return true
        if (bufferStuckSinceMs > 0L && nowMs - bufferStuckSinceMs >= stuckMs) return true
        if (halfClosedAtMs > 0L) {
            val idle = nowMs - lastActivityMs
            val sinceHalfClose = nowMs - halfClosedAtMs
            return idle >= halfIdleMs || sinceHalfClose >= halfMaxMs
        }
        return nowMs - lastActivityMs >= fullIdleMs
    }
}
