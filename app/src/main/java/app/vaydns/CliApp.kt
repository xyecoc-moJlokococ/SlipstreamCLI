package app.vaydns

import android.app.Application
import app.slipnet.tunnel.XrayBridge
import app.slipnet.util.AppLog

class CliApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        Strings.init(this)
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
