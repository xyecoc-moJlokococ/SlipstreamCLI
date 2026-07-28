package app.vaydns.ui

import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

@Composable
actual fun rememberTickHaptic(): () -> Unit {
    val view = LocalView.current
    return remember(view) {
        { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    }
}
