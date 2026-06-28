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
    private const val FLUSH_INTERVAL_MS = 15_000L
    private const val MAX_FILE_SIZE_BYTES = 2_000_000L
    private const val MAX_BUFFER_CHARS = 64 * 1024
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()
    private val pending = StringBuilder()
    @Volatile private var appContext: Context? = null
    @Volatile private var flusherStarted = false
    @Volatile private var fileLoggingEnabled = false

    fun init(context: Context) {
        appContext = context.applicationContext
        fileLoggingEnabled = isFileLoggingEnabled(context)
        if (fileLoggingEnabled) startFlusher()
        i("AppLog", "log initialized")
    }

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

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

    private fun write(priority: Int, tag: String, message: String, error: Throwable?) {
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
        runCatching {
            val f = file(context)
            if (f.length() > MAX_FILE_SIZE_BYTES) f.writeText("")
            f.appendText(chunk)
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
