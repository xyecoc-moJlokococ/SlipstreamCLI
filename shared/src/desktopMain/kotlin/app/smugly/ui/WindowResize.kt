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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import java.awt.Cursor
import java.awt.Window
import kotlin.math.roundToInt

/**
 * Edge / corner grips for an undecorated window.
 *
 * Live drag only calls [Window.setBounds] (device pixels). Anything else — sizing SkiaLayer,
 * GDI fill, changeSize, needRedraw — either no-ops or causes **extra** `renderImmediately`
 * (skiko 0.144: every SkiaLayer.reshape on DIRECT3D presents immediately). Stacking those
 * presents while the HWND is still changing is the remaining whole-window flicker.
 *
 * AWT lays out the content pane once per setBounds; Skiko's own reshape does a single Present.
 * [WindowState] is written **once** when the gesture ends so Compose does not fight mid-drag.
 */
@Composable
fun WindowResizeHandles(window: Window, windowState: WindowState, enabled: Boolean) {
    if (!enabled) return
    Box(Modifier.fillMaxSize()) {
        Grip(Modifier.align(Alignment.TopStart).size(CORNER), Cursor.NW_RESIZE_CURSOR, window, windowState, left = true, top = true)
        Grip(Modifier.align(Alignment.TopEnd).size(CORNER), Cursor.NE_RESIZE_CURSOR, window, windowState, right = true, top = true)
        Grip(Modifier.align(Alignment.BottomStart).size(CORNER), Cursor.SW_RESIZE_CURSOR, window, windowState, left = true, bottom = true)
        Grip(Modifier.align(Alignment.BottomEnd).size(CORNER), Cursor.SE_RESIZE_CURSOR, window, windowState, right = true, bottom = true)

        Grip(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(EDGE), Cursor.N_RESIZE_CURSOR, window, windowState, top = true)
        Grip(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(EDGE), Cursor.S_RESIZE_CURSOR, window, windowState, bottom = true)
        Grip(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(EDGE), Cursor.W_RESIZE_CURSOR, window, windowState, left = true)
        Grip(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(EDGE), Cursor.E_RESIZE_CURSOR, window, windowState, right = true)
    }
}

@Composable
private fun Grip(
    modifier: Modifier,
    cursor: Int,
    window: Window,
    windowState: WindowState,
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
                    WindowsResizeArtifacts.beginGripResize()
                    var pendingX = 0
                    var pendingY = 0
                    var lastApply = 0L
                    try {
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
                            // One setBounds per display frame. Faster only queues Present lag.
                            if (now - lastApply < FRAME_NS) continue
                            lastApply = now
                            resizeWindow(window, pendingX, pendingY, left, top, right, bottom)
                            pendingX = 0
                            pendingY = 0
                        }
                        if (pendingX != 0 || pendingY != 0) {
                            resizeWindow(window, pendingX, pendingY, left, top, right, bottom)
                        }
                    } finally {
                        // Publish final geometry into Compose state once — avoids mid-drag
                        // state↔AWT feedback loops that blink the frame.
                        syncWindowStateFromAwt(windowState, window)
                        WindowsResizeArtifacts.endGripResize()
                    }
                }
            }
    )
}

private fun resizeWindow(
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
        // Single native call. AWT layout → one SkiaLayer.reshape → one renderImmediately.
        // Do not touch children / Skiko / GDI here.
        window.setBounds(x, y, w, h)
    }
}

/**
 * Write [WindowState] from the live AWT bounds so the next composition does not push an
 * older dp size back onto the window (that 1-px fight reads as flicker).
 */
fun syncWindowStateFromAwt(state: WindowState, window: Window) {
    val scale = scaleOf(window)
    if (scale <= 0f) return
    state.position = WindowPosition.Absolute((window.x / scale).dp, (window.y / scale).dp)
    state.size = DpSize((window.width / scale).dp, (window.height / scale).dp)
}

private fun scaleOf(window: Window): Float =
    (runCatching { window.graphicsConfiguration?.defaultTransform?.scaleX?.toFloat() }
        .getOrNull() ?: 1f)
        .takeIf { it > 0f } ?: 1f

private val EDGE = 5.dp
private val CORNER = 12.dp

/** Match a 100 Hz panel: ~1 setBounds per refresh. */
private const val FRAME_NS = 10_000_000L
