package app.vaydns.ui.theme

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
import app.vaydns.shared.generated.resources.Res
import app.vaydns.shared.generated.resources.roboto_medium
import app.vaydns.shared.generated.resources.roboto_regular
import org.jetbrains.compose.resources.Font

// Palette aligned with Android vpn-bot miniapp / existing res/values/colors.xml
val SlipnetBg = Color(0xFF111111)
val SlipnetCard = Color(0xFF1C1C1C)
val SlipnetCardSoft = Color(0xFF242424)
val SlipnetInput = Color(0xFF181818)
val SlipnetStroke = Color(0xFF2E2E2E)
val SlipnetAccent = Color(0xFFC0392B)
val SlipnetAccentPressed = Color(0xFF9B1B1B)
val SlipnetTextPrimary = Color(0xFFE0E0E0)
val SlipnetTextSecondary = Color(0xFFB0B0B0)
val SlipnetTextMuted = Color(0xFF7A7A7A)
val SlipnetButtonTextPrimary = Color(0xFFFFFFFF)

/** Heaviest allowed weight: 500 (Medium). No Bold / SemiBold. */
val SlipnetWeightMax = FontWeight.Medium
val SlipnetWeightNormal = FontWeight.Normal

private val DarkScheme = darkColorScheme(
    primary = SlipnetAccent,
    onPrimary = SlipnetButtonTextPrimary,
    secondary = SlipnetCardSoft,
    onSecondary = SlipnetTextPrimary,
    background = SlipnetBg,
    onBackground = SlipnetTextPrimary,
    surface = SlipnetCard,
    onSurface = SlipnetTextPrimary,
    surfaceVariant = SlipnetInput,
    onSurfaceVariant = SlipnetTextSecondary,
    outline = SlipnetStroke,
    error = SlipnetAccent,
    onError = SlipnetButtonTextPrimary
)

@Composable
fun robotoFontFamily(): FontFamily = FontFamily(
    // Only Regular (400) + Medium (500). No Bold file → no faux-bold synthesis.
    Font(Res.font.roboto_regular, weight = FontWeight.Normal),
    Font(Res.font.roboto_medium, weight = FontWeight.Medium),
)

private fun vaydnsTypography(roboto: FontFamily): Typography {
    val base = TextStyle(
        fontFamily = roboto,
        color = SlipnetTextPrimary,
        fontWeight = SlipnetWeightNormal
    )
    return Typography(
        displayLarge = base.copy(fontSize = 32.sp, fontWeight = SlipnetWeightMax),
        displayMedium = base.copy(fontSize = 28.sp, fontWeight = SlipnetWeightMax),
        displaySmall = base.copy(fontSize = 24.sp, fontWeight = SlipnetWeightMax),
        headlineLarge = base.copy(fontSize = 22.sp, fontWeight = SlipnetWeightMax),
        headlineMedium = base.copy(fontSize = 20.sp, fontWeight = SlipnetWeightMax),
        headlineSmall = base.copy(fontSize = 18.sp, fontWeight = SlipnetWeightMax),
        titleLarge = base.copy(fontSize = 18.sp, fontWeight = SlipnetWeightMax),
        titleMedium = base.copy(fontSize = 16.sp, fontWeight = SlipnetWeightMax),
        titleSmall = base.copy(fontSize = 14.sp, fontWeight = SlipnetWeightMax),
        bodyLarge = base.copy(fontSize = 16.sp, fontWeight = SlipnetWeightNormal),
        bodyMedium = base.copy(fontSize = 14.sp, fontWeight = SlipnetWeightNormal),
        bodySmall = base.copy(fontSize = 12.sp, fontWeight = SlipnetWeightNormal),
        labelLarge = base.copy(fontSize = 14.sp, fontWeight = SlipnetWeightNormal),
        labelMedium = base.copy(fontSize = 12.sp, fontWeight = SlipnetWeightNormal),
        labelSmall = base.copy(fontSize = 11.sp, fontWeight = SlipnetWeightNormal),
    )
}

@Composable
fun VaydnsTheme(content: @Composable () -> Unit) {
    val roboto = robotoFontFamily()
    val typography = remember(roboto) { vaydnsTypography(roboto) }
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
