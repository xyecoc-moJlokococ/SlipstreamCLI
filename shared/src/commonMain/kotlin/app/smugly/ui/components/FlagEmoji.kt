package app.smugly.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.smugly.currentHostPlatform
import app.smugly.isDesktop
import app.smugly.ui.theme.SmuglyAccent
import app.smugly.ui.theme.SmuglyInput
import app.smugly.ui.theme.SmuglyStroke
import app.smugly.ui.theme.SmuglyTextPrimary

/**
 * Regional Indicator Symbols (U+1F1E6..U+1F1FF) pair → ISO 3166-1 alpha-2.
 *
 * Windows paints these as letter pairs ("ES") instead of flag pictures. Cards use [CountryFlag]
 * images; the profile-name field keeps the emoji *in the string* (deletable) and overlays the
 * PNG on desktop so the user sees a flag, not "ES".
 */
object FlagEmoji {
    private const val RI_A = 0x1F1E6
    private const val RI_Z = 0x1F1FF

    data class Split(val iso2: String?, val text: String)

    /** One flag emoji in [text], UTF-16 indices [start, end). */
    data class Span(val start: Int, val end: Int, val iso2: String)

    fun splitLeading(name: String): Split {
        val cps = codePoints(name)
        if (cps.size < 2) return Split(null, name)
        val a = cps[0]
        val b = cps[1]
        if (a !in RI_A..RI_Z || b !in RI_A..RI_Z) return Split(null, name)
        val iso = isoFromRis(a, b)
        val rest = name.substring(charIndexAfterCodePoints(name, 2)).trimStart()
        // Empty rest is fine (name is flag-only) — never fall back to the full string.
        return Split(iso, rest)
    }

    fun findAll(text: String): List<Span> {
        val out = ArrayList<Span>()
        var i = 0
        while (i < text.length) {
            val cp1 = codePointAt(text, i) ?: break
            val len1 = charCount(cp1)
            if (cp1 in RI_A..RI_Z && i + len1 < text.length) {
                val cp2 = codePointAt(text, i + len1)
                if (cp2 != null && cp2 in RI_A..RI_Z) {
                    val len2 = charCount(cp2)
                    out.add(Span(i, i + len1 + len2, isoFromRis(cp1, cp2)))
                    i += len1 + len2
                    continue
                }
            }
            i += len1
        }
        return out
    }

    fun isoToEmoji(iso2: String): String {
        if (iso2.length != 2) return ""
        val a = iso2[0].uppercaseChar()
        val b = iso2[1].uppercaseChar()
        if (a !in 'A'..'Z' || b !in 'A'..'Z') return ""
        return codePointToString(RI_A + (a - 'A')) + codePointToString(RI_A + (b - 'A'))
    }

    /**
     * Hide RI pairs (Windows "ES") so a flag PNG can be painted over them.
     * Length stays 1:1 for the caret. A slightly larger transparent run + letter-spacing
     * reserves room for a **rectangular** flag and a gap before the following text.
     */
    object HideFlagsTransformation : VisualTransformation {
        override fun filter(text: AnnotatedString): TransformedText {
            val spans = findAll(text.text)
            if (spans.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
            val built = buildAnnotatedString {
                var i = 0
                for (span in spans) {
                    if (i < span.start) append(text.text, i, span.start)
                    withStyle(
                        SpanStyle(
                            color = Color.Transparent,
                            // Keep body size so line metrics match Cyrillic; letterSpacing
                            // alone reserves width for a 4:3 flag + gap (larger fontSize
                            // inflated the line and made the flag sit low).
                            fontSize = 15.sp,
                            letterSpacing = 4.sp
                        )
                    ) {
                        append(text.text, span.start, span.end)
                    }
                    i = span.end
                }
                if (i < text.text.length) append(text.text, i, text.text.length)
            }
            return TransformedText(built, OffsetMapping.Identity)
        }
    }

    private fun isoFromRis(a: Int, b: Int): String = buildString {
        append(('A'.code + (a - RI_A)).toChar())
        append(('A'.code + (b - RI_A)).toChar())
    }

