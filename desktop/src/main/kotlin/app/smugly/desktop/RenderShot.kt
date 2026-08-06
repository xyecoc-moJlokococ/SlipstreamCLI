package app.smugly.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import app.smugly.AppLanguage
import app.smugly.ConfigJson
import app.smugly.ConfigProfile
import app.smugly.Strings
import app.smugly.subscription.SubscriptionJson
import app.smugly.ui.ConnectUiState
import app.smugly.ui.screens.HomeScreen
import app.smugly.ui.theme.SmuglyTheme
import org.jetbrains.skia.EncodedImageFormat
import org.json.JSONArray
import java.io.File

/**
 * Renders the Home screen straight to a PNG, with no window.
 *
 * A dev harness, not part of the app: it exists so a layout change can be *looked at* on a machine
 * whose screen is off or locked, where an ordinary screenshot comes back black. Point it at a
 * directory holding `profiles.json` + `subscriptions.json` (the desktop store's own format) and it
 * writes `<out>.png`; pass a tap coordinate to capture the same screen after a click, which is how
 * a folded category gets rendered.
 *
 * Usage: `java -cp "lib\…" app.smugly.desktop.RenderShotKt <dataDir> <out.png> [tapX tapY] [stopMs] [topInsetPx]`
 *
 * `topInsetPx` reproduces the desktop's own title bar sitting above the screen — the offset that
 * once pushed every anchored menu down by exactly its height.
 * (Kotlin nests block comments, so the classpath wildcard cannot be written out here.)
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val dataDir = File(args.getOrElse(0) { "." })
    val out = File(args.getOrElse(1) { "shot.png" })
    val tap = if (args.size >= 4) Offset(args[2].toFloat(), args[3].toFloat()) else null
    Strings.set(AppLanguage.RU)

    val profiles = readProfiles(File(dataDir, "profiles.json"))
    val subscriptions = SubscriptionJson.listFromString(File(dataDir, "subscriptions.json").readText())

    // Reproduces the desktop's own title bar sitting above the screen — the offset that once
    // pushed every anchored menu down by exactly its height.
    val topInset = args.getOrNull(5)?.toIntOrNull() ?: 0
    val scene = ImageComposeScene(width = 1080, height = 2100, density = Density(2.75f)) {
        SmuglyTheme {
            Column(Modifier.fillMaxSize()) {
                if (topInset > 0) Spacer(Modifier.fillMaxWidth().height(with(Density(2.75f)) { topInset.toDp() }))
                HomeScreen(
                    profiles = profiles,
                    activeId = profiles.firstOrNull { it.subscriptionId != null }?.id,
                    connect = ConnectUiState.idle(),
                    onMenu = {},
                    onAddNew = {},
                    onImportClipboard = {},
                    onImportFile = {},
                    onSelect = {},
                    onEdit = {},
                    onDelete = {},
                    onExport = {},
                    onToggle = {},
                    subscriptions = subscriptions,
                    initialFolderId = subscriptions.firstOrNull()?.id.orEmpty()
                )
            }
        }
    }
    try {
        // One frame to lay out, then the tap (if any) and enough frames for the fold to finish.
        scene.render(0)
        // How far past the tap to stop, in ms — a mid-fold frame is where an overlap would show.
        val stopAtMs = args.getOrNull(4)?.toLong() ?: 700
        if (tap != null) {
            scene.sendPointerEvent(PointerEventType.Press, tap)
            scene.sendPointerEvent(PointerEventType.Release, tap)
            var t = 0L
            while (t < stopAtMs * 1_000_000L) {
                scene.render(t)
                t += 16_000_000L
            }
        }
        val image = scene.render(stopAtMs * 1_000_000L)
        out.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    } finally {
        scene.close()
    }
    println("wrote ${out.absolutePath} (${out.length()} bytes)")
}

private fun readProfiles(file: File): List<ConfigProfile> {
    val arr = JSONArray(file.readText())
    return (0 until arr.length()).map { ConfigJson.profileFromJson(arr.getJSONObject(it)) }
}
