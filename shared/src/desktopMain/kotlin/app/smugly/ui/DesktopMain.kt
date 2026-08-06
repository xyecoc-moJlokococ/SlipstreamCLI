package app.smugly.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import app.smugly.ui.theme.SmuglyAccent
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
    // Skiko defaults clear-to-white when the D3D surface grows; set dark clear before any layer
    // is created (skiko#1042 / CMP-7919). Combined with WindowsResizeArtifacts on the HWND.
    WindowsResizeArtifacts.installSkikoClearColor()

    // Render API: **GPU by default** so the UI tracks the display (user's RTX / 100 Hz panel —
    // SOFTWARE was pegging ~24 FPS and felt dead). Measured trade-off on this machine:
    //   SOFTWARE  ~130 MB WS, ~20–40 FPS feel under load
    //   DIRECT3D  ~200–230 MB WS, display-refresh pacing with vsync
    // Window geometry animation is gone (see WindowAnimation); white resize strips are handled
    // by dark class brush + Skiko changeSize/Present on resize (see WindowsResizeArtifacts).
    //
    // Override without rebuilding:
    //   set JAVA_TOOL_OPTIONS=-Dskiko.renderApi=SOFTWARE   (lower RAM)
    //   set JAVA_TOOL_OPTIONS=-Dskiko.renderApi=OPENGL
    if (System.getProperty("skiko.renderApi").isNullOrBlank()) {
        val os = System.getProperty("os.name").orEmpty()
        System.setProperty(
            "skiko.renderApi",
            when {
                os.startsWith("Windows", ignoreCase = true) -> "DIRECT3D"
                os.contains("Mac", ignoreCase = true) -> "METAL"
                else -> "OPENGL"
            }
        )
    }
    // Lock presentation to the monitor refresh (100 Hz here → up to 100 FPS, no free tearing).
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

    /**
     * Instant GDI fill of the whole client area. Under DIRECT3D the swapchain lags behind
     * HWND growth; Windows (or a white component brush) shows through as a white L-strip.
     * Filling with [darkAwt] first means the strip is #111 until Skiko redraws — same as the
     * app background, so the flash is invisible.
     */
    fun fillClientDarkGdi(window: java.awt.Window) {
        if (window.width <= 0 || window.height <= 0) return
        runCatching {
            val g = window.graphics ?: return
            try {
                g.color = darkAwt
                g.fillRect(0, 0, window.width, window.height)
            } finally {
                g.dispose()
            }
        }
    }

    /**
     * Stretch content-pane children and Skiko/HardwareLayer surfaces to fill their parent.
     * Name-matching alone missed some wrappers; we also force-fill any direct child of the
     * content pane / layered pane.
     */
    fun fillSurfaces(component: java.awt.Component) {
        if (component !is java.awt.Container) return
        val w = component.width
        val h = component.height
        if (w <= 0 || h <= 0) return
        val parentName = component.javaClass.name
        val parentIsShell =
            component is javax.swing.JRootPane ||
                parentName.contains("JLayeredPane", ignoreCase = true) ||
                (component.parent is javax.swing.JRootPane && component is javax.swing.JPanel)
        for (child in component.components) {
            runCatching { child.background = darkAwt }
            if (child is JComponent) runCatching { child.isOpaque = true }
            val n = child.javaClass.name
            val isSkiko = n.contains("Skia", ignoreCase = true) ||
                n.contains("Compose", ignoreCase = true) ||
                n.contains("Canvas", ignoreCase = true) ||
                n.contains("HardwareLayer", ignoreCase = true) ||
                n.contains("Skiko", ignoreCase = true)
            if (isSkiko || parentIsShell) {
                if (child.x != 0 || child.y != 0 || child.width != w || child.height != h) {
                    child.setBounds(0, 0, w, h)
                }
            }
            fillSurfaces(child)
        }
    }

    fun paintWindowDark(window: java.awt.Window, relayout: Boolean = false) {
        // GDI first — covers white strips before anything else runs (needs dark class brush,
        // no WS_EX_NOREDIRECTIONBITMAP — see WindowsResizeArtifacts / Raph Levien).
        fillClientDarkGdi(window)
        window.background = darkAwt
        if (window is javax.swing.RootPaneContainer) {
            window.contentPane?.background = darkAwt
            window.rootPane?.background = darkAwt
            window.layeredPane?.background = darkAwt
            (window.contentPane as? JComponent)?.isOpaque = true
            window.rootPane?.isOpaque = true
            (window.layeredPane as? JComponent)?.isOpaque = true
            // Undecorated: content pane must match client size.
            val cp = window.contentPane
            if (cp != null && window.width > 0 && window.height > 0) {
                if (cp.width != window.width || cp.height != window.height) {
                    cp.setBounds(0, 0, window.width, window.height)
                }
            }
            val glass = window.rootPane?.glassPane
            if (glass is JComponent) {
                glass.background = darkAwt
                glass.isOpaque = false
            }
        }
        tintTreeDark(window)
        if (relayout) {
            runCatching { window.doLayout() }
            window.components.forEach { c -> runCatching { c.doLayout() } }
            fillSurfaces(window)
            runCatching { window.revalidate() }
        } else {
            fillSurfaces(window)
        }
        // MS D2D resize path: ResizeBuffers + full Present same frame (via Skiko reflection).
        WindowsResizeArtifacts.forceSkikoPresent(window)
        // Second GDI pass after layout — catches any strip still left before the next vsync.
        fillClientDarkGdi(window)
        runCatching { window.repaint() }
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
            // Discord-style boot: hold a centered spinner for a couple of frames while Skiko /
            // first composition settle, then mount SmuglyApp. Longer cold work is covered by the
            // pre-GUI WarmupCds splash when AppCDS is rebuilt after compile.
            var bootReady by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                withFrameNanos { }
                withFrameNanos { }
                bootReady = true
            }
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
                    // HWND exists after addNotify — apply brush/DWM as soon as we can.
                    WindowsResizeArtifacts.applyTo(window)
                    fitWindowToScreen(window)

                    // Live resize: only dark-fill + skiko bounds (cheap). Full revalidate once
                    // the drag settles — that used to freeze for seconds when fired every event.
                    val resizeSettle = Timer(60) {
                        paintWindowDark(window, relayout = true)
                    }.apply {
                        isRepeats = false
                    }

                    val onResize = object : ComponentAdapter() {
                        override fun componentResized(e: ComponentEvent?) {
                            // During drag: keep newly exposed strips dark and the Skiko surface
                            // matched to the content pane so DIRECT3D does not flash white/desktop.
                            paintWindowDark(window, relayout = false)
                            if (resizeSettle.isRunning) resizeSettle.restart()
                            else resizeSettle.start()
                        }

                        override fun componentShown(e: ComponentEvent?) {
                            paintWindowDark(window, relayout = true)
                        }
                    }
                    val onWindow = object : WindowAdapter() {
                        override fun windowOpened(e: WindowEvent?) {
                            paintWindowDark(window, relayout = true)
                            // DWM/Win32: dark class brush on all HWNDs incl. late Skiko layers
                            // (SO 63096226; no NOREDIRECTIONBITMAP — Raph / GDI gap fill).
                            WindowsResizeArtifacts.applyTo(window)
                            WindowsResizeArtifacts.forceSkikoPresent(window)
                            // Clamp here, not only in DisposableEffect: at composition time the
                            // frame has not been given its final size yet, so anything decided
                            // then gets overwritten when the window is realised.
                            fitWindowToScreen(window)
                            // Now that the window is on screen and at its final size, that size is
                            // what the opening animation grows into.
                            scope.launch { WindowAnimation.playOpen(window) }
                        }

                        override fun windowStateChanged(e: WindowEvent?) {
                            paintWindowDark(window, relayout = false)
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
                    if (!bootReady) {
                        // Same as Android LoadingOverlay: accent ring only, no wordmark / caption.
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(34.dp),
                                color = SmuglyAccent,
                                strokeWidth = 3.dp
                            )
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
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
                        // Above the content: the grips are a few pixels at the window's edge and
                        // must win over whatever the app draws underneath them.
                        WindowResizeHandles(window, enabled = !maximized)
                    }
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
        // Compose's DesktopCoroutineExceptionHandler shows only throwable.message in a tiny
        // JOptionPane — for NoClassDefFoundError that is just a mangled class name
        // ("WindowChromeKt$WindowChrome$1$1$1"). Prefer the real cause line when present.
        val friendly = buildString {
            append(throwable.javaClass.simpleName)
            val msg = throwable.message?.takeIf { it.isNotBlank() }
            val cause = throwable.cause?.let { c ->
                c.javaClass.simpleName + (c.message?.let { ": $it" } ?: "")
            }
            when {
                msg != null && cause != null -> append(": $msg\n($cause)")
                msg != null -> append(": $msg")
                cause != null -> append(": $cause")
            }
            append("\n\nDetails: %USERPROFILE%\\.smugly\\desktop-crash.log".replace("%USERPROFILE%", System.getProperty("user.home")))
        }
        // If Compose already painted a dialog, this is a second one only for non-Compose threads.
        // Still worth writing the log (above); avoid double popups when previous is Compose's.
        if (previous == null) {
            runCatching {
                javax.swing.JOptionPane.showMessageDialog(
                    null, friendly, "Smugly error", javax.swing.JOptionPane.ERROR_MESSAGE
                )
            }
        }
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
