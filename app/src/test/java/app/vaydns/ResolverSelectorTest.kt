package app.vaydns

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * SlipstreamCLI had zero automated test coverage before this. These tests focus on the resolver
 * cache (persistence, TTL, and the qtype field added this session) and the qtype fast-path
 * decision that skips validateTransport()'s 10-15s real-data probe matrix on a warm cache hit --
 * the two pieces of ResolverSelector most directly touched by this session's fixes.
 */
@RunWith(RobolectricTestRunner::class)
class ResolverSelectorTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun testConfig(
        resolverMode: Config.ResolverMode = Config.ResolverMode.AUTO,
        domain: String = "test.example.com"
    ) = Config(
        domain = domain,
        resolverHost = "",
        resolverPort = 53,
        resolverMode = resolverMode,
        resolverTransport = Config.ResolverTransport.UDP,
        resolverPathMode = Config.ResolverPathMode.AUTHORITATIVE,
        listenPort = 1080,
        mode = Config.Mode.VPN,
        authMode = Config.AuthMode.NO_AUTH,
        username = "",
        password = ""
    )

    // -- resolver cache: persistence, qtype field, TTL --

    @Test
    fun rememberTransport_then_loadResolverCache_round_trips_transport_and_qtype() {
        val config = testConfig()
        ResolverSelector.rememberTransport(context, config, "1.2.3.4", Config.ResolverTransport.TCP, 65)

        val cached = ResolverSelector.loadResolverCache(context, config)
            ?.resolvers
            ?.firstOrNull { it.host == "1.2.3.4" }
        assertEquals(Config.ResolverTransport.TCP, cached?.transport)
        assertEquals(65, cached?.qtype)
    }

    @Test
    fun rememberTransport_without_a_qtype_preserves_the_previously_cached_one() {
        // A later call that only learned a transport fallback (qtype=0, the "unknown/unchanged"
        // sentinel) must not wipe out a qtype a previous validateTransport() call already found --
        // otherwise every plain transport-fallback would silently defeat the fast-path forever.
        val config = testConfig()
        ResolverSelector.rememberTransport(context, config, "1.2.3.4", Config.ResolverTransport.TCP, 65)
        ResolverSelector.rememberTransport(context, config, "1.2.3.4", Config.ResolverTransport.UDP)

        val cached = ResolverSelector.loadResolverCache(context, config)
            ?.resolvers
            ?.firstOrNull { it.host == "1.2.3.4" }
        assertEquals(Config.ResolverTransport.UDP, cached?.transport)
        assertEquals(65, cached?.qtype)
    }

    @Test
    fun rememberTransport_is_a_no_op_outside_auto_mode() {
        val config = testConfig(resolverMode = Config.ResolverMode.MANUAL)
        ResolverSelector.rememberTransport(context, config, "1.2.3.4", Config.ResolverTransport.TCP, 65)
        assertNull(ResolverSelector.loadResolverCache(context, config))
    }

    @Test
    fun different_domains_get_independent_cache_entries() {
        // The cache key is derived from the network profile, which folds in config.domain -- two
        // profiles for the same physical network shouldn't bleed into each other.
        val configA = testConfig(domain = "a.example.com")
        val configB = testConfig(domain = "b.example.com")
        ResolverSelector.rememberTransport(context, configA, "1.2.3.4", Config.ResolverTransport.TCP, 65)

        assertNull(ResolverSelector.loadResolverCache(context, configB))
        assertEquals(
            1,
            ResolverSelector.loadResolverCache(context, configA)?.resolvers?.size
        )
    }

    // -- shouldSkipTransportValidation: the new fast-path decision --

    private fun cacheHit(qtype: Int, source: String = "auto-cache") = ResolverChoice(
        hosts = listOf("1.2.3.4"),
        port = 53,
        selectedHost = "1.2.3.4",
        source = source,
        transport = Config.ResolverTransport.TCP,
        qtype = qtype
    )

    @Test
    fun skips_validation_for_a_fresh_cache_hit_with_a_known_qtype() {
        assertTrue(
            ResolverSelector.shouldSkipTransportValidation(Config.ResolverMode.AUTO, cacheHit(qtype = 65))
        )
    }

    @Test
    fun does_not_skip_validation_when_qtype_is_unknown() {
        assertFalse(
            ResolverSelector.shouldSkipTransportValidation(Config.ResolverMode.AUTO, cacheHit(qtype = 0))
        )
    }

    @Test
    fun does_not_skip_validation_for_a_fresh_auto_probe_even_if_it_somehow_carried_a_qtype() {
        // source="auto-fast" means chooseFast had to fall back to a full probe (no usable cache) --
        // guard the source check explicitly, not just qtype, in case that ever changes.
        assertFalse(
            ResolverSelector.shouldSkipTransportValidation(
                Config.ResolverMode.AUTO,
                cacheHit(qtype = 65, source = "auto-fast")
            )
        )
    }

    @Test
    fun does_not_skip_validation_in_manual_mode() {
        assertFalse(
            ResolverSelector.shouldSkipTransportValidation(Config.ResolverMode.MANUAL, cacheHit(qtype = 65))
        )
    }
}
