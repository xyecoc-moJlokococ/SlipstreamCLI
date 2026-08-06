package app.smugly.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import java.awt.Cursor
import java.awt.Window
import kotlin.math.roundToInt

/**
 * Edge and corner grips for an undecorated window.
 *
 * Windows only resizes windows it draws a frame for, and this one has none — so the frame's job is
 * done here instead. Bounds are pushed straight onto the AWT window rather than through
 * [WindowState]: the state is in dp and rounds on every event, which turns a slow drag into a
 * visible stagger, while `setBounds` takes the device pixels the mouse actually moved.
 *
 * A maximized window has nothing to grab — resizing it would leave it "maximized" at a size that is
 * not the screen.
 */
@Composable
fun WindowResizeHandles(window: Window, enabled: Boolean) {
    if (!enabled) return
    Box(Modifier.fillMaxSize()) {
        // Corners first in the layout order so they sit above the edges they overlap.
        Grip(Modifier.align(Alignment.TopStart).size(CORNER), Cursor.NW_RESIZE_CURSOR, window, left = true, top = true)
        Grip(Modifier.align(Alignment.TopEnd).size(CORNER), Cursor.NE_RESIZE_CURSOR, window, right = true, top = true)
        Grip(Modifier.align(Alignment.BottomStart).size(CORNER), Cursor.SW_RESIZE_CURSOR, window, left = true, bottom = true)
        Grip(Modifier.align(Alignment.BottomEnd).size(CORNER), Cursor.SE_RESIZE_CURSOR, window, right = true, bottom = true)

        Grip(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(EDGE), Cursor.N_RESIZE_CURSOR, window, top = true)
        Grip(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(EDGE), Cursor.S_RESIZE_CURSOR, window, bottom = true)
        Grip(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(EDGE), Cursor.W_RESIZE_CURSOR, window, left = true)
        Grip(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(EDGE), Cursor.E_RESIZE_CURSOR, window, right = true)
    }
}

@Composable
private fun Grip(
    modifier: Modifier,
    cursor: Int,
    window: Window,
    left: Boolean = false,
    top: Boolean = false,
    right: Boolean = false,
    bottom: Boolean = false
) {
    Box(
        modifier
            .pointerHoverIcon(PointerIcon(Cursor(cursor)))
            .pointerInput(left, top, right, bottom) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    // The window moves under the pointer while it is dragged, so the deltas are
                    // taken from the pointer's own change rather than from its position inside a
                    // component that is being resized out from under it.
                    //
                    // Deltas are accumulated and applied at most once per frame. A gaming mouse
                    // reports several hundred times a second, and resizing the native window on
                    // every one of them makes it blink: each `setBounds` costs a native resize plus
                    // a re-render, and the queue never catches up.
                    var pendingX = 0
                    var pendingY = 0
                    var lastApply = 0L
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        val delta = change.positionChange()
                        change.consume()
                        pendingX += delta.x.roundToInt()
                        pendingY += delta.y.roundToInt()
                        if (pendingX == 0 && pendingY == 0) continue
                        val now = System.nanoTime()
                        if (now - lastApply < FRAME_NS) continue
                        lastApply = now
                        resize(window, pendingX, pendingY, left, top, right, bottom)
                        pendingX = 0
                        pendingY = 0
                    }
                    // Whatever the last frame did not carry still belongs to the user's gesture.
                    if (pendingX != 0 || pendingY != 0) {
                        resize(window, pendingX, pendingY, left, top, right, bottom)
                    }
                }
            }
    )
}

