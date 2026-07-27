package app.vaydns.platform

/**
 * Platform clocks / logging / persistence used by the shared core.
 * UI and VPN integrations stay platform-specific; this is only the thin surface
 * pure logic needs so it can compile on Android, desktop (Windows/Linux/macOS), and iOS.
 */
expect object PlatformTime {
    fun currentTimeMillis(): Long
}

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

expect object PlatformLog {
    fun log(level: LogLevel, tag: String, message: String, error: Throwable? = null)
}

/**
 * Minimal key/value store for profiles and settings.
 * Android → SharedPreferences; desktop/iOS → JSON file under app data dir.
 */
interface KeyValueStore {
    fun getString(key: String, default: String? = null): String?
    fun getInt(key: String, default: Int): Int
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getLong(key: String, default: Long): Long
    fun contains(key: String): Boolean
    fun edit(): Editor

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putInt(key: String, value: Int): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun putLong(key: String, value: Long): Editor
        fun remove(key: String): Editor
        fun apply()
        fun commit(): Boolean
    }
}

/**
 * Protect a raw socket fd so it bypasses the VPN tunnel (Android VpnService.protect).
 * On desktop/iOS proxy-only mode this is a no-op that returns true.
 */
interface SocketProtector {
    fun protect(fd: Int): Boolean
    val proxyOnly: Boolean
}

object NoOpSocketProtector : SocketProtector {
    override fun protect(fd: Int): Boolean = true
    override val proxyOnly: Boolean = true
}

/** Process-wide socket protector installed by the VPN service (or left as no-op). */
object SocketProtect {
    private val lock = PlatformLock()
    private var _protector: SocketProtector = NoOpSocketProtector

    var protector: SocketProtector
        get() = lock.withLock { _protector }
        set(value) {
            lock.withLock { _protector = value }
        }

    fun protect(fd: Int): Boolean = protector.protect(fd)
    val proxyOnly: Boolean get() = protector.proxyOnly
}
