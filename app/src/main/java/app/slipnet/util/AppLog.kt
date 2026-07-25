package app.slipnet.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private const val PREFS = "app_log"
    private const val KEY_FILE_LOGGING_ENABLED = "file_logging_enabled"
    private const val FILE_NAME = "vaydns-debug.log"
    private const val CRASH_FILE_NAME = "vaydns-crash.log"
    private const val FLUSH_INTERVAL_MS = 15_000L
    private const val MAX_FILE_SIZE_BYTES = 2_000_000L
    private const val MAX_CRASH_FILE_SIZE_BYTES = 512_000L
    private const val MAX_BUFFER_CHARS = 64 * 1024
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()
    private val pending = StringBuilder()
    @Volatile private var appContext: Context? = null
    @Volatile private var flusherStarted = false
    @Volatile private var fileLoggingEnabled = false

    fun init(context: Context) {
        appContext = context.applicationContext
        // Respect the saved preference (default off). Do NOT force-enable on every start —
        // that used to re-check "Enable debug mode" after the user turned it off.
        fileLoggingEnabled = isFileLoggingEnabled(context)
        if (fileLoggingEnabled) {
            startFlusher()
            i("AppLog", "log initialized (file logging on)")
        }
    }

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)
    fun crashFile(context: Context): File = File(context.filesDir, CRASH_FILE_NAME)

    fun isFileLoggingEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_FILE_LOGGING_ENABLED, false)

    fun setFileLoggingEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FILE_LOGGING_ENABLED, enabled)
            .apply()
        fileLoggingEnabled = enabled
        if (!enabled) {
            synchronized(lock) { pending.setLength(0) }
        } else {
            startFlusher()
            i("AppLog", "file logging enabled")
        }
    }

    fun d(tag: String, message: String) = write(android.util.Log.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = write(android.util.Log.INFO, tag, message, null)
    fun w(tag: String, message: String) = write(android.util.Log.WARN, tag, message, null)
    fun e(tag: String, message: String, error: Throwable? = null) = write(android.util.Log.ERROR, tag, message, error)

    fun recordCrash(thread: Thread, error: Throwable) {
        val context = appContext ?: return
        val report = buildString {
            appendLine("time=${stamp.format(Date())}")
            appendLine("thread=${thread.name} id=${thread.id} state=${thread.state}")
            appendLine("message=${error::class.java.name}: ${error.message.orEmpty()}")
            appendLine(android.util.Log.getStackTraceString(error))
            appendLine("----")
        }
        runCatching {
            val f = crashFile(context)
            if (f.length() > MAX_CRASH_FILE_SIZE_BYTES) f.writeText("")
            f.appendText(report)
        }
    }

    private fun write(priority: Int, tag: String, message: String, error: Throwable?) {
        if (!fileLoggingEnabled && priority < android.util.Log.WARN) return
        android.util.Log.println(priority, tag, message)
        if (error != null) android.util.Log.e(tag, message, error)
        if (!fileLoggingEnabled) return
        var shouldFlush = priority >= android.util.Log.ERROR
        synchronized(lock) {
            pending.append(stamp.format(Date()))
            pending.append(' ')
            pending.append(level(priority))
            pending.append('/')
            pending.append(tag)
            pending.append(": ")
            pending.append(message)
            if (error != null) {
                pending.append('\n')
                pending.append(android.util.Log.getStackTraceString(error))
            }
            pending.append('\n')
            shouldFlush = shouldFlush || pending.length >= MAX_BUFFER_CHARS
        }
        if (shouldFlush) flush()
    }

    private fun startFlusher() {
        if (flusherStarted) return
        synchronized(lock) {
            if (flusherStarted) return
            flusherStarted = true
        }
        Thread({
            while (true) {
                try {
                    Thread.sleep(FLUSH_INTERVAL_MS)
                    flush()
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "app-log-flusher").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun flush() {
        val context = appContext ?: return
        val chunk = synchronized(lock) {
            if (pending.isEmpty()) return
            pending.toString().also { pending.setLength(0) }
        }
        try {
            val f = file(context)
            if (f.length() > MAX_FILE_SIZE_BYTES) rotate(f)
            f.appendText(chunk)
        } catch (error: Throwable) {
            // Never fail silently. A swallowed write error here is indistinguishable from "file
            // logging is switched off", and the log going quiet is exactly when it is being relied
            // on -- logcat is the only channel guaranteed to still work, so complain there.
            android.util.Log.e("AppLog", "file log write failed, dropped ${chunk.length} chars", error)
        }
    }

    /**
     * Roll over to `<name>.1` instead of truncating.
     *
     * The cap used to `writeText("")` the live file, which erased the whole history the moment it
     * was hit -- i.e. precisely after a long session, when the earlier context is what you need.
     * Keeping one previous generation bounds disk use at ~2x the cap while preserving it.
     */
    private fun rotate(f: File) {
        try {
            val previous = File(f.parentFile, "$FILE_NAME.1")
            if (previous.exists()) previous.delete()
            if (!f.renameTo(previous)) f.writeText("")
        } catch (error: Throwable) {
            android.util.Log.e("AppLog", "log rotation failed; truncating instead", error)
            runCatching { f.writeText("") }
        }
    }

    private fun level(priority: Int): String = when (priority) {
        android.util.Log.ERROR -> "E"
        android.util.Log.WARN -> "W"
        android.util.Log.INFO -> "I"
        android.util.Log.DEBUG -> "D"
        else -> "V"
    }
}
