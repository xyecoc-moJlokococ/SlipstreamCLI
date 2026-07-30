package app.smugly.ui.components

import androidx.compose.ui.input.pointer.PointerIcon

/** Open-hand / grab affordance for reorderable surfaces (desktop). */
expect val PointerIconGrab: PointerIcon

/** Closed-hand / grabbing while a drag is active (desktop). */
expect val PointerIconGrabbing: PointerIcon
