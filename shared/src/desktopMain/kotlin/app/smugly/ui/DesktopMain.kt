package app.smugly.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.launch
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
    // Class-load Settings / editor / Material bits on a background thread while the window comes
    // up, so the first drawer → Settings click is not a multi-hundred-ms ClassLoader stall.
    DesktopUiWarmup.start()

    // Software rendering by default — a deliberate memory-over-smoothness choice, measured on
    // Windows at DIRECT3D 223 MB / SOFTWARE 133 MB working set. It was briefly DIRECT3D while the
    // window itself animated its geometry; that animation is gone (see WindowAnimation), and the
    // GPU path also left a much larger white gap while a live resize outran the swapchain.
    //
    // Switch without rebuilding:
    //   set JAVA_TOOL_OPTIONS=-Dskiko.renderApi=DIRECT3D
    if (System.getProperty("skiko.renderApi").isNullOrBlank()) {
        System.setProperty("skiko.renderApi", "SOFTWARE")
    }
    System.setProperty("skiko.vsync.enabled", "true")
    System.setProperty("sun.java2d.d3d", "true")
    System.setProperty("sun.java2d.noddraw", "false")
    // Let AWT erase newly exposed regions during a live resize — *because* every background in
    // this window is dark (see paintWindowDark / tintTreeDark below). These were the other way
    // round for a long time, to stop the white flooding, which treated the symptom: with erasing
    // off nothing paints the strip the window has just grown into, so it shows whatever the
    // compositor had there — which is how a whole white block ended up next to the UI while
    // dragging an edge. Erasing with a dark brush fills it with the app's own colour instead.
    System.setProperty("sun.awt.noerasebackground", "false")
    System.setProperty("sun.awt.erasebackgroundonresize", "true")

    // Explicit sRGB ints — never rely on Compose float→AWT float edge cases.
    val darkAwt = java.awt.Color(0x11, 0x11, 0x11)
    // Anything Swing builds for us later starts dark too, instead of inheriting the default grey /
    // white and flashing it the first time it is exposed.
    javax.swing.UIManager.put("Panel.background", darkAwt)
    javax.swing.UIManager.put("control", darkAwt)

    /**
     * Lightweight: only tint AWT chrome. Do **not** setSize/revalidate/repaint Skiko children
     * on every resize — that queues multi-second freezes after the user stops dragging.
     * Compose fillMaxSize + noerasebackground handle the canvas.
     */
    /**
     * The rendering surface is a heavyweight AWT component with its own native window, and Windows
     * fills whatever a resize newly exposes with **that component's** background brush before Skia
     * gets to draw. Tinting only the frame and its panes left the canvas itself default-white,
     * which is the white edge that flashed along the side being dragged.
     *
     * Walking the whole tree is deliberate: the surface sits several layers down inside Skiko and
     * its class is an implementation detail. Setting a colour is cheap — no layout, no repaint —
     * so this stays safe to call from the resize-settled timer.
     */
    fun tintTreeDark(component: java.awt.Component) {
        runCatching { component.background = darkAwt }
        if (component is java.awt.Container) {
            for (child in component.components) tintTreeDark(child)
        }
    }

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
        tintTreeDark(window)
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
            // Set once the frame exists; the close handler needs it and runs before the content.
            val frame = remember { arrayOfNulls<java.awt.Window>(1) }
            val windowState = rememberWindowState(size = DpSize(420.dp, 780.dp))
            // Maximise is ours, not `WindowPlacement.Maximized`: an undecorated window maximised by
            // the toolkit takes the whole monitor and sits **over the taskbar**, because the OS only
            // trims a maximised window to the work area for frames it draws itself.
            var maximized by remember { mutableStateOf(false) }
            val restoreBounds = remember { arrayOfNulls<java.awt.Rectangle>(1) }
            // The window animations run on Compose's frame clock, so they need its scope.
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            fun toggleMaximize(w: java.awt.Window) {
                if (maximized) {
                    val back = restoreBounds[0] ?: return
                    // Flipped before the animation, not after: the button's icon and the resize
                    // grips belong to where the window is going, not where it is leaving.
                    maximized = false
                    WindowAnimation.applyPlacement(windowState, w, back)
                } else {
                    restoreBounds[0] = w.bounds
                    maximized = true
                    WindowAnimation.applyPlacement(windowState, w, workArea(w))
                }
            }
            fun closeWindow() {
                scope.launch {
                    WindowAnimation.playClose(frame[0])
                    exitApplication()
                }
            }
            Window(
                // No system chrome at all: the title bar is ours (see WindowChrome). That removes
                // the light strip above the app, the white flash while Windows animated a frame
                // Compose had already torn down, and any reliance on DWM honouring a dark-mode
                // attribute. Resizing comes back via WindowResizeHandles.
                undecorated = true,
                state = windowState,
                onCloseRequest = { closeWindow() },
                title = "Smugly",
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
                    frame[0] = window
                    // Invisible until the window is on screen, so the opening fade has something
                    // to fade from.
                    WindowAnimation.prepareForOpen(window)
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
                            // Now that the window is on screen and at its final size, that size is
                            // what the opening animation grows into.
                            scope.launch { WindowAnimation.playOpen(window) }
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
                    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                        WindowChrome(
                            title = "Smugly",
                            maximized = maximized,
                            onMinimize = { windowState.isMinimized = true },
                            onToggleMaximize = { toggleMaximize(window) },
                            onClose = { closeWindow() }
                        )
                        Box(Modifier.weight(1f)) {
                            SmuglyApp(platform, shortcuts)
                        }
                    }
                    // Above the content: the grips are a few pixels at the window's edge and must
                    // win over whatever the app draws underneath them.
                    WindowResizeHandles(window, enabled = !maximized)
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
/**
 * The monitor's usable area — the screen minus the taskbar and anything else docked to an edge.
 *
 * This is what "maximised" has to mean for an undecorated window: the toolkit's own maximise takes
 * the whole monitor, because Windows only trims a maximised window to the work area when it draws
 * the frame itself.
 */
private fun workArea(window: java.awt.Window): java.awt.Rectangle {
    val gc = window.graphicsConfiguration ?: return window.bounds
    val screen = gc.bounds
    val insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc)
    return java.awt.Rectangle(
        screen.x + insets.left,
        screen.y + insets.top,
        screen.width - insets.left - insets.right,
        screen.height - insets.top - insets.bottom
    )
}

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
