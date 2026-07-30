package app.smugly.ui

import androidx.compose.runtime.Composable

/** Desktops do not vibrate. */
@Composable
actual fun rememberTickHaptic(): () -> Unit = {}
