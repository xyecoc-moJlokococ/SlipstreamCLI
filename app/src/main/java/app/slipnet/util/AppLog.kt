package app.slipnet.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private const val FILE_NAME = "vaydns-debug.log"
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        i("AppLog", "log initialized")
    }

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun d(tag: String, message: String) = write(android.util.Log.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = write(android.util.Log.INFO, tag, message, null)
    fun w(tag: String, message: String) = write(android.util.Log.WARN, tag, message, null)
    fun e(tag: String, message: String, error: Throwable? = null) = write(android.util.Log.ERROR, tag, message, error)

    private fun write(priority: Int, tag: String, message: String, error: Throwable?) {
        android.util.Log.println(priority, tag, message)
        if (error != null) android.util.Log.e(tag, message, error)
        val context = appContext ?: return
        val line = buildString {
            append(stamp.format(Date()))
            append(' ')
            append(level(priority))
            append('/')
            append(tag)
            append(": ")
            append(message)
            if (error != null) {
                append('\n')
                append(android.util.Log.getStackTraceString(error))
            }
            append('\n')
        }
        runCatching {
            val f = file(context)
            if (f.length() > 2_000_000) f.writeText("")
            f.appendText(line)
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
