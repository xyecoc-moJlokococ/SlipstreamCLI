package app.smugly.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp

/** iOS paints regional-indicator flags via Apple Color Emoji. */
@Composable
actual fun CountryFlag(iso2: String, modifier: Modifier) {
    Text(
        text = FlagEmoji.isoToEmoji(iso2),
        fontSize = 16.sp,
        modifier = modifier,
        maxLines = 1
    )
}
