package app.smugly.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle

/**
 * Lightweight JSON syntax colors (v2rayNG-style on a dark surface).
 * Applied via [VisualTransformation] so the underlying string stays plain text.
 */
object JsonHighlightColors {
    val key = Color(0xFF9CCC65)       // keys
    val string = Color(0xFFCE9178)    // string values
    val number = Color(0xFF82AAFF)    // numbers
    val keyword = Color(0xFFFFB74D)   // true / false / null
    val punctuation = Color(0xFFB0B0B0) // {}[]:,
    val plain = Color(0xFFE0E0E0)
}

/**
 * Tokenize JSON-ish text into colored spans. Tolerant of incomplete / invalid input while typing.
 */
fun highlightJson(src: String): AnnotatedString = buildAnnotatedString {
    if (src.isEmpty()) return@buildAnnotatedString
    var i = 0
    val n = src.length
    while (i < n) {
        val c = src[i]
        when {
            c == '"' -> {
                val start = i
                i++ // opening quote
                while (i < n) {
                    when (src[i]) {
                        '\\' -> i = (i + 2).coerceAtMost(n)
                        '"' -> {
                            i++
                            break
                        }
                        else -> i++
                    }
                }
                // Look ahead (skip space) for ':' → treat as key, else string value.
                var j = i
                while (j < n && src[j].isWhitespace()) j++
                val isKey = j < n && src[j] == ':'
                withStyle(
                    SpanStyle(color = if (isKey) JsonHighlightColors.key else JsonHighlightColors.string)
                ) {
                    append(src, start, i)
                }
            }
            c.isDigit() || (c == '-' && i + 1 < n && src[i + 1].isDigit()) -> {
                val start = i
                if (c == '-') i++
                while (i < n && (src[i].isDigit() || src[i] == '.' || src[i] == 'e' || src[i] == 'E' ||
                        src[i] == '+' || src[i] == '-')
                ) {
                    i++
                }
                withStyle(SpanStyle(color = JsonHighlightColors.number)) {
                    append(src, start, i)
                }
            }
            c == 't' && src.startsWith("true", i) -> {
                withStyle(SpanStyle(color = JsonHighlightColors.keyword)) { append("true") }
                i += 4
            }
            c == 'f' && src.startsWith("false", i) -> {
                withStyle(SpanStyle(color = JsonHighlightColors.keyword)) { append("false") }
                i += 5
            }
            c == 'n' && src.startsWith("null", i) -> {
                withStyle(SpanStyle(color = JsonHighlightColors.keyword)) { append("null") }
                i += 4
            }
            c == '{' || c == '}' || c == '[' || c == ']' || c == ':' || c == ',' -> {
                withStyle(SpanStyle(color = JsonHighlightColors.punctuation)) {
                    append(c)
                }
                i++
            }
            else -> {
                // whitespace / unknown
                withStyle(SpanStyle(color = JsonHighlightColors.plain)) {
                    append(c)
                }
                i++
            }
        }
    }
}

/**
 * Identity-mapping transformation so caret / selection stay on the raw JSON string.
 * Caches the last result — rebuilding spans every recomposition was resetting the text
 * field's internal scroll on focus/click.
 */
object JsonSyntaxHighlightTransformation : VisualTransformation {
    @Volatile private var cachedText: String? = null
    @Volatile private var cachedAnnotated: AnnotatedString? = null

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val annotated = if (raw == cachedText) {
            cachedAnnotated ?: highlightJson(raw).also { cachedAnnotated = it }
        } else {
            highlightJson(raw).also {
                cachedText = raw
                cachedAnnotated = it
            }
        }
        return TransformedText(annotated, OffsetMapping.Identity)
    }
}
