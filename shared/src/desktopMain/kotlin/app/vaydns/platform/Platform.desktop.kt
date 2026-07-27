package app.vaydns.platform

actual object PlatformTime {
    actual fun currentTimeMillis(): Long = System.currentTimeMillis()
}

actual object PlatformLog {
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
    }
}
