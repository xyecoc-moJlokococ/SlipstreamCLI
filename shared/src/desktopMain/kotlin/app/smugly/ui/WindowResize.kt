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
    }
}

/** Grab area: wide enough to hit without hunting, narrow enough not to eat the content's clicks. */
private val EDGE = 5.dp
private val CORNER = 12.dp

/** One 60 Hz frame: the fastest a native resize is worth doing. */
private const val FRAME_NS = 16_000_000L
