package app.smugly.subscription

import org.json.JSONArray
import org.json.JSONObject

/** Persistence for [Subscription]. Shared by the Android prefs store and the desktop file store. */
object SubscriptionJson {

    fun toJson(sub: Subscription): JSONObject = JSONObject()
        .put("id", sub.id)
        .put("name", sub.name)
        .put("url", sub.url)
        .put("enabled", sub.enabled)
        .put("addedAtMs", sub.addedAtMs)
        .put("lastUpdatedMs", sub.lastUpdatedMs)
        .put("updateIntervalMinutes", sub.updateIntervalMinutes)
        .put("userAgent", sub.userAgent)
        .put("lastError", sub.lastError)
        .put("allowReorder", sub.allowReorder)
        .put("showInfo", sub.showInfo)
        .put("hideProtocol", sub.hideProtocol)
        .put(
            "categories",
            JSONArray().apply {
                sub.categories.forEach {
                    put(
                        JSONObject()
                            .put("id", it.id)
                            .put("name", it.name)
                            .put("description", it.description)
                            .put("defaultOpen", it.defaultOpen)
                    )
                }
            }
        )
        .put(
            "info",
            JSONObject()
                .put("uploadBytes", sub.info.uploadBytes)
                .put("downloadBytes", sub.info.downloadBytes)
                .put("totalBytes", sub.info.totalBytes)
                .put("expiresAtSeconds", sub.info.expiresAtSeconds)
                .put("webPageUrl", sub.info.webPageUrl)
                .put("supportUrl", sub.info.supportUrl)
                .put("announce", sub.info.announce)
        )

    fun fromJson(json: JSONObject): Subscription? {
        val id = json.optString("id").ifBlank { return null }
        // Empty folders are a local group with no subscription link. Rejecting a blank URL
        // used to save them and then drop them on the next load — the tab never appeared.
        val url = json.optString("url")
        val name = json.optString("name")
        if (url.isBlank() && name.isBlank()) return null
        val info = json.optJSONObject("info") ?: JSONObject()
        return Subscription(
            id = id,
            // Blank stays blank on purpose: a refresh adopts the panel's `profile-title` only when
            // the record has no name of its own, and defaulting to the URL here made that
            // impossible — the URL is a display fallback, not a name.
            name = name,
            url = url,
            enabled = json.optBoolean("enabled", true),
            addedAtMs = json.optLong("addedAtMs", 0),
            lastUpdatedMs = json.optLong("lastUpdatedMs", 0),
            updateIntervalMinutes = json.optLong(
                "updateIntervalMinutes",
                Subscription.DEFAULT_UPDATE_INTERVAL_MINUTES
            ),
            userAgent = json.optString("userAgent"),
            lastError = json.optString("lastError"),
            allowReorder = json.optBoolean("allowReorder", false),
            showInfo = url.isNotBlank() && json.optBoolean("showInfo", true),
            hideProtocol = json.optBoolean("hideProtocol", false),
            categories = json.optJSONArray("categories")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                    val name = obj.optString("name")
                    val id = obj.optString("id").ifBlank { subscriptionCategoryId(name) }
                    if (id.isBlank()) {
                        null
                    } else {
                        SubscriptionCategory(
                            id = id,
                            name = name,
                            description = obj.optString("description"),
                            defaultOpen = obj.optBoolean("defaultOpen", false)
                        )
                    }
                }
            } ?: emptyList(),
            info = SubscriptionInfo(
                uploadBytes = info.optLong("uploadBytes", 0),
                downloadBytes = info.optLong("downloadBytes", 0),
                totalBytes = info.optLong("totalBytes", 0),
                expiresAtSeconds = info.optLong("expiresAtSeconds", 0),
                webPageUrl = info.optString("webPageUrl"),
                supportUrl = info.optString("supportUrl"),
                announce = info.optString("announce")
            )
        )
    }

    fun listToString(subs: List<Subscription>): String =
        JSONArray().apply { subs.forEach { put(toJson(it)) } }.toString()

    fun listFromString(text: String): List<Subscription> {
        if (text.isBlank()) return emptyList()
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { fromJson(it) }
        }
    }
}
