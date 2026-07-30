package app.smugly.platform

/**
 * In-memory [KeyValueStore] for unit tests and headless desktop sessions.
 */
class MemoryKeyValueStore : KeyValueStore {
    private val data = mutableMapOf<String, Any?>()
    private val lock = PlatformLock()

    override fun getString(key: String, default: String?): String? =
        lock.withLock { data[key] as? String ?: default }

    override fun getInt(key: String, default: Int): Int =
        lock.withLock { (data[key] as? Number)?.toInt() ?: default }

    override fun getBoolean(key: String, default: Boolean): Boolean =
        lock.withLock { data[key] as? Boolean ?: default }

    override fun getLong(key: String, default: Long): Long =
        lock.withLock { (data[key] as? Number)?.toLong() ?: default }

    override fun contains(key: String): Boolean =
        lock.withLock { key in data }

    override fun edit(): KeyValueStore.Editor = object : KeyValueStore.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()

        override fun putString(key: String, value: String?): KeyValueStore.Editor {
            if (value == null) removals.add(key) else {
                removals.remove(key)
                pending[key] = value
            }
            return this
        }

        override fun putInt(key: String, value: Int): KeyValueStore.Editor {
            removals.remove(key)
            pending[key] = value
            return this
        }

        override fun putBoolean(key: String, value: Boolean): KeyValueStore.Editor {
            removals.remove(key)
            pending[key] = value
            return this
        }

        override fun putLong(key: String, value: Long): KeyValueStore.Editor {
            removals.remove(key)
            pending[key] = value
            return this
        }

        override fun remove(key: String): KeyValueStore.Editor {
            pending.remove(key)
            removals.add(key)
            return this
        }

        override fun apply() {
            commit()
        }

        override fun commit(): Boolean {
            lock.withLock {
                removals.forEach { data.remove(it) }
                data.putAll(pending)
            }
            return true
        }
    }
}
