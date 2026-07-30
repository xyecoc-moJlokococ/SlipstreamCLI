package app.smugly.subscription

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubscriptionManagerTest {

    @Test
    fun acceptsPlainHttpUrls() {
        assertEquals(
            "https://panel.example.com/sub/abc",
            SubscriptionManager.normalizeSubscriptionUrl("  https://panel.example.com/sub/abc  ")
        )
        assertEquals(
            "http://panel.example.com/sub",
            SubscriptionManager.normalizeSubscriptionUrl("http://panel.example.com/sub")
        )
    }

    @Test
    fun unwrapsInstallSubDeepLink() {
        assertEquals(
            "https://panel.example.com/sub/abc?token=1",
            SubscriptionManager.normalizeSubscriptionUrl(
                "slipstream://install-sub?url=https%3A%2F%2Fpanel.example.com%2Fsub%2Fabc%3Ftoken%3D1"
            )
        )
        // Other clients' schemes should work too — users paste whatever they were given.
        assertEquals(
            "https://p.example/sub",
            SubscriptionManager.normalizeSubscriptionUrl("v2rayng://install-sub?url=https%3A%2F%2Fp.example%2Fsub")
        )
    }

    @Test
    fun unwrapsBase64SubScheme() {
        // sub://<base64 of the real url>
        val encoded = "aHR0cHM6Ly9wYW5lbC5leGFtcGxlLmNvbS9zdWI="
        assertEquals(
            "https://panel.example.com/sub",
            SubscriptionManager.normalizeSubscriptionUrl("sub://$encoded")
        )
    }

    @Test
    fun rejectsNonSubscriptionText() {
        assertNull(SubscriptionManager.normalizeSubscriptionUrl(""))
        assertNull(SubscriptionManager.normalizeSubscriptionUrl("   "))
        // A single config link is not a subscription.
        assertNull(SubscriptionManager.normalizeSubscriptionUrl("vless://uuid@host:443#x"))
        // A pasted blob containing a URL is not a single subscription URL either.
        assertNull(SubscriptionManager.normalizeSubscriptionUrl("see https://a.example/sub for details"))
        assertNull(SubscriptionManager.normalizeSubscriptionUrl("ftp://host/file"))
    }

    @Test
    fun looksLikeSubscriptionMatchesNormalisation() {
        assertTrue(SubscriptionManager.looksLikeSubscription("https://p.example/sub"))
        assertTrue(!SubscriptionManager.looksLikeSubscription("vless://uuid@host:443"))
    }

    @Test
    fun dueForUpdateSelectsOnlyStaleEnabledSubs() {
        val now = 10_000_000L
        val fresh = Subscription(id = "1", name = "fresh", url = "https://a", lastUpdatedMs = now - 60_000)
        val stale = Subscription(id = "2", name = "stale", url = "https://b", lastUpdatedMs = now - 2L * 24 * 60 * 60 * 1000)
        val disabled = stale.copy(id = "3", enabled = false)
        val manual = stale.copy(id = "4", updateIntervalMinutes = 0)

        val due = SubscriptionManager.dueForUpdate(listOf(fresh, stale, disabled, manual), now)
        assertEquals(listOf("2"), due.map { it.id })
    }

    @Test
    fun refreshFailureKeepsRecordAndReportsError() {
        // Unroutable host: the fetch must fail without throwing out of refresh().
        val sub = Subscription(id = "1", name = "n", url = "https://127.0.0.1:1/sub", lastUpdatedMs = 42)
        val result = SubscriptionManager.refresh(sub, nowMs = 999)
        assertTrue(!result.isSuccess)
        assertTrue(result.entries.isEmpty())
        // lastUpdated must NOT advance on failure, and the error is kept for the UI.
        assertEquals(42, result.subscription.lastUpdatedMs)
        assertTrue(result.subscription.lastError.isNotBlank())
    }
}
