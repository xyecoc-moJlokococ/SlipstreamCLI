package app.smugly.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import app.smugly.AppLanguage
import app.smugly.Config
import app.smugly.Strings
import app.smugly.defaultConfig
import app.smugly.ui.ConnectUiState
import app.smugly.ui.EditorDraft
import app.smugly.ui.defaultGlobalSettings
import app.smugly.ui.emptyDraft
import app.smugly.ui.screens.DiagnosticsScreen
import app.smugly.ui.screens.HomeScreen
import app.smugly.ui.screens.ProfileEditorScreen
import app.smugly.ui.screens.SettingsScreen
import app.smugly.ui.theme.SmuglyTheme

/**
 * Headless pass over every main screen **and the profile editor** so AppCDS / JIT see the same
 * classes a real user loads (Home ↔ Settings ↔ Diagnostics ↔ New profile).
 *
 * Invoked from [run-desktop-windows.cmd] when `smugly-dev.jsa` is missing (fresh stage after
 * compile). Uses [ImageComposeScene] — no AWT window, SOFTWARE raster is fine and fast.
 *
 * Exit 0 is required for `-XX:ArchiveClassesAtExit` to write the archive.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    // Quiet, predictable: no GPU, no window chrome. We only care about class loading + first layout.
    System.setProperty("skiko.renderApi", "SOFTWARE")
    System.setProperty("skiko.vsync.enabled", "false")
    Strings.set(AppLanguage.EN)

    val density = Density(1f)
    val width = 420
    val height = 780
    // A few more frames for the heavier editor / JSON path so C2 gets a look-in.
    val frames = 6
    val frameNs = 16_000_000L

    fun bake(label: String, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = width, height = height, density = density) {
            SmuglyTheme {
                content()
            }
        }
        try {
            var t = 0L
            repeat(frames) {
                scene.render(t)
                t += frameNs
            }
            System.err.println("warmup: $label ok")
        } finally {
            scene.close()
        }
    }

    @Composable
    fun editor(draft: EditorDraft) {
        ProfileEditorScreen(
            draft = draft,
            onBack = {},
            onChange = {},
            onSave = {},
            onDelete = null,
            onLocalDns = {},
            formatXray = { it }
        )
    }

    val settings = defaultGlobalSettings(mode = Config.Mode.PROXY).copy(localSocksAuthEnabled = false)
    val connect = ConnectUiState.idle()
    val base = defaultConfig(listenPort = 1080, mode = Config.Mode.PROXY)

    bake("home") {
        HomeScreen(
            profiles = emptyList(),
            activeId = null,
            connect = connect,
            onMenu = {},
            onAddNew = {},
            onImportClipboard = {},
            onImportFile = {},
            onSelect = {},
            onEdit = {},
            onDelete = {},
            onExport = {},
            onToggle = {},
            subscriptions = emptyList()
        )
    }
    bake("settings") {
        SettingsScreen(
            settings = settings,
            supportsVpn = false,
            showTrafficNotification = false,
            showLocalSocksAuth = false,
            onMenu = {},
            onChange = {}
        )
    }
    bake("diagnostics") {
        DiagnosticsScreen(
            logText = "warmup\nline2\nline3",
            onMenu = {},
            onShareLog = {},
            onCrashReport = {},
            onRefreshLog = {}
        )
    }
    // "New profile" path — this is the hitch the user still felt after Home/Settings CDS alone.
    bake("editor-slipstream") {
        editor(emptyDraft(base.copy(protocol = Config.TunnelProtocol.SLIPSTREAM)))
    }
    bake("editor-s3fu") {
        editor(
            emptyDraft(
                base.copy(
                    protocol = Config.TunnelProtocol.S3FU,
                    s3Endpoint = "https://s3.example.com",
                    s3Bucket = "warmup",
                    s3AccessKey = "ak",
                    s3SecretKey = "sk"
                )
            )
        )
    }
    bake("editor-xray") {
        editor(
            emptyDraft(
                base.copy(
                    protocol = Config.TunnelProtocol.XRAY,
                    xrayConfigJson = """{"outbounds":[{"protocol":"freedom","tag":"direct"}]}"""
                )
            )
        )
    }

    // Touch a few more desktop-only entry points so the archive covers chrome/proxy too.
    runCatching { Class.forName("app.smugly.ui.WindowChromeKt") }
    runCatching { Class.forName("app.smugly.ui.DesktopMainKt") }
    runCatching { Class.forName("app.smugly.desktop.WindowsSystemProxy") }
    runCatching { Class.forName("app.smugly.desktop.MixedProxyServer") }
    runCatching { Class.forName("app.smugly.ui.components.JsonHighlightKt") }
    runCatching { Class.forName("app.smugly.ui.components.FolderEditorKt") }

    System.err.println("warmup: done")
    // Explicit success so ArchiveClassesAtExit always fires.
    kotlin.system.exitProcess(0)
}