    private fun codePointAt(s: String, i: Int): Int? {
        if (i < 0 || i >= s.length) return null
        val c = s[i]
        return if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
            0x10000 + ((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00)
        } else {
            c.code
        }
    }

    private fun charCount(cp: Int): Int = if (cp >= 0x10000) 2 else 1

    private fun codePoints(s: String): List<Int> {
        val out = ArrayList<Int>(s.length)
        var i = 0
        while (i < s.length) {
            val cp = codePointAt(s, i) ?: break
            out.add(cp)
            i += charCount(cp)
        }
        return out
    }

    private fun charIndexAfterCodePoints(s: String, count: Int): Int {
        var seen = 0
        var i = 0
        while (i < s.length && seen < count) {
            val cp = codePointAt(s, i) ?: break
            i += charCount(cp)
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
                // 4:3 rectangle (not a square chip).
                modifier = Modifier
                    .padding(end = if (split.text.isEmpty()) 0.dp else 8.dp)
                    .size(width = 24.dp, height = 18.dp)
            )
            if (split.text.isNotEmpty()) {
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
}

/**
 * Profile name editor.
 *
 * On desktop Windows, flag emoji paint as "ES"/"RU", so the leading flag is shown as a
 * rectangular [CountryFlag] **inside** the same field chrome, in a [Row] with
 * [Alignment.CenterVertically] (pixel-perfect with the letters — overlay math kept fighting
 * baseline/ascent). The emoji remains in the stored string; Backspace at the start of the
 * text (or clearing the field and Backspace again) removes it.
 *
 * Android/iOS: plain text field — system emoji already draws real flags.
 */
@Composable
fun ProfileNameField(
    name: String,
    onNameChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!currentHostPlatform().isDesktop()) {
        SmuglyTextField(value = name, onValueChange = onNameChange, modifier = modifier)
        return
    }

    val split = remember(name) { FlagEmoji.splitLeading(name) }
    val body = split.text
    val iso = split.iso2
    var field by remember(name) {
        mutableStateOf(TextFieldValue(body, TextRange(body.length)))
    }
    // Keep caret when only the flag prefix changed externally; reset text from [name].
    LaunchedEffect(name) {
        val nextBody = FlagEmoji.splitLeading(name).text
        if (nextBody != field.text) {
            field = TextFieldValue(nextBody, TextRange(nextBody.length.coerceAtMost(nextBody.length)))
        }
    }

    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(SmuglyInput)
            .border(1.dp, SmuglyStroke, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (iso != null) {
                CountryFlag(
                    iso2 = iso,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(width = 24.dp, height = 18.dp)
                )
            }
            BasicTextField(
                value = field,
                onValueChange = { next ->
                    field = next
                    // Prefer a flag the user pasted into the field.
                    val pasted = FlagEmoji.splitLeading(next.text)
                    if (pasted.iso2 != null) {
                        onNameChange(next.text.trim())
                        field = TextFieldValue(
                            pasted.text,
                            TextRange(pasted.text.length)
                        )
                    } else if (iso != null) {
                        val emoji = FlagEmoji.isoToEmoji(iso)
                        val t = next.text
                        onNameChange(if (t.isEmpty()) emoji else "$emoji $t")
                    } else {
                        onNameChange(next.text)
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .onPreviewKeyEvent { e ->
                        // Cursor at start + Backspace → drop the leading flag (it's outside the
                        // text buffer so it would otherwise be undeletable).
                        if (iso != null &&
                            e.type == KeyEventType.KeyDown &&
                            e.key == Key.Backspace &&
                            field.selection.collapsed &&
                            field.selection.start == 0
                        ) {
                            onNameChange(field.text)
                            true
                        } else {
                            false
                        }
                    },
                singleLine = true,
                textStyle = TextStyle(
                    color = SmuglyTextPrimary,
                    fontSize = 15.sp
                ),
                cursorBrush = SolidColor(SmuglyAccent)
            )
        }
    }
}
