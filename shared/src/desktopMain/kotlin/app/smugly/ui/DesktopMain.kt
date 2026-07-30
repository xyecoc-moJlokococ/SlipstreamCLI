package app.smugly.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.smugly.currentHostPlatform
import app.smugly.platform.LogLevel
import app.smugly.platform.PlatformLog
import app.smugly.ui.theme.SmuglyBg
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
 * Compose Multiplatform desktop entry — same Smugly UI as Android/iOS shared code.
 */
fun main() {
    installDesktopCrashHandler()

    // Software rendering by default — a deliberate memory-over-smoothness choice.
    //
    // Measured on Windows: creating a GPU device costs ~90 MB of working set and ~145 MB of
    // committed memory (DIRECT3D 223/279 MB, OPENGL 234/253 MB, SOFTWARE 133/135 MB), which
    // dwarfs the JVM's own ~85 MB for a UI this simple. The cost is animation smoothness.
    //
    // Set the property to switch back without rebuilding, e.g.
    //   set JAVA_TOOL_OPTIONS=-Dskiko.renderApi=DIRECT3D
    // (DIRECT3D resizes more reliably than ANGLE on some Windows GPUs; macOS wants METAL.)
    if (System.getProperty("skiko.renderApi").isNullOrBlank()) {
        System.setProperty("skiko.renderApi", "SOFTWARE")
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
            val shortcuts = remember { AppShortcuts() }
            Window(
                onCloseRequest = ::exitApplication,
                title = "Smugly",
                state = rememberWindowState(size = DpSize(420.dp, 780.dp)),
                // Ctrl+V pastes a profile from the clipboard. This is onKeyEvent, not
                // onPreviewKeyEvent, so a focused text field gets the key first and its own paste
                // still works — the shortcut only fires when nothing is being typed into.
                onKeyEvent = { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        event.isCtrlPressed &&
                        event.key == Key.V
                    ) {
                        shortcuts.importFromClipboard?.invoke()
                        true
                    } else {
                        false
                    }
                }
            ) {
                DisposableEffect(window) {
                    paintWindowDark(window)
                    fitWindowToScreen(window)

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
                            // Clamp here, not only in DisposableEffect: at composition time the
                            // frame has not been given its final size yet, so anything decided
                            // then gets overwritten when the window is realised.
                            fitWindowToScreen(window)
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
                // Compose-side full-bleed dark so any frame lag still shows SmuglyBg, not white.
                Box(Modifier.fillMaxSize().background(SmuglyBg)) {
                    SmuglyApp(platform, shortcuts)
                }
            }
        }
    } catch (t: Throwable) {
        writeDesktopCrash("main", t)
        throw t
    }
}

/**
 * Keep the window inside the screen's usable area.
 *
 * The preferred size (420x780 dp) is taller than the work area on small or heavily scaled displays,
 * and AWT will happily place a window that runs under the taskbar and off the bottom of the screen.
 * Clamping in AWT coordinates avoids any dp/DPI conversion: `graphicsConfiguration.bounds` minus
 * `getScreenInsets` is already the taskbar-free area in the same units as `window.size`.
 */
private fun fitWindowToScreen(window: java.awt.Window) {
    val gc = window.graphicsConfiguration ?: return
    val screen = gc.bounds
    val insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc)
    val maxWidth = screen.width - insets.left - insets.right
    val maxHeight = screen.height - insets.top - insets.bottom
    if (maxWidth <= 0 || maxHeight <= 0) return

    // The floor must fit too — a 560-tall minimum on a 500-tall work area would re-break it.
    window.minimumSize = Dimension(minOf(360, maxWidth), minOf(560, maxHeight))

    val width = minOf(window.width, maxWidth)
    val height = minOf(window.height, maxHeight)
    if (width != window.width || height != window.height) {
        window.setSize(width, height)
    }
    // Re-centre in the work area so a clamped window never starts partly off-screen.
    window.setLocation(
        screen.x + insets.left + (maxWidth - width) / 2,
        screen.y + insets.top + (maxHeight - height) / 2
    )
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
        val dir = File(System.getProperty("user.home"), ".smugly")
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
