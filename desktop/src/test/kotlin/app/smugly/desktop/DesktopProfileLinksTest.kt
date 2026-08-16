package app.smugly.desktop

import app.smugly.Config
import app.smugly.defaultConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopProfileLinksTest {

    @Test
    fun cdnfu_query_link_is_imported() {
        val uri = "cdnfu://import?downlink=stream&hostheader=cdn.example.com&name=Spain" +
            "&psk=secret&url=https%3A%2F%2F203.0.113.20%2F&xhttp=cookie"
        val p = DesktopProfileLinks.parse(uri)
        assertNotNull(p)
        assertEquals(Config.TunnelProtocol.CDNFU, p.config.protocol)
        assertEquals("https://203.0.113.20/", p.config.cdnfuUrl)
        assertEquals("cdn.example.com", p.config.cdnfuHost)
        assertEquals("secret", p.config.cdnfuPsk)
        assertEquals("cookie", p.config.cdnfuXhttpPlacement)
        assertEquals("stream", p.config.cdnfuDownlinkMode)
        assertEquals("Spain", p.name)
    }

    @Test
    fun s3fu_query_link_is_imported() {
        val uri = "s3fu://import?endpoint=https%3A%2F%2Fs3.example.com&bucket=bkt" +
            "&accesskey=AK&secretkey=SK&prefix=s3fu_abc&psk=deadbeef&name=Russia"
        val p = DesktopProfileLinks.parse(uri)
        assertNotNull(p)
        assertEquals(Config.TunnelProtocol.S3FU, p.config.protocol)
        assertEquals("https://s3.example.com", p.config.s3Endpoint)
        assertEquals("bkt", p.config.s3Bucket)
        assertEquals("AK", p.config.s3AccessKey)
        assertEquals("SK", p.config.s3SecretKey)
        assertEquals("s3fu_abc", p.config.s3Prefix)
        assertEquals("deadbeef", p.config.s3Psk)
        assertEquals("Russia", p.name)
    }

    @Test
    fun slipstream_query_link_is_imported() {
        val uri = "slipstream://import?domain=ee.example.com&resolvermode=auto" +
            "&username=slipstream&password=secret&name=Estonia"
        val p = DesktopProfileLinks.parse(uri, base = defaultConfig(mode = Config.Mode.PROXY))
        assertNotNull(p)
        assertEquals(Config.TunnelProtocol.SLIPSTREAM, p.config.protocol)
        assertEquals("ee.example.com", p.config.domain)
        assertEquals(Config.ResolverMode.AUTO, p.config.resolverMode)
        assertEquals(Config.AuthMode.LOGIN_PASSWORD, p.config.authMode)
        assertEquals("slipstream", p.config.username)
        assertEquals("secret", p.config.password)
        assertEquals("Estonia", p.name)
    }

    @Test
    fun preferred_name_wins_over_query_name() {
        val p = DesktopProfileLinks.parse(
            "cdnfu://import?url=https://edge.example&psk=x&name=FromQuery",
            preferredName = "FromPanel"
        )
        assertNotNull(p)
        assertEquals("FromPanel", p.name)
    }

    @Test
    fun toml_query_param_is_url_decoded() {
        val toml = "url  = \"https://edge.example/\"\npsk  = \"abc\""
        val encoded = java.net.URLEncoder.encode(toml, "UTF-8")
        val p = DesktopProfileLinks.parse("cdnfu://import?url=https://edge.example&psk=abc&toml=$encoded")
        assertNotNull(p)
        assertTrue(p.config.cdnfuToml.contains("url  ="), p.config.cdnfuToml)
        assertTrue(p.config.cdnfuToml.contains("https://edge.example/"), p.config.cdnfuToml)
    }
}
