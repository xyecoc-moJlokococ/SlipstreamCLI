package app.vaydns

import java.util.Locale

actual fun defaultLanguageCode(): String = Locale.getDefault().language
