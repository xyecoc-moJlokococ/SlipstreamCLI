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
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.WindowConstants

/**
 * Headless pass over every main screen **and the profile editor** so AppCDS / JIT see the same
 * classes a real user loads (Home ↔ Settings ↔ Diagnostics ↔ New profile).
 *
 * Invoked from [run-desktop-windows.cmd] when `smugly-dev.jsa` is missing (fresh stage after
 * compile). Shows a small Discord-style splash (dark + centered spinner) while baking, then exits
 * 0 so `-XX:ArchiveClassesAtExit` can write the archive.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    // Quiet bake: SOFTWARE is enough; the splash is plain Swing, not Skiko.
    System.setProperty("skiko.renderApi", "SOFTWARE")
    System.setProperty("skiko.vsync.enabled", "false")
    Strings.set(AppLanguage.EN)

    val splash = openSplash()
    try {
        runWarmup { label ->
            setSplashStatus(splash, label)
            System.err.println("warmup: $label")
        }
        setSplashStatus(splash, "Almost ready…")
        System.err.println("warmup: done")
    } finally {
        closeSplash(splash)
    }
    // Explicit success so ArchiveClassesAtExit always fires.
    kotlin.system.exitProcess(0)
}

@OptIn(ExperimentalComposeUiApi::class)
private fun runWarmup(onStep: (String) -> Unit) {
    val density = Density(1f)
    val width = 420
    val height = 780
    val frames = 6
    val frameNs = 16_000_000L

    fun bake(label: String, content: @Composable () -> Unit) {
        onStep(label)
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

    bake("Loading home…") {
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
    bake("Loading settings…") {
        SettingsScreen(
            settings = settings,
            supportsVpn = false,
            showTrafficNotification = false,
            showLocalSocksAuth = false,
            onMenu = {},
            onChange = {}
        )
    }
    bake("Loading diagnostics…") {
        DiagnosticsScreen(
            logText = "warmup\nline2\nline3",
            onMenu = {},
            onShareLog = {},
            onCrashReport = {},
            onRefreshLog = {}
        )
    }
    bake("Loading profile editor…") {
        editor(emptyDraft(base.copy(protocol = Config.TunnelProtocol.SLIPSTREAM)))
    }
    bake("Loading S3 editor…") {
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
    bake("Loading CDN editor…") {
        editor(
            emptyDraft(
                base.copy(
                    protocol = Config.TunnelProtocol.CDNFU,
                    cdnfuUrl = "https://edge.example/",
                    cdnfuPsk = "warmup"
                )
            )
        )
    }
    bake("Loading Xray editor…") {
        editor(
            emptyDraft(
                base.copy(
                    protocol = Config.TunnelProtocol.XRAY,
                    xrayConfigJson = """{"outbounds":[{"protocol":"freedom","tag":"direct"}]}"""
                )
            )
        )
    }

    onStep("Finishing…")
    runCatching { Class.forName("app.smugly.ui.WindowChromeKt") }
    runCatching { Class.forName("app.smugly.ui.DesktopMainKt") }
    runCatching { Class.forName("app.smugly.desktop.WindowsSystemProxy") }
    runCatching { Class.forName("app.smugly.desktop.MixedProxyServer") }
    runCatching { Class.forName("app.smugly.ui.components.JsonHighlightKt") }
    runCatching { Class.forName("app.smugly.ui.components.FolderEditorKt") }
}

// ---- Splash: dark full panel + Material circular spinner (same language as Android LoadingOverlay) ----

private data class Splash(
    val frame: JFrame,
    val spinner: MaterialCircularSpinner
)

/**
 * Indeterminate ring like Material [CircularProgressIndicator]: 34dp, 3dp stroke, accent #C0392B.
 * Swing stand-in for the Compose spinner used on Android / in the main window boot screen.
 */
private class MaterialCircularSpinner(
    private val ringColor: Color = Color(0xC0, 0x39, 0x2B),
    /** Logical size in px (Compose 34.dp ≈ 34px at 1x; we use a bit more for crispness). */
    private val diameter: Int = 40,
    private val strokePx: Float = 3.5f
) : JComponent() {
    private var angle = 0
    private val timer = javax.swing.Timer(16) {
        angle = (angle + 8) % 360
        repaint()
    }

    init {
        isOpaque = false
        preferredSize = Dimension(diameter + 8, diameter + 8)
        minimumSize = preferredSize
        maximumSize = preferredSize
        timer.start()
    }

    fun stop() {
        timer.stop()
    }

    override fun paintComponent(g: java.awt.Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as java.awt.Graphics2D
        try {
            g2.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON
            )
            g2.stroke = java.awt.BasicStroke(
                strokePx,
                java.awt.BasicStroke.CAP_ROUND,
                java.awt.BasicStroke.JOIN_ROUND
            )
            g2.color = ringColor
            val pad = (strokePx / 2f).toInt() + 2
            val size = minOf(width, height) - pad * 2
            // Material indeterminate arc is ~270° of a circle, rotating.
            g2.drawArc(pad, pad, size, size, -angle, 270)
        } finally {
            g2.dispose()
        }
    }
}

private fun openSplash(): Splash {
    val bg = Color(0x11, 0x11, 0x11)

    lateinit var splash: Splash
    SwingUtilities.invokeAndWait {
        runCatching { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()) }
        val frame = JFrame("Smugly")
        frame.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        frame.isUndecorated = true
        frame.isAlwaysOnTop = true
        frame.background = bg

        // Compact splash — just room for the ring, not the main 420×780 window.
        val root = JPanel(BorderLayout())
        root.background = bg
        root.isOpaque = true

        val spinner = MaterialCircularSpinner()
        val center = JPanel(java.awt.GridBagLayout())
        center.background = bg
        center.isOpaque = true
        center.add(spinner)

        root.add(center, BorderLayout.CENTER)
        frame.contentPane = root
        frame.setSize(120, 120)
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
        frame.toFront()
        splash = Splash(frame, spinner)
    }
    return splash
}

private fun setSplashStatus(splash: Splash, text: String) {
    // Status text intentionally unused — Android loading is spinner-only here.
    // Keep the hook so bake steps still call in without UI thrash.
    SwingUtilities.invokeLater { splash.frame.repaint() }
}

private fun closeSplash(splash: Splash) {
    SwingUtilities.invokeAndWait {
        splash.spinner.stop()
        splash.frame.isVisible = false
        splash.frame.dispose()
    }
}
