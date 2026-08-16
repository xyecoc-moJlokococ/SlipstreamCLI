package app.smugly.desktop

import app.smugly.Config
import app.smugly.DnsResolverPool
import app.smugly.defaultConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.json.JSONObject

class DesktopEngineConfigsTest {

    private fun slipstreamProfile(): Config = defaultConfig().copy(
        domain = "ee.example.com",
        resolverHost = "1.2.3.4",
        resolverPort = 53,
        resolverMode = Config.ResolverMode.MANUAL,
        resolverTransport = Config.ResolverTransport.TCP,
        resolverPathMode = Config.ResolverPathMode.AUTHORITATIVE,
        dnsQueryType = 65,
        dnsLabelLength = 50,
        dnsLabelLengthJitter = 3,
        maxPollQps = 700,
        base64uEncoding = true
    )

    @Test
    fun slipstream_json_carries_profile_knobs_and_forced_listen() {
        val json = JSONObject(DesktopEngineConfigs.slipstream(slipstreamProfile(), 1081))
        assertEquals("127.0.0.1", json.getString("tcp_listen_host"))
        assertEquals(1081, json.getInt("tcp_listen_port"))
        assertEquals("ee.example.com", json.getString("domain"))
        assertEquals("tcp", json.getString("resolver_transport"))
        assertEquals(65, json.getInt("dns_query_type"))
        assertEquals(50, json.getInt("dns_label_length"))
        assertEquals(3, json.getInt("dns_label_length_jitter"))
        assertEquals(700, json.getInt("max_poll_qps"))
        assertTrue(json.getBoolean("base64u_encoding"))
        assertEquals(DesktopEngineConfigs.SLIPSTREAM_KEEP_ALIVE_MS, json.getInt("keep_alive_interval"))
        val resolvers = json.getJSONArray("resolvers")
        assertEquals(1, resolvers.length())
        assertEquals("1.2.3.4:53", resolvers.getJSONObject(0).getString("addr"))
        assertTrue(resolvers.getJSONObject(0).getBoolean("authoritative"))
    }

    @Test
    fun slipstream_tcp_keeps_only_the_first_resolver() {
        val c = slipstreamProfile().copy(resolverHost = "1.1.1.1, 8.8.8.8")
        val json = JSONObject(DesktopEngineConfigs.slipstream(c, 1081))
        assertEquals(1, json.getJSONArray("resolvers").length())
        assertEquals("1.1.1.1:53", json.getJSONArray("resolvers").getJSONObject(0).getString("addr"))
    }

    @Test
    fun slipstream_udp_keeps_every_manual_resolver() {
        val c = slipstreamProfile().copy(
            resolverHost = "1.1.1.1;8.8.8.8\n9.9.9.9",
            resolverTransport = Config.ResolverTransport.UDP
        )
        val json = JSONObject(DesktopEngineConfigs.slipstream(c, 1081))
        val addrs = (0 until json.getJSONArray("resolvers").length()).map {
            json.getJSONArray("resolvers").getJSONObject(it).getString("addr")
        }
        assertEquals(listOf("1.1.1.1:53", "8.8.8.8:53", "9.9.9.9:53"), addrs)
    }

    @Test
    fun slipstream_auto_skips_local_sentinel() {
        val c = slipstreamProfile().copy(
            resolverMode = Config.ResolverMode.AUTO,
            resolverHost = "",
            resolverTransport = Config.ResolverTransport.UDP
        )
        val pool = "${DnsResolverPool.LOCAL_SENTINEL}\n82.151.127.188\n188.0.190.47"
        val hosts = DesktopEngineConfigs.slipstreamResolverHosts(c, pool)
        assertEquals(listOf("82.151.127.188", "188.0.190.47"), hosts)
        assertFalse(hosts.any { DnsResolverPool.isLocalSentinel(it) })
    }

    @Test
    fun slipstream_auto_with_empty_pool_fails_loudly() {
        val c = slipstreamProfile().copy(
            resolverMode = Config.ResolverMode.AUTO,
            resolverHost = ""
        )
        val err = assertFailsWith<IllegalArgumentException> {
            DesktopEngineConfigs.slipstream(c, 1081, DnsResolverPool.LOCAL_SENTINEL)
        }
        assertTrue(err.message.orEmpty().contains("auto-DNS"), err.message)
    }

    @Test
    fun parse_manual_hosts_accepts_mixed_separators() {
        assertEquals(
            listOf("1.1.1.1", "8.8.8.8", "9.9.9.9"),
            DnsResolverPool.parseManualHosts("1.1.1.1, 8.8.8.8;9.9.9.9")
        )
    }
}
