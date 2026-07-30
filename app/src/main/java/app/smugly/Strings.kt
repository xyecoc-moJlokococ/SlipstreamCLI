package app.smugly

import android.content.Context

/**
 * Android bootstrap for [Strings] (shared). Enum [S] and [t] live in `:shared`.
 */
object AndroidStrings {
    fun init(context: Context) {
        Strings.set(ConfigStore.loadGlobalSettings(context).language)
    }
}
