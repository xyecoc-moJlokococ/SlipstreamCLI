package app.smugly.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.smugly.ui.theme.SmuglyCardSoft
import app.smugly.ui.theme.SmuglyTextSecondary
import java.util.concurrent.ConcurrentHashMap

/**
 * Windows fonts do not draw flag emojis as pictures (only as "ES"/"RU" letter pairs).
 * Flags are bundled at `resources/flags/{iso}.png` (w40 PNGs) and loaded from the classpath —
 * no network, no user-cache.
 */
@Composable
actual fun CountryFlag(iso2: String, modifier: Modifier) {
    val code = remember(iso2) { iso2.lowercase().filter { it in 'a'..'z' }.take(2) }
    val bitmap = remember(code) { BundledFlags.load(code) }

    val shape = RoundedCornerShape(2.dp)
    Box(
        modifier = modifier.clip(shape),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = code.uppercase(),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(SmuglyCardSoft),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = code.uppercase(),
                    color = SmuglyTextSecondary,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

private object BundledFlags {
    private val mem = ConcurrentHashMap<String, ImageBitmap>()

    fun load(code: String): ImageBitmap? {
        if (code.length != 2) return null
        mem[code]?.let { return it }
        val stream = BundledFlags::class.java.classLoader
            ?.getResourceAsStream("flags/$code.png")
            ?: return null
        val bmp = runCatching {
            stream.buffered().use { loadImageBitmap(it) }
        }.getOrNull() ?: return null
        mem[code] = bmp
        return bmp
    }
}
