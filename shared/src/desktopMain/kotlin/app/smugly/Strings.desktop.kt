package app.smugly

import java.util.Locale

actual fun defaultLanguageCode(): String = Locale.getDefault().language
