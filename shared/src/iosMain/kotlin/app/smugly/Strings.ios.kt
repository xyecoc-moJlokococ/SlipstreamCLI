package app.smugly

import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages

actual fun defaultLanguageCode(): String {
    val preferred = NSLocale.preferredLanguages.firstOrNull() as? String
    return preferred?.take(2) ?: "en"
}
