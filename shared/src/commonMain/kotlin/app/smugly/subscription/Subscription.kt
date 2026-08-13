package app.smugly.subscription

/**
 * An imported subscription — the "folder" concept from v2rayNG, carrying the metadata Happ-style
 * panels publish (title, quota, expiry).
 *
 * Profiles produced by a subscription keep its [id] in `ConfigProfile.subscriptionId`, so a refresh
 * can replace exactly that group and leave hand-made profiles alone.
 */
data class Subscription(
    val id: String,
    /** Display name. Taken from `profile-title` when the server sends one, else user-supplied. */
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val addedAtMs: Long = 0,
    val lastUpdatedMs: Long = 0,
    /**
     * Minutes between automatic refreshes. Servers advertise this in hours via
     * `profile-update-interval`; 0 disables automatic refresh.
     */
    val updateIntervalMinutes: Long = DEFAULT_UPDATE_INTERVAL_MINUTES,
    /** Some panels serve different payloads per client; empty means the app default. */
    val userAgent: String = "",
    /** Metadata from the most recent successful fetch. */
    val info: SubscriptionInfo = SubscriptionInfo(),
    /**
     * Whether profiles in this folder may be dragged into a different order. On by default —
     * users expect to rearrange servers; a subscription refresh still rewrites the group, but
     * hand-made order between refreshes is useful.
     */
    val allowReorder: Boolean = true,
    /** Whether the traffic / expiry card is drawn above the folder's servers. */
    val showInfo: Boolean = true,
    /**
     * Panel asked for the protocol line under each server name to be hidden (`hide-protocol`).
     * Which engine carries a profile is an implementation detail an operator may not want their
     * users reading off the card; the profile still runs exactly the same.
     */
    val hideProtocol: Boolean = false,
    /**
     * Sub-groups the panel published, in the order it listed them. Empty for a subscription that
     * does not use them — then the folder is a plain list of servers, exactly as before.
     */
    val categories: List<SubscriptionCategory> = emptyList(),
    /** Message from the last failed refresh; blank when the last refresh worked. */
    val lastError: String = ""
) {
    companion object {
        const val DEFAULT_UPDATE_INTERVAL_MINUTES = 24L * 60
    }

    /** True when enough time has passed that a refresh is due. */
    fun isUpdateDue(nowMs: Long): Boolean {
        if (!enabled || updateIntervalMinutes <= 0) return false
        if (lastUpdatedMs <= 0) return true
        return nowMs - lastUpdatedMs >= updateIntervalMinutes * 60_000
    }
}

/**
 * A sub-group inside one subscription folder: "Повседневный обход", "Обход БС (s3-fuckup)", …
 *
 * The panel owns these — it decides which servers belong to which group and what each one is for.
 * [description] is optional and is the whole point of the feature: a category can explain, in the
 * app, what its servers actually do. A blank one simply renders as a heading.
 *
 * [defaultOpen] is the panel's optional "main" category marker: applied **once on first import**
 * (only that group starts expanded, the rest collapsed). When none is marked, every category
 * starts open. Later refreshes leave the user's open/closed state alone.
 */
data class SubscriptionCategory(
    /**
     * Identifies the group within its subscription. Derived from [name] unless the panel sent an
     * explicit id, so the same category keeps its identity (and its collapsed state) across a
     * refresh even though every profile is rebuilt.
     */
    val id: String,
    val name: String,
    val description: String = "",
    /** Panel wants this group open by default (and every other group closed). */
    val defaultOpen: Boolean = false
)

/**
 * Collapse keys (`subscriptionId/categoryId`) implied by the panel's default-open marker.
 *
 * - No category has [SubscriptionCategory.defaultOpen] → empty set (all open).
 * - At least one is marked → every other category of this subscription is collapsed; the first
 *   marked one stays open if the panel somehow sent two.
 */
fun defaultCollapsedCategoryKeys(
    subscriptionId: String,
    categories: List<SubscriptionCategory>
): Set<String> {
    if (categories.isEmpty()) return emptySet()
    val openId = categories.firstOrNull { it.defaultOpen }?.id ?: return emptySet()
    return categories
        .asSequence()
        .filter { it.id != openId }
        .map { "$subscriptionId/${it.id}" }
        .toSet()
}

/**
 * Drop any prior collapse state for [subscriptionId], then seed the panel's default
 * (all open when no marker, otherwise only the marked category open).
 */
fun applyCategoryCollapseDefaults(
    subscriptionId: String,
    categories: List<SubscriptionCategory>,
    current: Set<String>
): Set<String> {
    val prefix = "$subscriptionId/"
    val without = current.filterNot { it.startsWith(prefix) }.toSet()
    return without + defaultCollapsedCategoryKeys(subscriptionId, categories)
}

/**
 * Category id for a group the panel named but did not give an id.
 *
 * Case- and punctuation-insensitive so "Обход БС (s3-fuckup)" and "обход бс (s3 fuckup)" are the
 * same group; a name that survives none of that (emoji only, say) falls back to the raw text, which
 * is still stable.
 */
fun subscriptionCategoryId(name: String): String {
    val slug = buildString {
        var pendingSeparator = false
        for (ch in name.trim().lowercase()) {
            if (ch.isLetterOrDigit()) {
                if (pendingSeparator && isNotEmpty()) append('-')
                pendingSeparator = false
                append(ch)
            } else {
                pendingSeparator = true
            }
        }
    }
    return slug.ifBlank { name.trim() }
}

/**
 * Quota / expiry reported by the panel.
 *
 * Byte counts come from the `subscription-userinfo` header; [totalBytes] and [expiresAtSeconds] use
 * 0 to mean "unlimited" / "never", which is what panels send for open-ended plans.
 */
data class SubscriptionInfo(
    val uploadBytes: Long = 0,
    val downloadBytes: Long = 0,
    val totalBytes: Long = 0,
    /** Unix time in **seconds**, as sent by the panel. 0 = no expiry. */
    val expiresAtSeconds: Long = 0,
    val webPageUrl: String = "",
    val supportUrl: String = "",
    val announce: String = ""
) {
    val usedBytes: Long get() = uploadBytes + downloadBytes
    val hasQuota: Boolean get() = totalBytes > 0
    val hasExpiry: Boolean get() = expiresAtSeconds > 0

    /**
     * True when the panel actually said something about traffic.
     *
     * A list that ships no `subscription-userinfo` header at all leaves every counter at 0, which
     * is indistinguishable from a fresh plan — and "0 B used" under a plain config list is a number
     * we do not have, so the card shows nothing instead. Any real quota or any consumed byte turns
     * the line back on.
     */
    val hasTraffic: Boolean get() = usedBytes > 0 || totalBytes > 0

    /** 0..1 of the quota consumed, or null when the plan is unlimited. */
    fun usedFraction(): Float? {
        if (totalBytes <= 0) return null
        return (usedBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
    }

    /** Whole days until expiry; negative once expired, null when there is no expiry. */
    fun daysLeft(nowMs: Long): Long? {
        if (expiresAtSeconds <= 0) return null
        val diffMs = expiresAtSeconds * 1000 - nowMs
        return diffMs / 86_400_000
    }
}

/** Human-readable byte size, matching the "258,0GB" style panels use. */
fun formatBytes(n: Long): String {
    if (n <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = n.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) {
        "${value.toLong()} ${units[unit]}"
    } else {
        val rounded = (value * 10).toLong() / 10.0
        val whole = rounded.toLong()
        val frac = ((rounded - whole) * 10).toLong()
        "$whole.$frac ${units[unit]}"
    }
}
