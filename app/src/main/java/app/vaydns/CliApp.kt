package app.vaydns

import android.app.Application
import app.slipnet.util.AppLog

class CliApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        Strings.init(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLog.recordCrash(thread, throwable)
            AppLog.e("Crash", "uncaught thread=${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }
}
