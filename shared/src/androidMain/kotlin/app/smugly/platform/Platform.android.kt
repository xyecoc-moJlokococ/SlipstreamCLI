package app.smugly.platform

import android.util.Log

actual object PlatformTime {
    actual fun currentTimeMillis(): Long = System.currentTimeMillis()
}

actual object PlatformLog {
    actual fun log(level: LogLevel, tag: String, message: String, error: Throwable?) {
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message, error)
            LogLevel.INFO -> Log.i(tag, message, error)
            LogLevel.WARN -> Log.w(tag, message, error)
            LogLevel.ERROR -> Log.e(tag, message, error)
        }
    }
}
