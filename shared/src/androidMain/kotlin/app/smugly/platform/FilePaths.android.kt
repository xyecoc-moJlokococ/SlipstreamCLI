package app.smugly.platform

/**
 * On Android, callers should prefer Context.filesDir. This fallback is only used
 * when the Application context has not been installed yet (early process init).
 */
actual object AppPaths {
    @Volatile
    var overrideDir: String? = null

    actual fun filesDir(): String =
        overrideDir ?: System.getProperty("java.io.tmpdir") ?: "/data/local/tmp"
}
