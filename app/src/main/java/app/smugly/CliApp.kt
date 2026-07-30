package app.smugly

import android.app.Application
import app.smugly.tunnel.XrayBridge
import app.smugly.util.AppLog
import app.smugly.platform.AppPaths

class CliApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppPaths.overrideDir = filesDir.absolutePath
        AppLog.init(this)
        AndroidStrings.init(this)
        // Process-wide, so both MainActivity (config validation) and TinyVpnService
        // (the actual tunnel) find the core initialized. Cheap and idempotent.
        XrayBridge.init(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLog.recordCrash(thread, throwable)
            AppLog.e("Crash", "uncaught thread=${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }
}
