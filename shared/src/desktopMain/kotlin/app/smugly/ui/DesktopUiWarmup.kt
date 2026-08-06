package app.smugly.ui

/**
 * Background class loading so the first time the user opens Settings / the editor does not pay
 * the full ClassLoader + verification cost on the UI thread.
 *
 * AppCDS (smugly-dev.jsa / smugly.jsa) covers the same ground once a clean exit has written an
 * archive that includes these classes. This path covers the cold run after a rebuild, when the
 * archive is stale or missing — which is exactly when the "first Settings click freezes the app"
 * report shows up.
 *
 * Safe to call before the Compose window exists: only [Class.forName], no UI.
 */
internal object DesktopUiWarmup {
    fun start() {
        val cl = Thread.currentThread().contextClassLoader ?: return
        Thread({
            // Names are best-effort: missing ones are fine (obfuscation / R8 renames on other
            // targets do not apply here; desktop ships plain jars).
            val names = listOf(
                // Our screens and the heavy widgets they pull in.
                "app.smugly.ui.screens.ScreensKt",
                "app.smugly.ui.components.UiKitKt",
                "app.smugly.ui.components.SubscriptionCardKt",
                "app.smugly.ui.components.JsonHighlightKt",
                "app.smugly.ui.components.FolderEditorKt",
                "app.smugly.ui.components.FlagEmojiKt",
                "app.smugly.ConfigJson",
                "org.json.JSONObject",
                // Compose / Material3 bits Settings and the editor always touch.
                "androidx.compose.foundation.text.BasicTextFieldKt",
                "androidx.compose.foundation.lazy.LazyDslKt",
                "androidx.compose.material3.TextFieldKt",
                "androidx.compose.material3.CheckboxKt",
                "androidx.compose.material3.SwitchKt",
                "androidx.compose.material3.TextKt",
                "androidx.compose.material3.IconKt",
                "androidx.compose.material.icons.Icons",
                "androidx.compose.material.icons.filled.MenuKt",
                "androidx.compose.material.icons.filled.AddKt",
                "androidx.compose.material.icons.filled.SettingsKt",
                "androidx.compose.material.icons.rounded.PlayArrowKt",
                "androidx.compose.material.icons.rounded.StopKt",
                "androidx.compose.animation.core.Animatable",
                "androidx.compose.foundation.gestures.DragGestureDetectorKt",
            )
            for (name in names) {
                runCatching { Class.forName(name, true, cl) }
            }
        }, "smugly-ui-warmup").apply {
            isDaemon = true
            // Below UI priority so this never races the first Home frame for CPU.
            priority = (Thread.NORM_PRIORITY - 1).coerceAtLeast(Thread.MIN_PRIORITY)
            start()
        }
    }
}
