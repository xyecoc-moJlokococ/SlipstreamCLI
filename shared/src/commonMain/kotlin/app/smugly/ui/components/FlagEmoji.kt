package app.smugly.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Regional Indicator Symbols (U+1F1E6..U+1F1FF) pair → ISO 3166-1 alpha-2.
 *
 * Windows does not paint these as flag pictures in most fonts (including Segoe UI Emoji and
 * our bundled Roboto) — it shows the two letters instead. We split the leading flag out of a
 * profile name and render a real flag image on desktop; Android/iOS keep the emoji glyph.
 */
object FlagEmoji {
    private const val RI_A = 0x1F1E6
    private const val RI_Z = 0x1F1FF

    data class Split(val iso2: String?, val text: String)

    fun splitLeading(name: String): Split {
        val cps = codePoints(name)
        if (cps.size < 2) return Split(null, name)
        val a = cps[0]
        val b = cps[1]
        if (a !in RI_A..RI_Z || b !in RI_A..RI_Z) return Split(null, name)
        val iso = buildString {
            append(('A'.code + (a - RI_A)).toChar())
            append(('A'.code + (b - RI_A)).toChar())
        }
        val rest = name.substring(charIndexAfterCodePoints(name, 2)).trimStart()
        return Split(iso, rest.ifBlank { name })
    }

    fun isoToEmoji(iso2: String): String {
        if (iso2.length != 2) return ""
        val a = iso2[0].uppercaseChar()
        val b = iso2[1].uppercaseChar()
        if (a !in 'A'..'Z' || b !in 'A'..'Z') return ""
        return codePointToString(RI_A + (a - 'A')) + codePointToString(RI_A + (b - 'A'))
    }

    private fun codePoints(s: String): List<Int> {
        val out = ArrayList<Int>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                val hi = c.code - 0xD800
                val lo = s[i + 1].code - 0xDC00
                out.add(0x10000 + (hi shl 10) + lo)
                i += 2
            } else {
                out.add(c.code)
                i++
            }
        }
        return out
    }

    private fun charIndexAfterCodePoints(s: String, count: Int): Int {
        var seen = 0
        var i = 0
        while (i < s.length && seen < count) {
            val c = s[i]
            if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                i += 2
            } else {
                i++
            }
            seen++
        }
        return i
    }

    private fun codePointToString(cp: Int): String {
        if (cp <= 0xFFFF) return cp.toChar().toString()
        val x = cp - 0x10000
        val hi = ((x shr 10) + 0xD800).toChar()
        val lo = ((x and 0x3FF) + 0xDC00).toChar()
        return charArrayOf(hi, lo).concatToString()
    }
}

/** Platform flag picture (or emoji glyph where that works). */
@Composable
expect fun CountryFlag(
    iso2: String,
    modifier: Modifier = Modifier
)

/**
 * Profile title: leading flag emoji becomes a real flag image on desktop, text stays clean.
 */
@Composable
fun ProfileNameText(
    name: String,
    color: Color,
    fontSize: TextUnit = 16.sp,
    fontWeight: FontWeight = FontWeight.Normal,
    maxLines: Int = 1,
    modifier: Modifier = Modifier
) {
    val split = remember(name) { FlagEmoji.splitLeading(name) }
    if (split.iso2 == null) {
        Text(
            text = name,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier
        )
    } else {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CountryFlag(
                iso2 = split.iso2,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(width = 22.dp, height = 16.dp)
            )
            Text(
                text = split.text,
                color = color,
                fontSize = fontSize,
                fontWeight = fontWeight,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
