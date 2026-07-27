package app.vaydns.platform

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

actual object PlatformTime {
    actual fun currentTimeMillis(): Long = System.currentTimeMillis()
}

actual object PlatformLog {
    /**
     * Mirror log lines into `vaydns-debug.log` so the Diagnostics screen can show them. Driven by
     * the "debug mode" setting: the engine subprocess is chatty, and its output is the only place
     * tunnel failures are ever explained — but writing it unconditionally would grow a file nobody
     * asked for.
     */
    @Volatile var fileLoggingEnabled: Boolean = false

    private const val MAX_BYTES = 2L * 1024 * 1024
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    private val logFile: File get() = File(AppPaths.filesDir(), "vaydns-debug.log")

    actual fun log(level: LogLevel, tag: String, message: String, error: Throwable?) {
        val stream = if (level == LogLevel.ERROR || level == LogLevel.WARN) System.err else System.out
        val prefix = when (level) {
            LogLevel.DEBUG -> "D"
            LogLevel.INFO -> "I"
            LogLevel.WARN -> "W"
            LogLevel.ERROR -> "E"
        }
        stream.println("$prefix/$tag: $message")
        error?.printStackTrace(stream)
        if (fileLoggingEnabled) appendToFile(prefix, tag, message, error)
    }

    private fun appendToFile(prefix: String, tag: String, message: String, error: Throwable?) {
        runCatching {
            synchronized(lock) {
                val f = logFile
                // Single rotation, so a crash cause isn't lost the moment the file fills up.
                if (f.length() > MAX_BYTES) {
                    val old = File(f.parentFile, f.name + ".1")
                    runCatching { old.delete() }
                    runCatching { f.renameTo(old) }
                }
                f.appendText(
                    buildString {
                        append(stamp.format(Date())).append(' ')
                        append(prefix).append('/').append(tag).append(": ").append(message).append('\n')
                        if (error != null) append(error.stackTraceToString()).append('\n')
                    }
                )
            }
        }
    }
}
