package app.vaydns.platform

import java.io.File

actual object AppPaths {
    actual fun filesDir(): String {
        val home = System.getProperty("user.home") ?: "."
        val dir = File(home, ".vaydns")
        if (!dir.exists()) dir.mkdirs()
        return dir.absolutePath
    }
}
