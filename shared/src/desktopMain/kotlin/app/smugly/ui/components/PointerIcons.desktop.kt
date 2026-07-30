package app.smugly.ui.components

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor

actual val PointerIconGrab: PointerIcon =
    PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))

/** MOVE_CURSOR reads as a grab/drag cursor on Windows (4-way move hand). */
actual val PointerIconGrabbing: PointerIcon =
    PointerIcon(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR))
