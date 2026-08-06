package app.smugly.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.FilterNone
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import app.smugly.ui.theme.SmuglyAccent
import app.smugly.ui.theme.SmuglyBg
import app.smugly.ui.theme.SmuglyCardSoft
import app.smugly.ui.theme.SmuglyTextMuted
import app.smugly.ui.theme.SmuglyTextSecondary
import java.awt.Cursor
import java.awt.MouseInfo
import java.awt.Window

/**
 * The window's own title bar, drawn by us.
 *
 * The frame is undecorated, so nothing here comes from Windows: no light strip above the app, no
 * white flash while the system animates a frame Compose has already torn down, and no dependency on
 * DWM accepting a dark-mode attribute. What the OS caption did for free is reproduced here —
 * dragging, double-click to maximise, the three buttons — except Aero Snap by dragging to a screen
 * edge, which needs the caption itself.
 */
@Composable
fun FrameWindowScope.WindowChrome(
    title: String,
    maximized: Boolean,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onClose: () -> Unit
) {
    // Capture AWT window once: pointerInput keys on [maximized] only, so the gesture body must not
    // close over recomposing Compose state beyond that.
    val awtWindow = window
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(BarHeight)
            .background(SmuglyBg)
            // Drag and double-click are one gesture stream. Compose's WindowDraggableArea plus a
            // separate tap detector does not work: the inner detector consumes the press first and
            // the window then refuses to move at all.
            //
            // Gesture logic lives in a top-level suspend fun (not a deeply nested lambda) so the
            // class file is `WindowChromeKt` / a shallow synthetic — nested `$1$1$1` classes were
            // the ones that blew up with NoClassDefFoundError after jar/CDS churn on mouse-move.
            .pointerInput(maximized) {
                titleBarDragGesture(
                    awtWindow = awtWindow,
                    maximized = maximized,
                    onToggleMaximize = onToggleMaximize
                )
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = SmuglyTextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.padding(start = 12.dp).weight(1f)
        )
        ChromeButton(
            icon = {
                Icon(Icons.Rounded.Remove, null, tint = SmuglyTextSecondary, modifier = Modifier.size(16.dp))
            },
            onClick = onMinimize
        )
        ChromeButton(
            icon = {
                Icon(
                    if (maximized) Icons.Rounded.FilterNone else Icons.Rounded.CropSquare,
                    null,
                    tint = SmuglyTextSecondary,
                    // FilterNone is two overlapping squares and reads heavier; trim it a little.
                    modifier = Modifier.size(if (maximized) 13.dp else 15.dp)
                )
            },
            onClick = onToggleMaximize
        )
        ChromeButton(
            icon = {
                Icon(Icons.Rounded.Close, null, tint = SmuglyTextSecondary, modifier = Modifier.size(16.dp))
            },
            hoverColor = SmuglyAccent,
            onClick = onClose
        )
    }
}

/**
 * Title-bar drag + double-click maximise. Kept as a file-level suspend function so the compiler
 * emits a stable method instead of a chain of anonymous inner classes that AppCDS/jar swaps
 * sometimes fail to resolve mid-gesture (`WindowChromeKt$WindowChrome$1$1$1`).
 */
private suspend fun PointerInputScope.titleBarDragGesture(
    awtWindow: Window,
    maximized: Boolean,
    onToggleMaximize: () -> Unit
) {
    var lastClickAt = 0L
    awaitEachGesture {
        val down = awaitFirstDown()
        // Screen coordinates, not deltas inside this component: the component moves with the
        // window, so its own coordinates chase the pointer and the drag feeds on itself.
        val startPointer = MouseInfo.getPointerInfo()?.location
        val startX = awtWindow.x
        val startY = awtWindow.y
        var moved = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            val now = MouseInfo.getPointerInfo()?.location ?: continue
            if (startPointer == null) continue
            val dx = now.x - startPointer.x
            val dy = now.y - startPointer.y
            if (!moved && (kotlin.math.abs(dx) > 2 || kotlin.math.abs(dy) > 2)) moved = true
            if (moved) {
                change.consume()
                // A maximized window has no position to nudge; the drag is taken as a request to
                // restore, which is what the system caption does too.
                if (maximized) {
                    onToggleMaximize()
                    break
                }
                awtWindow.setLocation(startX + dx, startY + dy)
            }
        }
        if (!moved) {
            val at = System.currentTimeMillis()
            if (at - lastClickAt < DoubleClickMs) {
                lastClickAt = 0
                onToggleMaximize()
            } else {
                lastClickAt = at
            }
        }
    }
}

/** Square, borderless, tinted on hover — the same language as the app's own menus. */
@Composable
private fun ChromeButton(
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    hoverColor: Color = SmuglyCardSoft
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .width(46.dp)
            .fillMaxHeight()
            .background(if (hovered) hoverColor else Color.Transparent)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center
    ) {
        icon()
    }
}

/** Height of the custom title bar. */
val BarHeight = 34.dp

private const val DoubleClickMs = 400L
