package app.smugly.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.graphics.Color

/**
 * TOML colors, reusing the JSON palette so the two config editors look like one thing.
 * Comments carry most of the meaning in these configs (the shipped examples explain every
 * knob), so they get their own muted colour rather than being dimmed into invisibility.
 */
private object TomlColors {
    val comment = Color(0xFF7A8A6B)
    val table = Color(0xFFFFB74D)
    val key = JsonHighlightColors.key
    val string = JsonHighlightColors.string
    val number = JsonHighlightColors.number
    val keyword = JsonHighlightColors.keyword
    val punctuation = JsonHighlightColors.punctuation
    val plain = JsonHighlightColors.plain
}

/**
 * Tokenize TOML line by line. Deliberately forgiving: this runs on every keystroke over a
 * half-typed config, so anything unrecognised is emitted as plain text rather than
 * throwing off the colours for the rest of the file.
 */
fun highlightToml(src: String): AnnotatedString = buildAnnotatedString {
    if (src.isEmpty()) return@buildAnnotatedString

    for ((index, rawLine) in src.split("\n").withIndex()) {
        if (index > 0) withStyle(SpanStyle(color = TomlColors.plain)) { append("\n") }
        val trimmed = rawLine.trimStart()
        val indent = rawLine.length - trimmed.length

        if (indent > 0) {
            withStyle(SpanStyle(color = TomlColors.plain)) { append(rawLine.substring(0, indent)) }
        }
        when {
            trimmed.isEmpty() -> {}
            trimmed.startsWith("#") ->
                withStyle(SpanStyle(color = TomlColors.comment)) { append(trimmed) }
            trimmed.startsWith("[") ->
                withStyle(SpanStyle(color = TomlColors.table)) { append(trimmed) }
            else -> appendAssignment(trimmed)
        }
    }
}

/** `key = value  # trailing comment` */
private fun androidx.compose.ui.text.AnnotatedString.Builder.appendAssignment(line: String) {
    val eq = line.indexOf('=')
    if (eq <= 0) {
        withStyle(SpanStyle(color = TomlColors.plain)) { append(line) }
        return
    }
    withStyle(SpanStyle(color = TomlColors.key)) { append(line.substring(0, eq)) }
    withStyle(SpanStyle(color = TomlColors.punctuation)) { append("=") }

    val rest = line.substring(eq + 1)
    // A '#' inside a quoted value is part of the value, not a comment.
    var inString = false
    var cut = -1
    for (i in rest.indices) {
        val ch = rest[i]
        if (ch == '"' && (i == 0 || rest[i - 1] != '\\')) inString = !inString
        if (ch == '#' && !inString) {
            cut = i
            break
        }
    }
    val value = if (cut >= 0) rest.substring(0, cut) else rest
    withStyle(SpanStyle(color = valueColor(value.trim()))) { append(value) }
    if (cut >= 0) {
        withStyle(SpanStyle(color = TomlColors.comment)) { append(rest.substring(cut)) }
    }
}

private fun valueColor(v: String): Color = when {
    v.startsWith("\"") || v.startsWith("'") -> TomlColors.string
    v == "true" || v == "false" -> TomlColors.keyword
    v.isNotEmpty() && (v[0].isDigit() || v[0] == '-' || v[0] == '+') -> TomlColors.number
    else -> TomlColors.plain
}

/**
 * Identity-mapping transformation so caret / selection stay on the raw text. Caches the
 * last result — rebuilding spans on every recomposition resets the field's scroll.
 */
object TomlSyntaxHighlightTransformation : VisualTransformation {
    @Volatile private var cachedText: String? = null
    @Volatile private var cachedAnnotated: AnnotatedString? = null

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val annotated = if (raw == cachedText) {
            cachedAnnotated ?: highlightToml(raw).also { cachedAnnotated = it }
        } else {
            highlightToml(raw).also {
                cachedText = raw
                cachedAnnotated = it
            }
        }
        return TransformedText(annotated, OffsetMapping.Identity)
    }
}
