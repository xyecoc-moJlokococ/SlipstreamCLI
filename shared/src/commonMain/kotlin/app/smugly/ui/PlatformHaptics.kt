package app.smugly.ui

import androidx.compose.runtime.Composable

/**
 * One light tick, for the "a neighbour just moved out of the way" moment of a reorder drag.
 *
 * Not [androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove], which is the closest
 * Compose gives us in common code: plenty of phones (a Nothing Phone 2 among them) have no vibration
 * pattern configured for it and the request is dropped as `ignored_unsupported` — silently nothing.
 * Android therefore asks for a clock tick, which lands on the HAL's own TICK effect.
 *
 * Returns a callback rather than doing the work, so a drag can fire it outside composition.
 * No-op where the platform has no vibrator.
 */
@Composable
expect fun rememberTickHaptic(): () -> Unit
