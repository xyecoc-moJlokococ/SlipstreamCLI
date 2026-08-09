package app.smugly.subscription

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
 *
 * **Categories** (sub-groups inside one folder) are picked up the same tolerant way, because a panel
 * can express them in any of the shapes its own data model already has:
 *
 *  - a named node that holds configs — `{"name": "Обход БС", "description": "…", "configs": [ … ]}`;
 *  - a field on the config itself — `{"remarks": "…", "category": "Обход БС", "outbounds": […]}`;
 *  - a declaration block that only names them — `{"categories": [{"name": …, "description": …}], …}`,
 *    which is how a flat list gets its descriptions.
 *
 * The three combine freely: whichever the panel sends, the group is identified by its name (see
 * [subscriptionCategoryId]) so the same category from two shapes is still one category.
 */
object SubscriptionContent {

    sealed interface Entry {
        /** Display name shown in the profile list. */
        val name: String

        /** Category this server was listed under; blank when the panel does not use them. */
        val categoryId: String

        /** A `scheme://` config URI. */
        data class Link(
            val uri: String,
            override val name: String = "",
            override val categoryId: String = ""
        ) : Entry

        /** A complete Xray config document, kept exactly as the panel sent it. */
        data class XrayJson(
            override val name: String,
            val json: String,
            override val categoryId: String = ""
        ) : Entry
    }

    data class Parsed(
        val metadata: SubscriptionParser.Metadata,
        val entries: List<Entry>,
        /** Categories the payload declared, in the order it listed them. */
        val categories: List<SubscriptionCategory> = emptyList()
    )

    /** Keys checked for a server's display name, in order of preference. */
    private val NAME_KEYS = listOf("remarks", "remark", "name", "title", "label", "tag")

    /**
     * Keys that make a node a *category* rather than a wrapper. Deliberately excludes `remarks` and
     * `tag`: those name individual servers, and a wrapper that happens to carry one is not a group.
     */
    private val GROUP_NAME_KEYS = listOf("category", "group", "name", "title", "label")

    /** Category a single config declares for itself. */
    private val CATEGORY_FIELD_KEYS = listOf("category", "group", "folder", "section")

    private val DESCRIPTION_KEYS = listOf("description", "desc", "subtitle", "about", "note")

    /** A config may carry its category's blurb too, so a flat list needs no declaration block. */
    private val CATEGORY_DESCRIPTION_KEYS = listOf(
        "categorydescription", "groupdescription", "categorydesc", "groupdesc"
    )

    /** Keys under which a payload may simply *list* its categories. */
    private val DECLARATION_KEYS = setOf("categories", "groups", "folders", "sections")

    /** Where a labelled object keeps its config URI. */
    private val LINK_KEYS = listOf("link", "uri", "url", "config", "server")

    /**
     * On a category object: truthy means this group starts open (others collapse).
     * Spelling is deliberately loose — same spirit as every other panel field.
     */
    private val DEFAULT_OPEN_FLAG_KEYS = listOf(
        "defaultopen", "default", "openbydefault", "defaultexpanded", "main", "isdefault"
    )

    /**
     * On the document root: names the category that should start open
     * (`"defaultCategory": "Повседневный обход"`).
     */
    private val DEFAULT_OPEN_NAME_KEYS = listOf(
        "defaultcategory", "defaultopen", "maincategory", "opendefault"
    )

    /** Guards against a pathological or hostile document. */
    private const val MAX_DEPTH = 8
    private const val MAX_ENTRIES = 500

