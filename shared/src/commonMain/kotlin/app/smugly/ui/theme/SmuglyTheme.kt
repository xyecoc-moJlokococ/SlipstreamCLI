package app.smugly.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.smugly.shared.generated.resources.Res
import app.smugly.shared.generated.resources.roboto_medium
import app.smugly.shared.generated.resources.roboto_regular
import org.jetbrains.compose.resources.Font

// Palette aligned with Android vpn-bot miniapp / existing res/values/colors.xml
val SmuglyBg = Color(0xFF111111)
val SmuglyCard = Color(0xFF1C1C1C)
val SmuglyCardSoft = Color(0xFF242424)
val SmuglyInput = Color(0xFF181818)
val SmuglyStroke = Color(0xFF2E2E2E)
val SmuglyAccent = Color(0xFFC0392B)
val SmuglyAccentPressed = Color(0xFF9B1B1B)
val SmuglyTextPrimary = Color(0xFFE0E0E0)
val SmuglyTextSecondary = Color(0xFFB0B0B0)
val SmuglyTextMuted = Color(0xFF7A7A7A)
val SmuglyButtonTextPrimary = Color(0xFFFFFFFF)

/** Heaviest allowed weight: 500 (Medium). No Bold / SemiBold. */
val SmuglyWeightMax = FontWeight.Medium
val SmuglyWeightNormal = FontWeight.Normal

private val DarkScheme = darkColorScheme(
    primary = SmuglyAccent,
    onPrimary = SmuglyButtonTextPrimary,
    secondary = SmuglyCardSoft,
    onSecondary = SmuglyTextPrimary,
    background = SmuglyBg,
    onBackground = SmuglyTextPrimary,
    surface = SmuglyCard,
    onSurface = SmuglyTextPrimary,
    surfaceVariant = SmuglyInput,
    onSurfaceVariant = SmuglyTextSecondary,
    outline = SmuglyStroke,
    error = SmuglyAccent,
    onError = SmuglyButtonTextPrimary
)

@Composable
fun robotoFontFamily(): FontFamily = FontFamily(
    // Only Regular (400) + Medium (500). No Bold file → no faux-bold synthesis.
    Font(Res.font.roboto_regular, weight = FontWeight.Normal),
    Font(Res.font.roboto_medium, weight = FontWeight.Medium),
)

private fun smuglyTypography(roboto: FontFamily): Typography {
    val base = TextStyle(
        fontFamily = roboto,
        color = SmuglyTextPrimary,
        fontWeight = SmuglyWeightNormal
    )
    return Typography(
        displayLarge = base.copy(fontSize = 32.sp, fontWeight = SmuglyWeightMax),
        displayMedium = base.copy(fontSize = 28.sp, fontWeight = SmuglyWeightMax),
        displaySmall = base.copy(fontSize = 24.sp, fontWeight = SmuglyWeightMax),
        headlineLarge = base.copy(fontSize = 22.sp, fontWeight = SmuglyWeightMax),
        headlineMedium = base.copy(fontSize = 20.sp, fontWeight = SmuglyWeightMax),
        headlineSmall = base.copy(fontSize = 18.sp, fontWeight = SmuglyWeightMax),
        titleLarge = base.copy(fontSize = 18.sp, fontWeight = SmuglyWeightMax),
        titleMedium = base.copy(fontSize = 16.sp, fontWeight = SmuglyWeightMax),
        titleSmall = base.copy(fontSize = 14.sp, fontWeight = SmuglyWeightMax),
        bodyLarge = base.copy(fontSize = 16.sp, fontWeight = SmuglyWeightNormal),
        bodyMedium = base.copy(fontSize = 14.sp, fontWeight = SmuglyWeightNormal),
        bodySmall = base.copy(fontSize = 12.sp, fontWeight = SmuglyWeightNormal),
        labelLarge = base.copy(fontSize = 14.sp, fontWeight = SmuglyWeightNormal),
        labelMedium = base.copy(fontSize = 12.sp, fontWeight = SmuglyWeightNormal),
        labelSmall = base.copy(fontSize = 11.sp, fontWeight = SmuglyWeightNormal),
    )
}

@Composable
fun SmuglyTheme(content: @Composable () -> Unit) {
    val roboto = robotoFontFamily()
    val typography = remember(roboto) { smuglyTypography(roboto) }
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = typography
    ) {
        // Default Text / BasicTextField pick up Roboto via LocalTextStyle.
        CompositionLocalProvider(
            androidx.compose.material3.LocalTextStyle provides typography.bodyLarge
        ) {
            content()
        }
    }
}
