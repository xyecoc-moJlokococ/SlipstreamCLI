package app.smugly

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * These cover the migration path, which is the risky part: a profile made before the
 * editor existed has no config text, and the app has to derive one that behaves exactly
 * as the old per-parameter plumbing did — on a phone nobody can debug from here.
 */
class TunnelTomlTest {

    private fun base() = Config(
        domain = "",
        resolverHost = "",
        resolverPort = 53,
        resolverMode = Config.ResolverMode.AUTO,
        resolverTransport = Config.ResolverTransport.UDP,
        resolverPathMode = Config.ResolverPathMode.AUTHORITATIVE,
        listenPort = 1080,
        mode = Config.Mode.VPN,
        authMode = Config.AuthMode.NO_AUTH,
        username = "",
        password = ""
    )

    @Test
    fun s3fu_config_is_derived_from_the_legacy_fields() {
        val c = base().copy(
            protocol = Config.TunnelProtocol.S3FU,
            s3Endpoint = "https://s3.example.com",
            s3Bucket = "bkt",
            s3AccessKey = "ak",
            s3SecretKey = "sk",
            s3Prefix = "s3fu-madrid",
            s3Psk = "ff00"
        )
        val toml = c.effectiveS3fuToml()
        for (want in listOf(
            "endpoint   = \"https://s3.example.com\"",
            "bucket     = \"bkt\"",
            "prefix     = \"s3fu-madrid\"",
            "psk        = \"ff00\"",
            // A client that deletes objects the peer has not read yet wedges the session.
            "allow_delete = false"
        )) {
            assertTrue(toml.contains(want), "missing $want in:\n$toml")
        }
    }

    @Test
    fun edited_text_wins_over_the_fields() {
        val c = base().copy(s3Endpoint = "https://ignored", s3fuToml = "endpoint = \"https://typed\"\n")
        assertEquals("endpoint = \"https://typed\"\n", c.effectiveS3fuToml())
    }

    /**
     * "auto" used to be coerced by the service before it reached the engine, because on a
     * phone it left GET/query defaults that fight cookie stealth. Migrating a profile must
     * not quietly change its behaviour on the wire.
     */
    @Test
    fun cdnfu_auto_knobs_are_coerced_to_the_phone_proven_shape() {
        val c = base().copy(
            protocol = Config.TunnelProtocol.CDNFU,
            cdnfuUrl = "http://192.0.2.1/",
            cdnfuHost = "cdn.example.com",
            cdnfuPsk = "secret",
            cdnfuUplinkMethod = "auto",
            cdnfuUplinkPath = "",
            cdnfuUplinkData = "AUTO",
            cdnfuXhttpPlacement = "auto",
            cdnfuDownlinkMode = "auto"
        )
        val toml = c.effectiveCdnfuToml()
        for (want in listOf(
            "method = \"POST\"",
            "path   = \"api\"",
            "data   = \"body\"",
            "session_placement = \"cookie\"",
            "mode = \"stream\"",
            "url  = \"http://192.0.2.1/\"",
            "host = \"cdn.example.com\"",
            // h1 makes buffering edges hold the whole streaming downlink.
            "http1_only = false"
        )) {
            assertTrue(toml.contains(want), "missing $want in:\n$toml")
        }
    }

    @Test
    fun cdnfu_multipath_zero_becomes_one_path() {
        val c = base().copy(cdnfuUrl = "https://e.example", cdnfuMultipath = 0)
        assertTrue(c.effectiveCdnfuToml().contains("paths = 1"))
    }

    /** Values with quotes must not break out of the string and corrupt the config. */
    @Test
    fun values_are_escaped() {
        val c = base().copy(s3Bucket = "we\"ird\\", s3Endpoint = "https://e")
        assertTrue(c.effectiveS3fuToml().contains("""bucket     = "we\"ird\\""""))
    }

    @Test
    fun forced_key_replaces_the_configs_own_value() {
        val toml = "listen = \"127.0.0.1:9\"\nurl = \"https://e\"\n[pool]\nlisten = \"in-a-table\"\n"
        val out = TunnelToml.withForcedTopLevelKey(toml, "listen", "127.0.0.1:1080")

        assertTrue(out.startsWith("listen = \"127.0.0.1:1080\"\n"), out)
        // A duplicate key is a parse error, so the original has to be commented out.
        assertFalse(out.contains("\nlisten = \"127.0.0.1:9\""), out)
        assertTrue(out.contains("# listen = \"127.0.0.1:9\""), out)
        // A same-named key inside a table is a different key.
        assertTrue(out.contains("\nlisten = \"in-a-table\""), out)
        assertTrue(out.contains("url = \"https://e\""), out)
    }

    @Test
    fun forced_key_is_added_when_the_config_has_none() {
        val out = TunnelToml.withForcedTopLevelKey("url = \"https://e\"\n", "listen", "127.0.0.1:1")
        assertTrue(out.startsWith("listen = \"127.0.0.1:1\"\n"), out)
        assertTrue(out.contains("url = \"https://e\""), out)
    }
}
