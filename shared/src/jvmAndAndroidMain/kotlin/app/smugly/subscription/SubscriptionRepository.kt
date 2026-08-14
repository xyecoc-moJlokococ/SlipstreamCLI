package app.smugly.subscription

import app.smugly.Config
import app.smugly.ConfigProfile

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

        /**
         * Which category panels are folded (`subscriptionId/categoryId`). Used to seed the panel's
         * default-open marker after a refresh. Defaults keep older test fakes compiling.
         */
        fun loadCollapsedCategories(): Set<String> = emptySet()
        fun saveCollapsedCategories(ids: Set<String>) {}
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

    /**
     * Create or update a folder from the editor.
     *
     * [id] null creates one. A changed (or new) URL forces a fetch, since the group's contents are
     * defined by it; editing only the name or the switches just persists and leaves the servers
     * alone. Returns an error message, or null when it worked.
     */
    fun save(
        id: String?,
        name: String,
        rawUrl: String,
        enabled: Boolean,
        updateIntervalMinutes: Long,
        allowReorder: Boolean,
        showInfo: Boolean
    ): String? {
        // A folder is allowed to have no link at all: plenty of them are just a place to keep
        // hand-made profiles, and demanding a subscription URL made that impossible. Only a
        // non-empty link has to look like one.
        val url = if (rawUrl.isBlank()) {
            ""
        } else {
            SubscriptionManager.normalizeSubscriptionUrl(rawUrl) ?: return "not a subscription URL"
        }
        // Empty folders need a display name; subscription folders can take the panel title later.
        val resolvedName = name.trim().ifBlank {
            if (url.isBlank()) "Folder" else ""
        }
        if (url.isBlank() && resolvedName.isBlank()) return "folder needs a name"
        // No URL → nothing to refresh. Force updates and the info card off so empty / file
        // folders never grow a quota block or a refresh button.
        val resolvedEnabled = if (url.isBlank()) false else enabled
        val resolvedInterval = if (url.isBlank() || !resolvedEnabled) {
            0L
        } else if (updateIntervalMinutes > 0) {
            updateIntervalMinutes
        } else {
            0L
        }
        val resolvedShowInfo = url.isNotBlank() && showInfo
        val existing = id?.let { find(it) }
            // Re-adding a URL that is already here edits that folder instead of making a twin.
            ?: storage.loadSubscriptions().firstOrNull { url.isNotBlank() && it.url == url }
        val updated = (existing ?: Subscription(
            id = storage.newId(),
            name = resolvedName,
            url = url,
            addedAtMs = storage.nowMs(),
            allowReorder = true
        )).copy(
            name = resolvedName.ifBlank { existing?.name.orEmpty() },
            url = url,
            enabled = resolvedEnabled,
            updateIntervalMinutes = resolvedInterval,
            allowReorder = allowReorder,
            showInfo = resolvedShowInfo
        )
        // Nothing to fetch without a link, so a URL-less folder is simply stored.
        val urlChanged = url.isNotBlank() && (existing == null || existing.url != url)
        if (existing == null) {
            storage.saveSubscriptions(storage.loadSubscriptions() + updated)
        } else {
            persist(updated)
        }
        return if (urlChanged) refresh(updated.id).error else null
    }

    /**
     * Reorder the folder tabs. Ids not mentioned keep their relative order at the end, so a list
     * built from a stale UI snapshot can never drop a subscription.
     */
    fun reorder(orderedIds: List<String>) {
        val all = storage.loadSubscriptions()
        val byId = all.associateBy { it.id }
        val moved = orderedIds.mapNotNull { byId[it] }
        val movedIds = moved.map { it.id }.toSet()
        storage.saveSubscriptions(moved + all.filterNot { it.id in movedIds })
    }

    fun refresh(id: String): ImportResult {
        val existing = find(id)
            ?: return ImportResult(Subscription(id = id, name = "", url = ""), 0, "unknown subscription")
        if (existing.url.isBlank()) return ImportResult(existing, 0, "folder has no subscription link")
        // Panel defaults only matter the first time this folder is filled: after that the user
        // may have opened/closed groups on purpose, and a refresh must not undo that.
        val firstSuccessfulFetch = existing.lastUpdatedMs <= 0

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
        if (firstSuccessfulFetch) {
            seedCategoryCollapse(existing.id, result.subscription.categories)
        } else {
            // Drop keys for categories the panel no longer ships; leave the rest as the user left them.
            pruneGoneCategoryCollapse(existing.id, result.subscription.categories)
        }
        return ImportResult(result.subscription, profiles.size)
    }

    /** Refresh every subscription whose interval has elapsed. */
    // Link-less folders are skipped rather than "refreshed" into an error: they are a place to
    // keep hand-made profiles, and there is nothing to fetch.
    fun refreshDue(): List<ImportResult> =
        SubscriptionManager.dueForUpdate(storage.loadSubscriptions(), storage.nowMs())
            .filter { it.url.isNotBlank() }
            .map { refresh(it.id) }

    fun refreshAll(): List<ImportResult> =
        storage.loadSubscriptions().filter { it.url.isNotBlank() }.map { refresh(it.id) }

    /** Removes the subscription and every profile that came from it. */
    fun delete(id: String) {
        storage.saveSubscriptions(storage.loadSubscriptions().filterNot { it.id == id })
        storage.writeProfiles(storage.loadProfiles().filterNot { it.subscriptionId == id })
        // Drop fold state that pointed at categories of a folder that no longer exists.
        val prefix = "$id/"
        storage.saveCollapsedCategories(
            storage.loadCollapsedCategories().filterNot { it.startsWith(prefix) }.toSet()
        )
    }

    /** First successful fetch only: apply the panel's default-open rule. */
    private fun seedCategoryCollapse(subscriptionId: String, categories: List<SubscriptionCategory>) {
        val next = applyCategoryCollapseDefaults(
            subscriptionId = subscriptionId,
            categories = categories,
            current = storage.loadCollapsedCategories()
        )
        storage.saveCollapsedCategories(next)
    }

    /**
     * Later refreshes: keep the user's fold state, but forget keys for categories that disappeared
     * from the payload so they cannot reappear as phantom closed groups later.
     */
    private fun pruneGoneCategoryCollapse(subscriptionId: String, categories: List<SubscriptionCategory>) {
        val prefix = "$subscriptionId/"
        val live = categories.mapTo(HashSet()) { "$subscriptionId/${it.id}" }
        val current = storage.loadCollapsedCategories()
        val next = current.filterTo(HashSet()) { key ->
            !key.startsWith(prefix) || key in live
        }
        if (next != current) storage.saveCollapsedCategories(next)
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
        // Each old id may be claimed by at most one fresh server. Public lists routinely carry
        // several servers under the identical name (three "🇩🇪 Germany | 🌐 [*CIDR]" in a row),
        // and the previous `associateBy { it.name }` handed that one old id to every one of them
        // — minting profiles that shared an id, so selecting one lit up all of them and the lazy
        // list refused to render the folder at all.
        val unclaimed = all
            .filter { it.subscriptionId == subscriptionId }
            .groupByTo(mutableMapOf(), { it.name }, { it.id })
        val reconciled = fresh.map { profile ->
            val queue = unclaimed[profile.name]
            val claimed = if (queue.isNullOrEmpty()) null else queue.removeAt(0)
            if (claimed != null) profile.copy(id = claimed) else profile
        }
        val firstIndex = all.indexOfFirst { it.subscriptionId == subscriptionId }
        val others = all.filterNot { it.subscriptionId == subscriptionId }
        val merged = if (firstIndex < 0) {
            others + reconciled
        } else {
            val head = others.take(firstIndex)
            head + reconciled + others.drop(firstIndex)
        }
        storage.writeProfiles(withUniqueIds(merged))
    }

    /**
     * Last word on the invariant every id-keyed thing depends on: the active selection, the
     * per-profile menu, and the lazy list, which refuses to render a repeated key at all.
     *
     * Enforced rather than trusted, because a group written by an older build can already contain
     * repeats — inheriting an id by name faithfully carries those forward, so reassigning here is
     * what actually repairs an existing list on its next refresh.
     */
    private fun withUniqueIds(profiles: List<ConfigProfile>): List<ConfigProfile> {
        val seen = mutableSetOf<String>()
        return profiles.map { profile ->
            if (profile.id.isNotBlank() && seen.add(profile.id)) {
                profile
            } else {
                profile.copy(id = storage.newId()).also { seen.add(it.id) }
            }
        }
    }

    private fun toProfile(entry: SubscriptionContent.Entry, subscriptionId: String): ConfigProfile? {
        val base = storage.baseConfig()
        val category = entry.categoryId.ifBlank { null }
        return when (entry) {
            is SubscriptionContent.Entry.XrayJson -> ConfigProfile(
                id = storage.newId(),
                name = entry.name,
                config = base.copy(
                    protocol = Config.TunnelProtocol.XRAY,
                    xrayConfigJson = entry.json
                ),
                subscriptionId = subscriptionId,
                categoryId = category
            )
            is SubscriptionContent.Entry.Link -> storage.profileFromLink(entry.uri, entry.name)
                ?.copy(id = storage.newId(), subscriptionId = subscriptionId, categoryId = category)
                ?.let { profile ->
                    // Prefer the panel's label over whatever the parser derived.
                    if (entry.name.isNotBlank()) profile.copy(name = entry.name) else profile
                }
        }
    }
}
