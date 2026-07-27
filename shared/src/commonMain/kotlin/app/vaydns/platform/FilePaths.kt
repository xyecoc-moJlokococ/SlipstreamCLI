package app.vaydns.platform

/**
 * Platform-specific app data directory for logs / profile files.
 * Android uses Context.filesDir; desktop uses ~/.vaydns; iOS uses Documents.
 */
expect object AppPaths {
    /** Absolute path to a writable app-private directory. */
    fun filesDir(): String
}
