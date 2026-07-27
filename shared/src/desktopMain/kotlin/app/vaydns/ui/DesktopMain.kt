package app.vaydns.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.vaydns.currentHostPlatform
import app.vaydns.platform.LogLevel
import app.vaydns.platform.PlatformLog
import app.vaydns.ui.theme.SlipnetBg
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Compose Multiplatform desktop entry — same Vaydns UI as Android/iOS shared code.
 */
fun main() {
    installDesktopCrashHandler()

    // GPU backends only. SOFTWARE feels like ~20–30 FPS. Must be set before any Skiko/Window init.
    if (System.getProperty("skiko.renderApi").isNullOrBlank()) {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        System.setProperty(
            "skiko.renderApi",
            when {
                // DIRECT3D resizes more reliably than ANGLE on some Win GPUs (no white dead zone).
                os.contains("win") -> "DIRECT3D"
                os.contains("mac") -> "METAL"
                else -> "OPENGL"
            }
        )
    }
    System.setProperty("skiko.vsync.enabled", "true")
    System.setProperty("sun.java2d.d3d", "true")
    System.setProperty("sun.java2d.noddraw", "false")
    // Stop AWT from flooding newly exposed regions with pure white during live resize.
    System.setProperty("sun.awt.noerasebackground", "true")
    System.setProperty("sun.awt.erasebackgroundonresize", "false")

    // Explicit sRGB ints — never rely on Compose float→AWT float edge cases.
    val darkAwt = java.awt.Color(0x11, 0x11, 0x11)

    /**
     * Lightweight: only tint AWT chrome. Do **not** setSize/revalidate/repaint Skiko children
     * on every resize — that queues multi-second freezes after the user stops dragging.
     * Compose fillMaxSize + noerasebackground handle the canvas.
     */
    fun paintWindowDark(window: java.awt.Window) {
        window.background = darkAwt
        if (window is javax.swing.RootPaneContainer) {
            window.contentPane?.background = darkAwt
            window.rootPane?.background = darkAwt
            window.layeredPane?.background = darkAwt
            (window.contentPane as? JComponent)?.isOpaque = true
            window.rootPane?.isOpaque = true
            (window.layeredPane as? JComponent)?.isOpaque = true
            val glass = window.rootPane?.glassPane
            if (glass is JComponent) {
                glass.background = darkAwt
                glass.isOpaque = false
            }
        }
    }

    try {
        application {
            PlatformLog.log(LogLevel.INFO, "DesktopUI", "starting Compose UI on ${currentHostPlatform()}")
            PlatformLog.log(
                LogLevel.INFO,
                "DesktopUI",
                "skiko.renderApi=${System.getProperty("skiko.renderApi")}"
            )
            val platform = DesktopPlatform()
            Window(
                onCloseRequest = ::exitApplication,
                title = "Vaydns",
                state = rememberWindowState(size = DpSize(420.dp, 780.dp))
            ) {
                DisposableEffect(window) {
                    paintWindowDark(window)
                    window.minimumSize = Dimension(360, 560)

                    // Debounce: one light paint after resize settles (~80ms).
                    // Live drag used to call setSize+revalidate+repaint twice per event → freeze.
                    val resizeSettle = Timer(80) {
                        paintWindowDark(window)
                    }.apply {
                        isRepeats = false
                    }

                    val onResize = object : ComponentAdapter() {
                        override fun componentResized(e: ComponentEvent?) {
                            // Coalesce: restart timer; run once when user stops dragging.
                            if (resizeSettle.isRunning) resizeSettle.restart()
                            else resizeSettle.start()
                        }

                        override fun componentShown(e: ComponentEvent?) {
                            paintWindowDark(window)
                        }
                    }
                    val onWindow = object : WindowAdapter() {
                        override fun windowOpened(e: WindowEvent?) {
                            paintWindowDark(window)
                        }

                        override fun windowStateChanged(e: WindowEvent?) {
                            // Maximize/restore — single deferred paint, not a storm.
                            if (resizeSettle.isRunning) resizeSettle.restart()
                            else resizeSettle.start()
                        }
                    }
                    window.addComponentListener(onResize)
                    window.addWindowListener(onWindow)
                    window.addWindowStateListener(onWindow)
                    onDispose {
                        resizeSettle.stop()
                        window.removeComponentListener(onResize)
                        window.removeWindowListener(onWindow)
                        window.removeWindowStateListener(onWindow)
                    }
                }
                // Compose-side full-bleed dark so any frame lag still shows SlipnetBg, not white.
                Box(Modifier.fillMaxSize().background(SlipnetBg)) {
                    VaydnsApp(platform)
                }
            }
        }
    } catch (t: Throwable) {
        writeDesktopCrash("main", t)
        throw t
    }
}

private fun installDesktopCrashHandler() {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        writeDesktopCrash(thread.name, throwable)
        previous?.uncaughtException(thread, throwable)
    }
}

/** Persist desktop crashes so we can see them after a hard exit (not only Gradle EOF). */
internal fun writeDesktopCrash(thread: String, throwable: Throwable) {
    try {
        val dir = File(System.getProperty("user.home"), ".vaydns")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "desktop-crash.log")
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val block = buildString {
            appendLine()
            appendLine("==== ${Instant.now()} thread=$thread ====")
            appendLine(sw.toString().trimEnd())
            appendLine()
        }
        file.appendText(block)
        System.err.println("Desktop crash written to ${file.absolutePath}")
        System.err.print(block)
    } catch (_: Throwable) {
        throwable.printStackTrace()
    }
}
