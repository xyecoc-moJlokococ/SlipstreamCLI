package app.vaydns.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

@Composable
actual fun rememberTickHaptic(): () -> Unit {
    // No UIKit interop needed here — Compose's own selection tick is the light one on iOS.
    val haptics = LocalHapticFeedback.current
    return remember(haptics) {
        { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    }
}
