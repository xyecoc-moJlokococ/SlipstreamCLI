package app.smugly.tunnel

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
 *  - Absolute age cap: any connection older than [maxAgeMs] is reaped regardless of activity.
 *  - Jammed buffer: a relay buffer that has been non-empty continuously for [stuckMs] means data
 *    isn't draining.
 *  - Half-closed connection: reap on a much shorter idle ([halfIdleMs]) OR once it has been
 *    half-closed for [halfMaxMs] regardless of trickle.
 *  - Fully-open connection: reap only after [fullIdleMs] of no progress.
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