    fun parse(body: String, headers: Map<String, String> = emptyMap()): Parsed {
        val textParse = SubscriptionParser.parse(body, headers)

        val root = parseJsonRoot(body)
        if (root != null) {
            val walk = Walk()
            walk.collect(root, category = "", depth = 0)
            if (walk.entries.isNotEmpty()) {
                // A JSON payload may also carry the subscription metadata alongside the servers.
                val fromJson = (root as? JSONObject)?.let { SubscriptionParser.metadataFrom(scalarFields(it)) }
                val metadata = if (fromJson != null) {
                    SubscriptionParser.mergeMetadata(primary = textParse.metadata, fallback = fromJson)
                } else {
                    textParse.metadata
                }
                // Root-level "defaultCategory": "Name" is an alternative to a flag on the group.
                val rootDefault = (root as? JSONObject)?.let { pick(scalarFields(it), DEFAULT_OPEN_NAME_KEYS) }
                val categories = SubscriptionParser.markDefaultOpen(walk.categoriesInOrder(), rootDefault)
                return Parsed(metadata, walk.entries, categories)
            }
        }

        return Parsed(
            textParse.metadata,
            textParse.entries.map { Entry.Link(it.uri, nameFromLinkFragment(it.uri), it.categoryId) },
            textParse.categories
        )
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
     * One pass over a JSON document: the servers it holds and the categories they fell under.
     *
     * A category is only *created* by a node that actually contains servers, or by an explicit
     * declaration block — so a wrapper object with a stray `name` cannot invent an empty group.
     */
    private class Walk {
        val entries = ArrayList<Entry>()
        private val categories = LinkedHashMap<String, SubscriptionCategory>()

        fun categoriesInOrder(): List<SubscriptionCategory> = categories.values.toList()

        /**
         * Record a category and return its id. A later non-blank description wins over a blank one,
         * so a declaration block and a group node can each supply half of the same category.
         * Once [defaultOpen] is true it sticks — a second mention without the flag must not clear it.
         */
        fun register(
            name: String,
            description: String,
            explicitId: String? = null,
            defaultOpen: Boolean = false
        ): String {
            val id = explicitId?.trim()?.ifBlank { null } ?: subscriptionCategoryId(name)
            val existing = categories[id]
            categories[id] = SubscriptionCategory(
                id = id,
                name = existing?.name?.ifBlank { name } ?: name,
                description = description.ifBlank { existing?.description.orEmpty() },
                defaultOpen = defaultOpen || existing?.defaultOpen == true
            )
            return id
        }

        /** A config naming its own category may be referring to an id the panel already declared. */
        fun idFor(value: String, description: String): String =
            if (categories.containsKey(value)) value else register(value, description)

        fun collect(node: Any?, category: String, depth: Int) {
            if (depth > MAX_DEPTH || entries.size >= MAX_ENTRIES) return
            when (node) {
                is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), category, depth + 1)
                is JSONObject -> collectObject(node, category, depth)
                // A bare `"s3fu://…"` string is a server too. This app's own protocols have no
                // JSON config document at all — they exist only as links — so without this a
                // panel could group its Xray servers but not the tunnels it hands out for the
                // very case the groups are usually about.
                is String -> if (SubscriptionParser.isConfigLink(node)) {
                    entries.add(Entry.Link(node.trim(), nameFromLinkFragment(node), category))
                }
            }
        }

        private fun collectObject(node: JSONObject, category: String, depth: Int) {
            // Once an object qualifies as a server it is taken whole and not descended into — its
            // own `outbounds` entries are parts of that config, not separate servers.
            if (isServerConfig(node)) {
                entries.add(toEntry(node, entries.size, category))
                return
            }
            // A link with a label around it: {"name": "…", "link": "s3fu://…"}.
            linkEntry(node, category)?.let {
                entries.add(it)
                return
            }
            for (key in node.keys()) {
                if (SubscriptionParser.normalizeKey(key) in DECLARATION_KEYS) declare(node.opt(key))
            }
            val fields = scalarFields(node)
            // The root is the document, not a group: a subscription-wide `name` describes the whole
            // list, and turning it into a category would wrap every server in one meaningless group.
            val name = if (depth == 0) null else pick(fields, GROUP_NAME_KEYS)
            val here = if (name != null && holdsConfig(node, depth)) {
                register(
                    name = name,
                    description = pick(fields, DESCRIPTION_KEYS).orEmpty(),
                    explicitId = fields[SubscriptionParser.normalizeKey("id")],
                    defaultOpen = isDefaultOpenFlag(fields)
                )
            } else {
                category
            }
            for (key in node.keys()) collect(node.opt(key), here, depth + 1)
        }

        /** `{"categories": [{"name": …, "description": …, "defaultOpen": true}]}` and map shape. */
        private fun declare(node: Any?) {
            when (node) {
                is JSONArray -> for (i in 0 until node.length()) declare(node.opt(i))
                is JSONObject -> {
                    val fields = scalarFields(node)
                    val name = pick(fields, GROUP_NAME_KEYS)
                    if (name != null) {
                        register(
                            name = name,
                            description = pick(fields, DESCRIPTION_KEYS).orEmpty(),
                            explicitId = fields[SubscriptionParser.normalizeKey("id")],
                            defaultOpen = isDefaultOpenFlag(fields)
                        )
                        return
                    }
                    // Map shape: {"Повседневный обход": "описание"} — the key is the name.
                    for (key in node.keys()) {
                        val value = node.opt(key)
                        when (value) {
                            is JSONObject -> {
                                val inner = scalarFields(value)
                                register(
                                    name = pick(inner, GROUP_NAME_KEYS) ?: key,
                                    description = pick(inner, DESCRIPTION_KEYS).orEmpty(),
                                    explicitId = key,
                                    defaultOpen = isDefaultOpenFlag(inner)
                                )
                            }
                            is JSONArray -> Unit
                            else -> register(key, value?.toString().orEmpty())
                        }
                    }
                }
            }
        }

