package app.vaydns.subscription

import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns a subscription body into importable entries.
 *
 * Deliberately **format-agnostic** rather than an implementation of any one panel's contract.
 * Panels differ, add fields over time and reshape their payloads, so instead of matching a fixed
 * schema this does two tolerant things:
 *
 *  - **JSON**: walk the whole document and treat *any* object carrying a non-empty `outbounds`
 *    array as a server config, wherever it sits — top-level array, single object, or wrapped in
 *    `{"data": {"configs": [...]}}`. Unknown sibling fields are ignored, and each config is stored
 *    verbatim so nothing is lost.
 *  - **text**: fall back to scanning for `scheme://` config links ([SubscriptionParser]).
 *
 * Metadata is read from headers, from `#key: value` lines, and from scalar fields on the JSON root,
 * with loose key matching — so a panel that renames `profile-title` to `profileTitle` still works.
 */
object SubscriptionContent {

    sealed interface Entry {
        /** Display name shown in the profile list. */
        val name: String

        /** A `scheme://` config URI. */
        data class Link(val uri: String, override val name: String = "") : Entry

        /** A complete Xray config document, kept exactly as the panel sent it. */
        data class XrayJson(override val name: String, val json: String) : Entry
    }

    data class Parsed(
        val metadata: SubscriptionParser.Metadata,
        val entries: List<Entry>
    )

    /** Keys checked for a server's display name, in order of preference. */
    private val NAME_KEYS = listOf("remarks", "remark", "name", "title", "label", "tag")

    /** Guards against a pathological or hostile document. */
    private const val MAX_DEPTH = 8
    private const val MAX_ENTRIES = 500

    fun parse(body: String, headers: Map<String, String> = emptyMap()): Parsed {
        val textParse = SubscriptionParser.parse(body, headers)

        val root = parseJsonRoot(body)
        if (root != null) {
            val configs = ArrayList<JSONObject>()
            collectConfigs(root, configs, depth = 0)
            if (configs.isNotEmpty()) {
                // A JSON payload may also carry the subscription metadata alongside the servers.
                val fromJson = (root as? JSONObject)?.let { SubscriptionParser.metadataFrom(scalarFields(it)) }
                val metadata = if (fromJson != null) {
                    SubscriptionParser.mergeMetadata(primary = textParse.metadata, fallback = fromJson)
                } else {
                    textParse.metadata
                }
                return Parsed(metadata, configs.mapIndexed { i, obj -> toEntry(obj, i) })
            }
        }

        return Parsed(textParse.metadata, textParse.links.map { Entry.Link(it, nameFromLinkFragment(it)) })
    }

    private fun parseJsonRoot(body: String): Any? {
        val text = body.trim()
        return when {
            text.startsWith("[") -> runCatching { JSONArray(text) }.getOrNull()
            text.startsWith("{") -> runCatching { JSONObject(text) }.getOrNull()
            else -> null
        }
    }

    /**
     * Depth-first search for config objects. Once an object qualifies it is taken whole and not
     * descended into — its own `outbounds` entries are parts of that config, not separate servers.
     */
    private fun collectConfigs(node: Any?, out: MutableList<JSONObject>, depth: Int) {
        if (depth > MAX_DEPTH || out.size >= MAX_ENTRIES) return
        when (node) {
            is JSONArray -> for (i in 0 until node.length()) collectConfigs(node.opt(i), out, depth + 1)
            is JSONObject -> {
                if (isServerConfig(node)) {
                    out.add(node)
                    return
                }
                for (key in node.keys()) collectConfigs(node.opt(key), out, depth + 1)
            }
        }
    }

    /** An Xray config is identified by having somewhere to send traffic. */
    private fun isServerConfig(obj: JSONObject): Boolean =
        (obj.optJSONArray("outbounds")?.length() ?: 0) > 0

    private fun toEntry(obj: JSONObject, index: Int): Entry.XrayJson {
        val name = NAME_KEYS.firstNotNullOfOrNull { key -> obj.optString(key).takeIf { it.isNotBlank() } }
            ?: "Server ${index + 1}"
        return Entry.XrayJson(name = name, json = obj.toString())
    }

    /** Top-level scalar fields, so metadata can also ride inside the JSON. */
    private fun scalarFields(obj: JSONObject): Map<String, String> = buildMap {
        for (key in obj.keys()) {
            val value = obj.opt(key)
            if (value is JSONObject || value is JSONArray) continue
            put(key, value?.toString().orEmpty())
        }
    }

    /** `vless://…#My%20Server` -> "My Server". */
    private fun nameFromLinkFragment(uri: String): String {
        val hash = uri.indexOf('#')
        if (hash < 0 || hash == uri.lastIndex) return ""
        val fragment = uri.substring(hash + 1)
        return runCatching { java.net.URLDecoder.decode(fragment, "UTF-8") }.getOrDefault(fragment)
    }
}
