package app.vaydns.subscription

/**
 * Fetch + parse + fold-into-the-record, with no storage or UI knowledge.
 *
 * A refresh returns the updated [Subscription] and the config links it advertised; turning those
 * links into profiles is left to the caller, because each platform already owns an importer that
 * understands this app's protocols.
 */
object SubscriptionManager {

    data class RefreshResult(
        /** The record to persist — metadata folded in, timestamps and error state updated. */
        val subscription: Subscription,
        /** Servers advertised by the subscription; empty on failure. */
        val entries: List<SubscriptionContent.Entry>,
        /** Null on success. */
        val error: String? = null
    ) {
        val isSuccess: Boolean get() = error == null
    }

    /**
     * Normalises what a user might paste: panels hand out `https://…`, but links are also shared as
     * `sub://<base64 url>` and as our own `slipstream://install-sub?url=…` deep link.
     * Returns null when there is no usable http(s) URL in [raw].
     */
    fun normalizeSubscriptionUrl(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        // Deep link form: <scheme>://install-sub?url=<encoded>
        val installSub = Regex("""^[a-z0-9+.-]+://install-sub\?(.*)$""", RegexOption.IGNORE_CASE)
            .find(text)
        if (installSub != null) {
            val query = installSub.groupValues[1]
            val url = query.split('&')
                .map { it.split('=', limit = 2) }
                .firstOrNull { it.size == 2 && it[0].equals("url", ignoreCase = true) }
                ?.get(1)
                ?.let { percentDecode(it) }
            return url?.let { normalizeSubscriptionUrl(it) }
        }

        // sub:// wraps the real URL in base64.
        if (text.startsWith("sub://", ignoreCase = true)) {
            val decoded = SubscriptionParser.decodeBase64ToString(text.removePrefix("sub://").removePrefix("SUB://"))
            return decoded?.let { normalizeSubscriptionUrl(it) }
        }

        if (text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)) {
            // Reject anything with whitespace inside — that is a pasted blob, not a single URL.
            return text.takeIf { it.none { ch -> ch.isWhitespace() } }
        }
        return null
    }

    /** True when [raw] is a subscription link rather than a single config link. */
    fun looksLikeSubscription(raw: String): Boolean = normalizeSubscriptionUrl(raw) != null

    fun refresh(
        subscription: Subscription,
        nowMs: Long,
        /** Routes to try, in order; see [SubscriptionFetcher.fetch]. */
        routes: List<SubscriptionFetcher.ProxySpec?> = listOf(null)
    ): RefreshResult {
        val result = SubscriptionFetcher.fetch(subscription.url, subscription.userAgent, routes)
        val response = result.getOrElse { error ->
            return RefreshResult(
                subscription = subscription.copy(lastError = error.message ?: "fetch failed"),
                entries = emptyList(),
                error = error.message ?: "fetch failed"
            )
        }

        val parsed = SubscriptionContent.parse(response.body, response.headers)
        if (parsed.entries.isEmpty()) {
            val message = "no configs in subscription response"
            return RefreshResult(
                subscription = subscription.copy(lastError = message),
                entries = emptyList(),
                error = message
            )
        }

        return RefreshResult(
            subscription = subscription.copy(
                // Keep a user-chosen name; only adopt the panel's title when we have none of our own.
                name = subscription.name.ifBlank { parsed.metadata.title }
                    .ifBlank { subscription.url },
                lastUpdatedMs = nowMs,
                updateIntervalMinutes = parsed.metadata.updateIntervalHours
                    .takeIf { it > 0 }
                    ?.times(60)
                    ?: subscription.updateIntervalMinutes,
                info = parsed.metadata.info,
                lastError = ""
            ),
            entries = parsed.entries
        )
    }

    /** Subscriptions whose refresh interval has elapsed. */
    fun dueForUpdate(subs: List<Subscription>, nowMs: Long): List<Subscription> =
        subs.filter { it.isUpdateDue(nowMs) }

    private fun percentDecode(value: String): String = runCatching {
        java.net.URLDecoder.decode(value, "UTF-8")
    }.getOrDefault(value)
}
