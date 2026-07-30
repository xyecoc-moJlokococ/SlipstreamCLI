package app.smugly.subscription

/**
 * Parsing for subscription responses. Pure functions over strings — no I/O, so the whole format can
 * be unit-tested.
 *
 * The de-facto format panels use (Happ, Marzban, Remnawave, 3x-ui …):
 *
 *  - the **body** is a newline-separated list of `vless://` / `ss://` / … links, usually wrapped in
 *    base64 as a whole;
 *  - metadata arrives in **HTTP headers** (`subscription-userinfo`, `profile-title`,
 *    `profile-update-interval`, `profile-web-page-url`, `support-url`, `announce`);
 *  - Happ additionally allows the same keys inline in the body as `#key: value` lines, for panels
 *    that cannot set headers.
 *
 * Text values may be sent as `base64:<payload>` and titles are sometimes quoted, so both are
 * normalised here rather than at each call site.
 */
object SubscriptionParser {

    /** Everything a fetch can tell us, independent of where it came from (headers or body). */
    data class Metadata(
        val title: String = "",
        /** Hours, as advertised. 0 when the panel did not say. */
        val updateIntervalHours: Long = 0,
        val info: SubscriptionInfo = SubscriptionInfo()
    )

    data class Parsed(
        val metadata: Metadata,
        /** Config links in the order the panel listed them. */
        val links: List<String>
    )

    private val LINK_SCHEMES = listOf(
        "vless://", "vmess://", "trojan://", "ss://", "ssr://",
        "socks://", "hy2://", "hysteria://", "hysteria2://", "tuic://", "wireguard://",
        // This app's own protocols, so a panel can hand out DNS/S3 tunnels too.
        "slipstream://", "s3fu://", "xray://"
    )

    /**
     * @param headers response headers, keys matched case-insensitively.
     * @param body raw response body.
     */
    fun parse(body: String, headers: Map<String, String> = emptyMap()): Parsed {
        val decodedBody = decodeBodyIfBase64(body)
        val inline = parseInlineMetadata(decodedBody)
        val fromHeaders = parseHeaders(headers)
        // Headers win: they are the primary channel, inline keys are the fallback for panels
        // that cannot set them.
        val metadata = merge(primary = fromHeaders, fallback = inline)
        return Parsed(metadata, extractLinks(decodedBody))
    }

    /**
     * Key lookup is deliberately loose: `profile-title`, `Profile_Title` and `profileTitle` are all
     * the same key. Panels are not consistent and the set of fields they send grows over time, so
     * matching on a normalised form costs nothing and avoids chasing every new spelling.
     */
    fun normalizeKey(key: String): String =
        key.filter { it.isLetterOrDigit() }.lowercase()

    fun parseHeaders(headers: Map<String, String>): Metadata {
        val byKey = headers.entries.associate { (k, v) -> normalizeKey(k) to v }
        return buildMetadata { key -> byKey[normalizeKey(key)] }
    }

    /** Metadata from an arbitrary key/value bag — JSON fields, query params, anything. */
    fun metadataFrom(values: Map<String, String>): Metadata {
        val byKey = values.entries.associate { (k, v) -> normalizeKey(k) to v }
        return buildMetadata { key -> byKey[normalizeKey(key)] }
    }

    /** Combine two metadata sets; [primary] wins field by field. */
    fun mergeMetadata(primary: Metadata, fallback: Metadata): Metadata = merge(primary, fallback)