private fun resize(
    window: Window,
    dx: Int,
    dy: Int,
    left: Boolean,
    top: Boolean,
    right: Boolean,
    bottom: Boolean
) {
    val min = window.minimumSize
    var x = window.x
    var y = window.y
    var w = window.width
    var h = window.height

    if (left) {
        // Clamp against the minimum before moving the origin, or the window would keep sliding
        // right once it can no longer get any narrower.
        val next = (w - dx).coerceAtLeast(min.width)
        x += w - next
        w = next
    }
    if (right) w = (w + dx).coerceAtLeast(min.width)
    if (top) {
        val next = (h - dy).coerceAtLeast(min.height)
        y += h - next
        h = next
    }
    if (bottom) h = (h + dy).coerceAtLeast(min.height)

    if (x != window.x || y != window.y || w != window.width || h != window.height) {
        window.setBounds(x, y, w, h)
        // setBounds grows the HWND immediately; Skiko's heavyweight surface lags under DIRECT3D
        // and leaves a white/transparent strip (desktop showing through) until the next full
        // layout. Match content-pane children to the new size and repaint once per frame
        // (this function is already frame-throttled by the grip handler).
        syncSurfaceAfterResize(window)
    }
}

/** #111111 — same as SmuglyBg; used for GDI fill so resize strips match the app. */
private val RESIZE_FILL = java.awt.Color(0x11, 0x11, 0x11)

/**
 * After a manual [Window.setBounds] from the grips:
 * 1) GDI-fill the whole client with dark (gap between HWND and swapchain — SO 63096226),
 * 2) stretch content-pane children / Skiko layers to the new size,
 * 3) Skiko `changeSize` + `redrawImmediately` (MS: ResizeBuffers then full Present same frame),
 * 4) repaint.
 * Already frame-throttled by the grip handler (~60 Hz).
 */
private fun syncSurfaceAfterResize(window: Window) {
    runCatching {
        // 1) Instant dark fill — covers the HWND/swapchain gap while Skiko catches up.
        //    Only works without WS_EX_NOREDIRECTIONBITMAP (see WindowsResizeArtifacts).
        if (window.width > 0 && window.height > 0) {
            window.graphics?.let { g ->
                try {
                    g.color = RESIZE_FILL
                    g.fillRect(0, 0, window.width, window.height)
                } finally {
                    g.dispose()
                }
            }
        }
        window.background = RESIZE_FILL
        if (window is javax.swing.RootPaneContainer) {
            val cp = window.contentPane
            if (cp != null && window.width > 0 && window.height > 0) {
                cp.background = RESIZE_FILL
                if (cp is javax.swing.JComponent) cp.isOpaque = true
                // Undecorated: client area == window size.
                cp.setBounds(0, 0, window.width, window.height)
                // Every direct child of the content pane must cover it (Compose panel, etc.).
                for (child in cp.components) {
                    child.background = RESIZE_FILL
                    if (child is javax.swing.JComponent) child.isOpaque = true
                    child.setBounds(0, 0, cp.width, cp.height)
                }
                cp.doLayout()
                expandSkiko(cp)
            }
        }
        // 3) ResizeBuffers + Present in one shot (learn.microsoft D2D resize guidance).
        WindowsResizeArtifacts.forceSkikoPresent(window)
        // GDI again after layout (covers any remaining strip before the next vsync).
        if (window.width > 0 && window.height > 0) {
            window.graphics?.let { g ->
                try {
                    g.color = RESIZE_FILL
                    g.fillRect(0, 0, window.width, window.height)
                } finally {
                    g.dispose()
                }
            }
        }
        window.repaint()
    }
}

private fun expandSkiko(component: java.awt.Component) {
    if (component !is java.awt.Container) return
    val w = component.width
    val h = component.height
    if (w <= 0 || h <= 0) return
    for (child in component.components) {
        val n = child.javaClass.name
        val match =
            n.contains("Skia", ignoreCase = true) ||
                n.contains("Compose", ignoreCase = true) ||
                n.contains("Canvas", ignoreCase = true) ||
                n.contains("HardwareLayer", ignoreCase = true) ||
                n.contains("Skiko", ignoreCase = true)
        if (match) {
            child.setBounds(0, 0, w, h)
            child.background = RESIZE_FILL
            if (child is javax.swing.JComponent) child.isOpaque = true
            child.repaint()
        }
        expandSkiko(child)
    }
}

/** Grab area: wide enough to hit without hunting, narrow enough not to eat the content's clicks. */
private val EDGE = 5.dp
private val CORNER = 12.dp

/** One 60 Hz frame: the fastest a native resize is worth doing. */
private const val FRAME_NS = 16_000_000L
