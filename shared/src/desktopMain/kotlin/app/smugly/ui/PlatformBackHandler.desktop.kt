package app.smugly.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent as AwtKeyEvent

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // Escape = back on desktop (profile editor → home, close drawer, etc.).
    DisposableEffect(enabled, onBack) {
        if (!enabled) {
            return@DisposableEffect onDispose { }
        }
        val dispatcher = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val listener = java.awt.KeyEventDispatcher { e ->
            if (e.id == AwtKeyEvent.KEY_PRESSED && e.keyCode == AwtKeyEvent.VK_ESCAPE) {
                onBack()
                true
            } else {
                false
            }
        }
        dispatcher.addKeyEventDispatcher(listener)
        onDispose { dispatcher.removeKeyEventDispatcher(listener) }
    }
}
