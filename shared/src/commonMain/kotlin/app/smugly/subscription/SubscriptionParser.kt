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

    /** One config link plus the category it was listed under ("" = none). */
    data class CategorizedLink(val uri: String, val categoryId: String = "")

    data class Parsed(
        val metadata: Metadata,
        /** Config links in the order the panel listed them, each tagged with its category. */
        val entries: List<CategorizedLink>,
        /** Categories the body declared, in declaration order. */
        val categories: List<SubscriptionCategory> = emptyList()
    ) {
        /** Just the URIs — most callers do not care which group a link came from. */
        val links: List<String> get() = entries.map { it.uri }
    }

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
        val grouped = extractCategorized(decodedBody)
        return Parsed(metadata, grouped.links, grouped.categories)
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
    fun extractLinks(body: String): List<String> = extractCategorized(body).links.map { it.uri }

    /** True when [text] is a single config URI of a scheme some client can actually run. */
    fun isConfigLink(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.isNotEmpty() && LINK_SCHEMES.any { trimmed.startsWith(it, ignoreCase = true) }
    }

    /** Links plus the categories declared around them. */
    data class CategorizedBody(
        val links: List<CategorizedLink>,
        val categories: List<SubscriptionCategory>
    )

    /** `#category:` and friends; the panel may spell them with any punctuation (see [normalizeKey]). */
    private val CATEGORY_KEYS = setOf("category", "group", "folder", "section")
    private val CATEGORY_DESCRIPTION_KEYS = setOf(
        "categorydescription", "groupdescription", "folderdescription", "sectiondescription",
        "categorydesc", "groupdesc", "description", "desc"
    )
    /**
     * Marks which category should start open (the rest collapse). Value is either the category
     * name, or a truthy flag (`true` / `1` / `yes`) for the category currently open.
     */
    private val DEFAULT_OPEN_KEYS = setOf(
        "defaultcategory", "defaultopen", "categorydefault", "opendefault",
        "defaultexpanded", "maincategory"
    )

    /**
     * Split a link list into groups.
     *
     * A `#category: Name` line opens a category and everything below it belongs to that category
     * until the next one; `#category-description: …` (or plain `#description:`) attaches a blurb to
     * the category currently open. A `#category:` with no value closes the group, so a panel can
     * emit ungrouped servers after grouped ones. Both values accept `base64:…`, since panels that
     * cannot be trusted with UTF-8 headers usually cannot be trusted with UTF-8 bodies either.
     *
     * An optional `#default-category: Name` (or `#default-open: true` while a category is open)
     * marks the group the panel wants expanded by default; without it every category starts open.
     *
     * A body with no such lines parses exactly as it always did: every link, no categories.
     */
    fun extractCategorized(body: String): CategorizedBody {
        val links = ArrayList<CategorizedLink>()
        val seen = HashSet<String>()
        val categories = LinkedHashMap<String, SubscriptionCategory>()
        var current = ""
        var defaultOpenName: String? = null
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                val content = line.removePrefix("#").trim()
                val colon = content.indexOf(':')
                if (colon <= 0) continue
                val key = normalizeKey(content.substring(0, colon))
                val value = decodeText(content.substring(colon + 1))
                when (key) {
                    in CATEGORY_KEYS -> {
                        current = if (value.isBlank()) "" else {
                            val id = subscriptionCategoryId(value)
                            categories.getOrPut(id) { SubscriptionCategory(id, value) }
                            id
                        }
                    }
                    in CATEGORY_DESCRIPTION_KEYS -> {
                        // A description with no category open has nothing to describe.
                        categories[current]?.let { categories[current] = it.copy(description = value) }
                    }
                    in DEFAULT_OPEN_KEYS -> {
                        // First marker wins — panels should only name one main group.
                        if (defaultOpenName == null) {
                            defaultOpenName = resolveDefaultOpenValue(value, current, categories)
                        }
                    }
                }
                continue
            }
            if (LINK_SCHEMES.none { line.startsWith(it, ignoreCase = true) }) continue
            // Same link twice is the same server; the first listing (and its category) wins.
            if (!seen.add(line)) continue
            links.add(CategorizedLink(line, current))
        }
        val list = markDefaultOpen(categories.values.toList(), defaultOpenName)
        return CategorizedBody(links, list)
    }

    /** `true`/`1`/`yes` → the open category; otherwise the value is a category name. */
    private fun resolveDefaultOpenValue(
        value: String,
        currentId: String,
        categories: Map<String, SubscriptionCategory>
    ): String? {
        if (value.isBlank()) return null
        if (isTruthy(value)) return categories[currentId]?.name
        return value
    }

    private fun isTruthy(value: String): Boolean {
        val v = value.trim().lowercase()
        return v == "true" || v == "1" || v == "yes" || v == "on"
    }

    /**
     * Attach [SubscriptionCategory.defaultOpen] to the category whose name (or id) matches
     * [defaultOpenName]. Unknown names are ignored so a typo cannot invent a ghost group.
     */
    internal fun markDefaultOpen(
        categories: List<SubscriptionCategory>,
        defaultOpenName: String?
    ): List<SubscriptionCategory> {
        if (defaultOpenName.isNullOrBlank() || categories.isEmpty()) return categories
        val wantedId = subscriptionCategoryId(defaultOpenName)
        val matchId = categories.firstOrNull {
            it.id == defaultOpenName ||
                it.id == wantedId ||
                it.name.equals(defaultOpenName, ignoreCase = true)
        }?.id ?: return categories
        return categories.map { if (it.id == matchId) it.copy(defaultOpen = true) else it }
    }

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
