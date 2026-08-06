package app.smugly.ui

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import java.awt.GraphicsDevice
import java.awt.Rectangle
import java.awt.Window

/**
 * Window animations for the undecorated frame — all of them opacity, none of them geometry.
 *
 * Windows animates the frames it draws itself; ours has none, so appearing and disappearing would
 * otherwise be instantaneous. Growing the window into place was tried first, because that is the
 * shape the system uses, and it was wrong twice over: every step re-renders the whole UI at a new
 * size, which stutters, and writing geometry per frame fights Compose's own window state — the
 * window kept settling at a size neither of them had asked for. Opacity costs the compositor
 * nothing and touches no layout.
 *
 * The clock is [withFrameNanos] — Compose's own — so each step lands on the frame that draws it.
 * Every animation ends by writing its final value: one that is interrupted, or that cannot run at
 * all, must still leave the window fully opaque.
 */
object WindowAnimation {

    private const val OPEN_MS = 80f
    private const val CLOSE_MS = 60f

    /** Fade in. Called once the window is on screen. */
    suspend fun playOpen(window: Window) {
        if (!supportsTranslucency(window)) return
        fade(window, from = 0f, to = 1f, durationMs = OPEN_MS)
    }

    /** Fade out and hide. Returns once the window is gone — the caller then ends the application. */
    suspend fun playClose(window: Window?) {
        if (window == null || !window.isVisible) return
        if (supportsTranslucency(window)) {
            fade(window, from = 1f, to = 0f, durationMs = CLOSE_MS)
        }
        // Hidden before the app exits: whatever the system does with a window on its way out, it
        // will not be drawing a frame Compose has already released.
        runCatching { window.isVisible = false }
    }

    // Minimise and restore are deliberately **not** animated. The system already plays its own
    // taskbar animation for them, and fading on top of it does not replace that animation — it
    // adds to it: the window blinked coming back, and going away took as long as both together.

    /**
     * Maximise / restore: straight to [toPx] (device pixels), no animation at all.
     *
     * A button press that changes the whole window should feel immediate, and both animated
     * versions — growing, and cross-fading — read as lag rather than polish.
     */
    fun applyPlacement(state: WindowState, window: Window, toPx: Rectangle) {
        val scale = scaleOf(window)
        state.position = WindowPosition.Absolute((toPx.x / scale).dp, (toPx.y / scale).dp)
        state.size = DpSize((toPx.width / scale).dp, (toPx.height / scale).dp)
    }

    /** Start the window invisible so [playOpen] has something to fade from. */
    fun prepareForOpen(window: Window) {
        if (!supportsTranslucency(window)) return
        runCatching { window.opacity = 0f }
    }

    private suspend fun fade(window: Window, from: Float, to: Float, durationMs: Float) {
        runCatching { window.opacity = from }
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val t = ((now - start) / 1_000_000f / durationMs).coerceIn(0f, 1f)
            runCatching { window.opacity = (from + (to - from) * t).coerceIn(0f, 1f) }
            if (t >= 1f) break
        }
        runCatching { window.opacity = to.coerceIn(0f, 1f) }
    }

    /** OS scaling (125 % display → 1.25), which is what separates dp from device pixels here. */
    private fun scaleOf(window: Window): Float =
        (runCatching { window.graphicsConfiguration?.defaultTransform?.scaleX?.toFloat() }
            .getOrNull() ?: 1f)
            .takeIf { it > 0f } ?: 1f

    private fun supportsTranslucency(window: Window): Boolean = runCatching {
        window.graphicsConfiguration?.device
            ?.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.TRANSLUCENT) == true
    }.getOrDefault(false)
}
