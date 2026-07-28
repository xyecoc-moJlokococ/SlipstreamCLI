package app.vaydns.subscription

import app.vaydns.Config
import app.vaydns.ConfigProfile

/**
 * Everything a subscription does to stored state, over a tiny storage port so Android
 * (SharedPreferences) and desktop (files) share one implementation.
 *
 * The rule that matters: a refresh **replaces exactly the profiles carrying this subscription's
 * id** and never touches hand-made ones or other subscriptions' profiles.
 */
class SubscriptionRepository(private val storage: Storage) {

    /** The bits of persistence this needs; implemented by each platform's profile store. */
    interface Storage {
        fun loadSubscriptions(): List<Subscription>
        fun saveSubscriptions(subs: List<Subscription>)
        fun loadProfiles(): List<ConfigProfile>
        fun writeProfiles(profiles: List<ConfigProfile>)
        /** Template for new profiles (listen port, mode, …). */
        fun baseConfig(): Config
        fun newId(): String
        fun nowMs(): Long

        /**
         * Turn a config URI (`vless://`, `slipstream://`, …) into a profile, or null if the scheme
         * is not understood. Left to the platform because each already owns the URI parsers; the
         * profile's id and subscriptionId are set by the caller.
         */
        fun profileFromLink(uri: String, name: String): ConfigProfile?

        /**
         * Routes to try when fetching, in order. A panel is frequently only reachable through the
         * tunnel it hands out, so the platform gets to put its own proxy first.
         */
        fun fetchRoutes(): List<SubscriptionFetcher.ProxySpec?> = listOf(null)
    }

    data class ImportResult(
        val subscription: Subscription,
        val profileCount: Int,
        val error: String? = null
    ) {
        val isSuccess: Boolean get() = error == null
    }

    fun list(): List<Subscription> = storage.loadSubscriptions()

    fun find(id: String): Subscription? = storage.loadSubscriptions().firstOrNull { it.id == id }

    /**
     * Add a subscription from anything the user pasted (plain URL, `install-sub` deep link,
     * `sub://…`) and fetch it immediately. Re-adding an existing URL refreshes it instead of
     * creating a duplicate folder.
     */
    fun add(rawUrl: String, name: String = ""): ImportResult {
        val url = SubscriptionManager.normalizeSubscriptionUrl(rawUrl)
            ?: return ImportResult(
                Subscription(id = "", name = name, url = rawUrl),
                0,
                "not a subscription URL"
            )

        storage.loadSubscriptions().firstOrNull { it.url == url }?.let { return refresh(it.id) }

        val subscription = Subscription(
            id = storage.newId(),
            name = name,
            url = url,
            addedAtMs = storage.nowMs()
        )
        storage.saveSubscriptions(storage.loadSubscriptions() + subscription)
        return refresh(subscription.id)
    }

    fun refresh(id: String): ImportResult {
        val existing = find(id)
            ?: return ImportResult(Subscription(id = id, name = "", url = ""), 0, "unknown subscription")

        val result = SubscriptionManager.refresh(existing, storage.nowMs(), storage.fetchRoutes())
        persist(result.subscription)
        if (!result.isSuccess) return ImportResult(result.subscription, 0, result.error)

        val profiles = result.entries.mapNotNull { toProfile(it, existing.id) }
        if (profiles.isEmpty()) {
            val message = "subscription had no importable servers"
            val failed = result.subscription.copy(lastError = message)
            persist(failed)
            return ImportResult(failed, 0, message)
        }
        replaceGroup(existing.id, profiles)
        return ImportResult(result.subscription, profiles.size)
    }

    /** Refresh every subscription whose interval has elapsed. */
    fun refreshDue(): List<ImportResult> =
        SubscriptionManager.dueForUpdate(storage.loadSubscriptions(), storage.nowMs())
            .map { refresh(it.id) }

    fun refreshAll(): List<ImportResult> = storage.loadSubscriptions().map { refresh(it.id) }

    /** Removes the subscription and every profile that came from it. */
    fun delete(id: String) {
        storage.saveSubscriptions(storage.loadSubscriptions().filterNot { it.id == id })
        storage.writeProfiles(storage.loadProfiles().filterNot { it.subscriptionId == id })
    }

    fun setEnabled(id: String, enabled: Boolean) {
        find(id)?.let { persist(it.copy(enabled = enabled)) }
    }

    fun rename(id: String, name: String) {
        find(id)?.let { persist(it.copy(name = name)) }
    }

    /** Profiles belonging to [id], in subscription order. */
    fun profilesOf(id: String): List<ConfigProfile> =
        storage.loadProfiles().filter { it.subscriptionId == id }

    /** Profiles the user made themselves — the "Home" folder. */
    fun ownProfiles(): List<ConfigProfile> =
        storage.loadProfiles().filter { it.subscriptionId == null }

    private fun persist(sub: Subscription) {
        val updated = storage.loadSubscriptions().map { if (it.id == sub.id) sub else it }
        storage.saveSubscriptions(updated)
    }

    /**
     * Swap this subscription's profiles for the freshly fetched ones, keeping their position in the
     * list so the UI does not jump, and preserving ids of servers that kept the same name so the
     * active selection survives a refresh.
     */
    private fun replaceGroup(subscriptionId: String, fresh: List<ConfigProfile>) {
        val all = storage.loadProfiles()
        val oldById = all.filter { it.subscriptionId == subscriptionId }.associateBy { it.name }
        val reconciled = fresh.map { profile ->
            oldById[profile.name]?.let { profile.copy(id = it.id) } ?: profile
        }
        val firstIndex = all.indexOfFirst { it.subscriptionId == subscriptionId }
        val others = all.filterNot { it.subscriptionId == subscriptionId }
        val merged = if (firstIndex < 0) {
            others + reconciled
        } else {
            val head = others.take(firstIndex)
            head + reconciled + others.drop(firstIndex)
        }
        storage.writeProfiles(merged)
    }

    private fun toProfile(entry: SubscriptionContent.Entry, subscriptionId: String): ConfigProfile? {
        val base = storage.baseConfig()
        return when (entry) {
            is SubscriptionContent.Entry.XrayJson -> ConfigProfile(
                id = storage.newId(),
                name = entry.name,
                config = base.copy(
                    protocol = Config.TunnelProtocol.XRAY,
                    xrayConfigJson = entry.json
                ),
                subscriptionId = subscriptionId
            )
            is SubscriptionContent.Entry.Link -> storage.profileFromLink(entry.uri, entry.name)
                ?.copy(id = storage.newId(), subscriptionId = subscriptionId)
                ?.let { profile ->
                    // Prefer the panel's label over whatever the parser derived.
                    if (entry.name.isNotBlank()) profile.copy(name = entry.name) else profile
                }
        }
    }
}
