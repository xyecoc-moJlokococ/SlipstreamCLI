package app.smugly

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigJsonTest {
    @Test
    fun roundtrip_preserves_fields() {
        val original = defaultConfig(listenPort = 2080, mode = Config.Mode.PROXY).copy(
            domain = "t.example.com",
            resolverHost = "1.2.3.4",
            resolverPort = 53,
            dnsQueryType = 65,
            maxDataQps = 900,
            base64uEncoding = true,
            protocol = Config.TunnelProtocol.SLIPSTREAM
        )
        val json = ConfigJson.configToJson(original)
        val restored = ConfigJson.configFromJson(json)
        assertEquals(original, restored)
    }

    @Test
    fun profile_roundtrip() {
        val profile = ConfigProfile("abc", "My profile", defaultConfig().copy(domain = "x.test"))
        val restored = ConfigJson.profileFromJson(ConfigJson.profileToJson(profile))
        assertEquals(profile.id, restored.id)
        assertEquals(profile.name, restored.name)
        assertEquals(profile.config.domain, restored.config.domain)
    }

    @Test
    fun s3fu_defaults() {
        val c = ConfigJson.configFromJson(org.json.JSONObject("""{"protocol":"S3FU","s3Bucket":"b"}"""))
        assertEquals(Config.TunnelProtocol.S3FU, c.protocol)
        assertEquals("b", c.s3Bucket)
        assertEquals("s3fu", c.s3Prefix)
        assertTrue(c.domain.isEmpty())
    }

    @Test
    fun cdnfu_roundtrip() {
        val original = defaultConfig().copy(
            protocol = Config.TunnelProtocol.CDNFU,
            cdnfuUrl = "https://jarvis-media.ru/",
            cdnfuPsk = "cdnfu-lab-jarvis-2026",
            cdnfuMimic = "mixed",
            cdnfuUplinkMethod = "POST",
            cdnfuUplinkPath = "api",
            cdnfuUplinkData = "body",
            cdnfuXhttpPlacement = "cookie",
            cdnfuDownlinkMode = "poll",
            cdnfuMultipath = 4
        )
        val restored = ConfigJson.configFromJson(ConfigJson.configToJson(original))
        assertEquals(original, restored)
    }
}
