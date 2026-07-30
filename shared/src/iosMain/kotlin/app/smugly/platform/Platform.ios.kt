package app.smugly.platform

import platform.Foundation.NSDate
import platform.Foundation.NSLog
import platform.Foundation.timeIntervalSince1970

actual object PlatformTime {
    actual fun currentTimeMillis(): Long =
        (NSDate().timeIntervalSince1970 * 1000.0).toLong()
}

actual object PlatformLog {
    actual fun log(level: LogLevel, tag: String, message: String, error: Throwable?) {
        val prefix = when (level) {
            LogLevel.DEBUG -> "D"
            LogLevel.INFO -> "I"
            LogLevel.WARN -> "W"
            LogLevel.ERROR -> "E"
        }
        val err = error?.message?.let { " ($it)" }.orEmpty()
        NSLog("$prefix/$tag: $message$err")
    }
}
