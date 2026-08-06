package app.smugly.ui

/**
 * Background class loading so the first time the user opens Settings / the editor does not pay
 * the full ClassLoader + verification cost on the UI thread.
 *
 * AppCDS (`smugly-dev.jsa`) is built by the headless [app.smugly.desktop.WarmupCdsKt] pass when
 * the archive is missing after a rebuild. This path still helps when CDS is off or incomplete:
 * it races class loading while the first Home frame paints.
 *
 * Safe to call before the Compose window exists: only [Class.forName], no UI.
 */
internal object DesktopUiWarmup {
    fun start() {
        val cl = Thread.currentThread().contextClassLoader ?: return
        Thread({
            val names = listOf(
                // Screens + heavy widgets.
                "app.smugly.ui.screens.ScreensKt",
                "app.smugly.ui.components.UiKitKt",
                "app.smugly.ui.components.SubscriptionCardKt",
                "app.smugly.ui.components.JsonHighlightKt",
                "app.smugly.ui.components.FolderEditorKt",
                "app.smugly.ui.components.FlagEmojiKt",
                "app.smugly.ui.components.SubscriptionCardKt",
                "app.smugly.ui.WindowChromeKt",
                "app.smugly.ui.WindowAnimationKt",
                "app.smugly.ui.WindowResizeKt",
                "app.smugly.ConfigJson",
                "org.json.JSONObject",
                // Compose / Material3 bits Settings and the editor always touch.
                "androidx.compose.foundation.text.BasicTextFieldKt",
                "androidx.compose.foundation.lazy.LazyDslKt",
                "androidx.compose.foundation.lazy.LazyListKt",
                "androidx.compose.material3.TextFieldKt",
                "androidx.compose.material3.OutlinedTextFieldKt",
                "androidx.compose.material3.CheckboxKt",
                "androidx.compose.material3.SwitchKt",
                "androidx.compose.material3.TextKt",
                "androidx.compose.material3.IconKt",
                "androidx.compose.material3.ButtonKt",
                "androidx.compose.material3.ScrollableTabRowKt",
                "androidx.compose.material.icons.Icons",
                "androidx.compose.material.icons.filled.MenuKt",
                "androidx.compose.material.icons.filled.AddKt",
                "androidx.compose.material.icons.filled.SettingsKt",
                "androidx.compose.material.icons.filled.MoreVertKt",
                "androidx.compose.material.icons.rounded.PlayArrowKt",
                "androidx.compose.material.icons.rounded.StopKt",
                "androidx.compose.material.icons.rounded.CloseKt",
                "androidx.compose.animation.core.Animatable",
                "androidx.compose.foundation.gestures.DragGestureDetectorKt",
                "androidx.compose.foundation.gestures.ScrollableKt",
                // Desktop tunnel path (connect button).
                "app.smugly.desktop.DesktopTunnel",
                "app.smugly.desktop.MixedProxyServer",
                "app.smugly.desktop.EngineProcess",
                "app.smugly.desktop.WindowsSystemProxy",
            )
            for (name in names) {
                runCatching { Class.forName(name, true, cl) }
            }
        }, "smugly-ui-warmup").apply {
            isDaemon = true
            priority = (Thread.NORM_PRIORITY - 1).coerceAtLeast(Thread.MIN_PRIORITY)
            start()
        }
    }
}