    /** Happ-style `#key: value` lines carried inside the body. */
    fun parseInlineMetadata(body: String): Metadata {
        val values = HashMap<String, String>()
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith("#")) continue
            val content = line.removePrefix("#").trim()
            val colon = content.indexOf(':')
            if (colon <= 0) continue
            val key = content.substring(0, colon).trim().lowercase()
            val value = content.substring(colon + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty() && key !in values) values[key] = value
        }
        return buildMetadata { key -> values[key] }
    }

    private inline fun buildMetadata(get: (String) -> String?): Metadata {
        val userInfo = parseUserInfo(get("subscription-userinfo").orEmpty())
        return Metadata(
            title = decodeText(get("profile-title").orEmpty()),
            updateIntervalHours = get("profile-update-interval")?.trim()?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
            info = userInfo.copy(
                webPageUrl = get("profile-web-page-url").orEmpty().trim(),
                supportUrl = get("support-url").orEmpty().trim(),
                announce = decodeText(get("announce").orEmpty())
            )
        )
    }

    private fun merge(primary: Metadata, fallback: Metadata) = Metadata(
        title = primary.title.ifBlank { fallback.title },
        updateIntervalHours = if (primary.updateIntervalHours > 0) {
            primary.updateIntervalHours
        } else {
            fallback.updateIntervalHours
        },
        info = SubscriptionInfo(
            uploadBytes = maxOf(primary.info.uploadBytes, fallback.info.uploadBytes),
            downloadBytes = maxOf(primary.info.downloadBytes, fallback.info.downloadBytes),
            totalBytes = if (primary.info.totalBytes > 0) primary.info.totalBytes else fallback.info.totalBytes,
            expiresAtSeconds = if (primary.info.expiresAtSeconds > 0) {
                primary.info.expiresAtSeconds
            } else {
                fallback.info.expiresAtSeconds
            },
            webPageUrl = primary.info.webPageUrl.ifBlank { fallback.info.webPageUrl },
            supportUrl = primary.info.supportUrl.ifBlank { fallback.info.supportUrl },
            announce = primary.info.announce.ifBlank { fallback.info.announce }
        )
    )

    /** `upload=0; download=2153701362; total=0; expire=1790951622` */
    fun parseUserInfo(raw: String): SubscriptionInfo {
        if (raw.isBlank()) return SubscriptionInfo()
        var upload = 0L
        var download = 0L
        var total = 0L
        var expire = 0L
        for (part in raw.split(';')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val key = part.substring(0, eq).trim().lowercase()
            val value = part.substring(eq + 1).trim().toLongOrNull() ?: continue
            when (key) {
                "upload" -> upload = value
                "download" -> download = value
                "total" -> total = value
                "expire" -> expire = value
            }
        }
        return SubscriptionInfo(
            uploadBytes = upload,
            downloadBytes = download,
            totalBytes = total,
            expiresAtSeconds = expire
        )
    }

    /** Strips surrounding quotes and an optional `base64:` prefix. */
    fun decodeText(raw: String): String {
        var value = raw.trim()
        if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            value = value.substring(1, value.length - 1)
        }
        if (value.startsWith("base64:", ignoreCase = true)) {
            val payload = value.substring("base64:".length)
            return decodeBase64ToString(payload) ?: payload
        }
        return value
    }

    /** Config links, ignoring metadata/comment lines. */
    fun extractLinks(body: String): List<String> =
        body.lineSequence()
            .map { it.trim() }
            .filter { line -> line.isNotEmpty() && LINK_SCHEMES.any { line.startsWith(it, ignoreCase = true) } }
            .distinct()
            .toList()

    /**
     * Most panels base64 the whole body. Decode only when the result actually looks like a config
     * list — otherwise a plain-text body that happens to decode to garbage would be destroyed.
     */
    fun decodeBodyIfBase64(body: String): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return body
        if (extractLinks(trimmed).isNotEmpty()) return trimmed
        val decoded = decodeBase64ToString(trimmed) ?: return body
        return if (extractLinks(decoded).isNotEmpty() || decoded.contains('#')) decoded else body
    }

    /**
     * Lenient base64 decode: accepts the URL-safe alphabet, missing padding and embedded
     * whitespace/newlines, all of which real subscription payloads contain. Returns null when the
     * input is not decodable or is not valid UTF-8 text.
     */
    fun decodeBase64ToString(input: String): String? {
        val bytes = decodeBase64(input) ?: return null
        if (bytes.isEmpty()) return null
        val text = bytes.decodeToString()
        // decodeToString substitutes U+FFFD for invalid sequences; that means it was not text.
        if (text.contains('�')) return null
        return text
    }

    private fun decodeBase64(input: String): ByteArray? {
        val out = ArrayList<Byte>(input.length * 3 / 4 + 3)
        var buffer = 0
        var bits = 0
        for (ch in input) {
            if (ch == '=' || ch == '\n' || ch == '\r' || ch == ' ' || ch == '\t') continue
            val value = base64Value(ch)
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer shr bits) and 0xFF).toByte())
            }
        }
        if (out.isEmpty()) return null
        return out.toByteArray()
    }

    private fun base64Value(ch: Char): Int = when (ch) {
        in 'A'..'Z' -> ch - 'A'
        in 'a'..'z' -> ch - 'a' + 26
        in '0'..'9' -> ch - '0' + 52
        '+', '-' -> 62   // '-' is the URL-safe form
        '/', '_' -> 63   // '_' is the URL-safe form
        else -> -1
    }
}
