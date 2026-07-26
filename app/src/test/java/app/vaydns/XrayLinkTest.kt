package app.vaydns

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the vless:// -> Xray JSON translation: the parser's query-parameter
 * vocabulary (which follows v2rayNG's), and the emitted config's shape against
 * Xray-core's own infra/conf schema (rawSettings/wsSettings/realitySettings/...).
 *
 * Runs under Robolectric because the builder uses android's org.json.
 */
@RunWith(RobolectricTestRunner::class)
class XrayLinkTest {

    private fun outbound(json: String): JSONObject =
        JSONObject(json).getJSONArray("outbounds").getJSONObject(0)

    private fun stream(json: String): JSONObject =
        outbound(json).getJSONObject("streamSettings")

    // -- parsing --

    @Test
    fun parses_reality_link_with_all_common_params() {
        val link = VlessLinkParser.parse(
            "vless://d4d6d4d6-0000-4000-8000-abcdefabcdef@example.org:443" +
                "?type=tcp&security=reality&pbk=PUBKEY123&sid=ab12&spx=%2F&fp=chrome" +
                "&sni=www.microsoft.com&flow=xtls-rprx-vision#My%20Server"
        )!!

        assertEquals("My Server", link.remarks)
        assertEquals("example.org", link.server)
        assertEquals(443, link.port)
        assertEquals("d4d6d4d6-0000-4000-8000-abcdefabcdef", link.uuid)
        assertEquals("reality", link.security)
        assertEquals("PUBKEY123", link.publicKey)
        assertEquals("ab12", link.shortId)
        assertEquals("/", link.spiderX)
        assertEquals("chrome", link.fingerprint)
        assertEquals("www.microsoft.com", link.sni)
        assertEquals("xtls-rprx-vision", link.flow)
        assertEquals("none", link.encryption)
    }

    @Test
    fun parses_ipv6_host_and_ws_transport() {
        val link = VlessLinkParser.parse(
            "vless://uuid-1@[2001:db8::1]:8443?type=ws&security=tls&host=cdn.example.com&path=%2Fws#v6"
        )!!

        assertEquals("2001:db8::1", link.server)
        assertEquals(8443, link.port)
        assertEquals("ws", link.network)
        assertEquals("cdn.example.com", link.host)
        assertEquals("/ws", link.path)
    }

    @Test
    fun defaults_network_to_tcp_and_remarks_to_server() {
        val link = VlessLinkParser.parse("vless://uuid-1@1.2.3.4:443?security=tls")!!
        assertEquals("tcp", link.network)
        assertEquals("1.2.3.4", link.remarks)
    }

    @Test
    fun accepts_the_three_allow_insecure_spellings() {
        for (key in listOf("insecure", "allowInsecure", "allow_insecure")) {
            val link = VlessLinkParser.parse("vless://u@h.example:443?security=tls&$key=1")!!
            assertTrue("$key should set allowInsecure", link.allowInsecure)
        }
        assertFalse(VlessLinkParser.parse("vless://u@h.example:443?security=tls&insecure=0")!!.allowInsecure)
    }

    @Test
    fun rejects_malformed_links() {
        assertNull(VlessLinkParser.parse("vless://nouserinfo.example:443"))
        assertNull(VlessLinkParser.parse("vless://uuid@host-without-port"))
        assertNull(VlessLinkParser.parse("vless://uuid@host.example:70000"))
        assertNull(VlessLinkParser.parse("https://example.org"))
    }

    @Test
    fun finds_every_link_in_a_subscription_paste() {
        val text = """
            vless://a@one.example:443?security=tls#one
            some junk line
            vless://b@two.example:8443?security=tls#two
        """.trimIndent()
        val found = VlessLinkParser.findAll(text)
        assertEquals(2, found.size)
        assertEquals("one.example", VlessLinkParser.parse(found[0])!!.server)
        assertEquals("two.example", VlessLinkParser.parse(found[1])!!.server)
    }

    // -- config generation --

    @Test
    fun builds_a_socks_inbound_on_the_requested_port() {
        val link = VlessLinkParser.parse("vless://uuid-1@srv.example:443?security=tls")!!
        val inbound = JSONObject(XrayConfigBuilder.build(link, 1080))
            .getJSONArray("inbounds").getJSONObject(0)

        assertEquals("socks", inbound.getString("protocol"))
        assertEquals("127.0.0.1", inbound.getString("listen"))
        assertEquals(1080, inbound.getInt("port"))
        // hev bridges UDP via SOCKS5 UDP ASSOCIATE, which needs this on.
        assertTrue(inbound.getJSONObject("settings").getBoolean("udp"))
    }

    @Test
    fun builds_flat_vless_outbound_settings() {
        val link = VlessLinkParser.parse(
            "vless://uuid-1@srv.example:443?security=tls&flow=xtls-rprx-vision"
        )!!
        val settings = outbound(XrayConfigBuilder.build(link, 1080)).getJSONObject("settings")

        assertEquals("vless", outbound(XrayConfigBuilder.build(link, 1080)).getString("protocol"))
        assertEquals("srv.example", settings.getString("address"))
        assertEquals(443, settings.getInt("port"))
        assertEquals("uuid-1", settings.getString("id"))
        assertEquals("none", settings.getString("encryption"))
        assertEquals("xtls-rprx-vision", settings.getString("flow"))
    }

    @Test
    fun reality_params_go_into_realitySettings_not_tlsSettings() {
        val link = VlessLinkParser.parse(
            "vless://u@srv.example:443?security=reality&pbk=KEY&sid=ff&sni=www.apple.com&fp=chrome"
        )!!
        val stream = stream(XrayConfigBuilder.build(link, 1080))

        assertEquals("reality", stream.getString("security"))
        assertFalse(stream.has("tlsSettings"))
        val reality = stream.getJSONObject("realitySettings")
        assertEquals("KEY", reality.getString("publicKey"))
        assertEquals("ff", reality.getString("shortId"))
        assertEquals("www.apple.com", reality.getString("serverName"))
        assertEquals("chrome", reality.getString("fingerprint"))
    }

    @Test
    fun tls_alpn_is_split_into_an_array() {
        val link = VlessLinkParser.parse(
            "vless://u@srv.example:443?security=tls&alpn=h2%2Chttp%2F1.1"
        )!!
        val alpn = stream(XrayConfigBuilder.build(link, 1080))
            .getJSONObject("tlsSettings").getJSONArray("alpn")

        assertEquals(2, alpn.length())
        assertEquals("h2", alpn.getString(0))
        assertEquals("http/1.1", alpn.getString(1))
    }

    @Test
    fun sni_falls_back_to_the_ws_host_then_to_the_server_name() {
        val viaHost = VlessLinkParser.parse(
            "vless://u@1.2.3.4:443?type=ws&security=tls&host=cdn.example.com&path=%2F"
        )!!
        assertEquals(
            "cdn.example.com",
            stream(XrayConfigBuilder.build(viaHost, 1080)).getJSONObject("tlsSettings").getString("serverName")
        )

        val viaServer = VlessLinkParser.parse("vless://u@srv.example:443?security=tls")!!
        assertEquals(
            "srv.example",
            stream(XrayConfigBuilder.build(viaServer, 1080)).getJSONObject("tlsSettings").getString("serverName")
        )
    }

    @Test
    fun bare_ip_server_yields_no_sni() {
        val link = VlessLinkParser.parse("vless://u@1.2.3.4:443?security=tls")!!
        val tls = stream(XrayConfigBuilder.build(link, 1080)).getJSONObject("tlsSettings")
        assertFalse("an IP literal is not a valid SNI", tls.has("serverName"))
    }

    @Test
    fun pinned_certificate_disables_allowInsecure() {
        val link = VlessLinkParser.parse(
            "vless://u@srv.example:443?security=tls&insecure=1&pcs=abcdef"
        )!!
        val tls = stream(XrayConfigBuilder.build(link, 1080)).getJSONObject("tlsSettings")
        assertEquals("abcdef", tls.getString("pinnedPeerCertSha256"))
        assertFalse(tls.getBoolean("allowInsecure"))
    }

    @Test
    fun transports_land_in_their_own_settings_object() {
        val cases = mapOf(
            "ws" to "wsSettings",
            "httpupgrade" to "httpupgradeSettings",
            "xhttp" to "xhttpSettings",
            "grpc" to "grpcSettings",
            "kcp" to "kcpSettings"
        )
        for ((type, key) in cases) {
            val link = VlessLinkParser.parse("vless://u@srv.example:443?type=$type&security=tls")!!
            assertTrue("$type should emit $key", stream(XrayConfigBuilder.build(link, 1080)).has(key))
        }
        // tcp/raw uses rawSettings in current Xray, not the legacy tcpSettings.
        val raw = VlessLinkParser.parse("vless://u@srv.example:443?type=tcp&security=tls")!!
        assertTrue(stream(XrayConfigBuilder.build(raw, 1080)).has("rawSettings"))
    }

    @Test
    fun plaintext_link_emits_no_security_block() {
        val link = VlessLinkParser.parse("vless://u@srv.example:80?type=tcp")!!
        val stream = stream(XrayConfigBuilder.build(link, 1080))
        assertFalse(stream.has("security"))
        assertFalse(stream.has("tlsSettings"))
        assertFalse(stream.has("realitySettings"))
    }

    // -- port re-pinning / inspection helpers --

    @Test
    fun withSocksPort_repoints_an_existing_socks_inbound() {
        val link = VlessLinkParser.parse("vless://u@srv.example:443?security=tls")!!
        val moved = XrayConfigBuilder.withSocksPort(XrayConfigBuilder.build(link, 1080), 1099)
        assertEquals(1099, XrayConfigBuilder.socksPortOf(moved))
        // Still exactly one inbound -- re-pinning must not append a duplicate.
        assertEquals(1, JSONObject(moved).getJSONArray("inbounds").length())
    }

    @Test
    fun withSocksPort_injects_an_inbound_when_the_config_has_none() {
        val json = """{"outbounds":[{"protocol":"freedom"}]}"""
        val patched = XrayConfigBuilder.withSocksPort(json, 1080)
        assertEquals(1080, XrayConfigBuilder.socksPortOf(patched))
    }

    @Test
    fun withSocksPort_returns_unparseable_text_untouched() {
        assertEquals("not json at all", XrayConfigBuilder.withSocksPort("not json at all", 1080))
    }

    @Test
    fun describeServer_reads_flat_and_vnext_shapes() {
        val link = VlessLinkParser.parse("vless://u@srv.example:443?security=tls")!!
        assertEquals("srv.example:443", XrayConfigBuilder.describeServer(XrayConfigBuilder.build(link, 1080)))

        val vnext = """
            {"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"old.example","port":8443}]}}]}
        """.trimIndent()
        assertEquals("old.example:8443", XrayConfigBuilder.describeServer(vnext))
        assertNull(XrayConfigBuilder.describeServer("garbage"))
    }

    @Test
    fun blankTemplate_is_valid_json_with_a_socks_inbound() {
        val template = XrayConfigBuilder.blankTemplate(1080)
        assertEquals(1080, XrayConfigBuilder.socksPortOf(template))
        assertEquals("vless", outbound(template).getString("protocol"))
    }
}
