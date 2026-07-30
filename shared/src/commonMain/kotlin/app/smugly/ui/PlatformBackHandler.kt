package app.smugly.ui

import androidx.compose.runtime.Composable

/**
 * System back (Android) / Esc (desktop if wired). No-op on platforms without a back key.
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)
