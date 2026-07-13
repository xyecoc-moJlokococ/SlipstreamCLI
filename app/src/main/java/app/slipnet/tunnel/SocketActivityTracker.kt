package app.slipnet.tunnel

import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks last-activity timestamps for a set of keys (in production: open bridge Sockets) so an
 * overload-eviction policy can prefer killing the most IDLE connections instead of blindly
 * killing whichever were opened first. A long-lived but actively-used connection (e.g. a game's
 * persistent session socket) should survive eviction even though it's "old"; a socket that has
 * gone quiet is the safer thing to sacrifice regardless of age.
 *
 * Generic over the key type purely so this is unit-testable without real Sockets -- production
 * code keys it by `Socket` (identity equality, which `Socket` gets for free since it doesn't
 * override `equals`/`hashCode`).
 */
class SocketActivityTracker<T : Any> {
    private val lastActivityAt = ConcurrentHashMap<T, Long>()

    /** Record that [key] just did something useful (opened, sent, or received data). */
    fun touch(key: T, now: Long = System.currentTimeMillis()) {
        lastActivityAt[key] = now
    }

    /** Stop tracking [key] -- call this whenever the underlying connection is torn down. */
    fun remove(key: T) {
        lastActivityAt.remove(key)
    }

    /** Drop all tracked keys, e.g. on a full bridge restart. */
    fun clear() {
        lastActivityAt.clear()
    }

    /**
     * Returns up to [count] entries from [candidates], least-recently-active first. A candidate
     * with no recorded activity sorts as maximally idle (activity = 0) -- every candidate should
     * have been touched when it was added, so this only matters as a defensive fallback, not the
     * common case.
     */
    fun selectLeastRecentlyActive(candidates: List<T>, count: Int): List<T> {
        if (count <= 0 || candidates.isEmpty()) return emptyList()
        return candidates
            .sortedBy { lastActivityAt[it] ?: 0L }
            .take(count)
    }
}
