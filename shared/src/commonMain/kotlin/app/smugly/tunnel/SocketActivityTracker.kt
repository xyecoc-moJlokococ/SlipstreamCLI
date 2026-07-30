package app.smugly.tunnel

import app.smugly.platform.PlatformLock
import app.smugly.platform.PlatformTime

/**
 * Tracks last-activity timestamps for a set of keys so overload eviction can
 * prefer killing the most idle connections. Multiplatform (no ConcurrentHashMap).
 */
class SocketActivityTracker<T : Any> {
    private val lastActivityAt = mutableMapOf<T, Long>()
    private val lock = PlatformLock()

    fun touch(key: T, now: Long = PlatformTime.currentTimeMillis()) {
        lock.withLock { lastActivityAt[key] = now }
    }

    fun remove(key: T) {
        lock.withLock { lastActivityAt.remove(key) }
    }

    fun clear() {
        lock.withLock { lastActivityAt.clear() }
    }

    fun selectLeastRecentlyActive(candidates: List<T>, count: Int): List<T> {
        if (count <= 0 || candidates.isEmpty()) return emptyList()
        val snapshot = lock.withLock { lastActivityAt.toMap() }
        return candidates
            .sortedBy { snapshot[it] ?: 0L }
            .take(count)
    }
}
