package app.vaydns.subscription

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