        private fun isDefaultOpenFlag(fields: Map<String, String>): Boolean =
            DEFAULT_OPEN_FLAG_KEYS.any { key ->
                fields[SubscriptionParser.normalizeKey(key)]
                    ?.trim()
                    ?.lowercase()
                    ?.let { it == "true" || it == "1" || it == "yes" || it == "on" } == true
            }

        /** An object wrapping a single config URI, or null when it is not one. */
        private fun linkEntry(obj: JSONObject, inherited: String): Entry.Link? {
            val fields = scalarFields(obj)
            val uri = pick(fields, LINK_KEYS)?.takeIf { SubscriptionParser.isConfigLink(it) } ?: return null
            val own = pick(fields, CATEGORY_FIELD_KEYS)
            val categoryId = if (own != null) {
                idFor(own, pick(fields, CATEGORY_DESCRIPTION_KEYS).orEmpty())
            } else {
                inherited
            }
            return Entry.Link(
                uri = uri,
                name = pick(fields, NAME_KEYS) ?: nameFromLinkFragment(uri),
                categoryId = categoryId
            )
        }

        private fun toEntry(obj: JSONObject, index: Int, inherited: String): Entry.XrayJson {
            val name = NAME_KEYS.firstNotNullOfOrNull { key -> obj.optString(key).takeIf { it.isNotBlank() } }
                ?: "Server ${index + 1}"
            val fields = scalarFields(obj)
            val own = pick(fields, CATEGORY_FIELD_KEYS)
            val categoryId = if (own != null) {
                idFor(own, pick(fields, CATEGORY_DESCRIPTION_KEYS).orEmpty())
            } else {
                inherited
            }
            return Entry.XrayJson(name = name, json = obj.toString(), categoryId = categoryId)
        }
    }

    /** An Xray config is identified by having somewhere to send traffic. */
    private fun isServerConfig(obj: JSONObject): Boolean =
        (obj.optJSONArray("outbounds")?.length() ?: 0) > 0

    /** Whether a server sits anywhere under [node] — what makes a named node a category. */
    private fun holdsConfig(node: Any?, depth: Int): Boolean {
        if (depth > MAX_DEPTH) return false
        return when (node) {
            is JSONArray -> (0 until node.length()).any { holdsConfig(node.opt(it), depth + 1) }
            is JSONObject ->
                isServerConfig(node) ||
                    pick(scalarFields(node), LINK_KEYS)?.let { SubscriptionParser.isConfigLink(it) } == true ||
                    node.keys().asSequence().any { holdsConfig(node.opt(it), depth + 1) }
            // A group may hold nothing but links — that is the whole point for s3fu / Slipstream.
            is String -> SubscriptionParser.isConfigLink(node)
            else -> false
        }
    }

    /**
     * Scalar fields keyed by their normalised name, so metadata can ride inside the JSON and
     * `category_name` / `categoryName` / `Category-Name` are one key.
     */
    private fun scalarFields(obj: JSONObject): Map<String, String> = buildMap {
        for (key in obj.keys()) {
            val value = obj.opt(key)
            if (value is JSONObject || value is JSONArray) continue
            val normalized = SubscriptionParser.normalizeKey(key)
            if (!containsKey(normalized)) put(normalized, value?.toString().orEmpty())
        }
    }

    /** First non-blank value among [keys], in preference order rather than document order. */
    private fun pick(fields: Map<String, String>, keys: List<String>): String? =
        keys.firstNotNullOfOrNull { key ->
            fields[SubscriptionParser.normalizeKey(key)]
                ?.let { SubscriptionParser.decodeText(it) }
                ?.takeIf { it.isNotBlank() }
        }

    /** `vless://…#My%20Server` -> "My Server". */
    private fun nameFromLinkFragment(uri: String): String {
        val hash = uri.indexOf('#')
        if (hash < 0 || hash == uri.lastIndex) return ""
        val fragment = uri.substring(hash + 1)
        return runCatching { java.net.URLDecoder.decode(fragment, "UTF-8") }.getOrDefault(fragment)
    }
}
