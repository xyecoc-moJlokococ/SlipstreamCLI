package app.smugly.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WindowsConnectionSettingsTest {

    private val bypass = listOf(
        "localhost", "127.*", "10.*",
        "172.16.*", "172.17.*", "192.168.*",
        "<local>", "127.0.0.1", "::1"
    ).joinToString(";")

    @Test
    fun encode_manual_proxy_has_no_pac_and_roundtrips() {
        val raw = WindowsConnectionSettings.encode(
            enabled = true,
            server = "127.0.0.1:1080",
            bypass = bypass,
            pacUrl = "",
            counter = 7
        )
        val parsed = assertNotNull(WindowsConnectionSettings.decode(raw))
        assertEquals(WindowsConnectionSettings.VERSION, parsed.version)
        assertEquals(7, parsed.counter)
        assertTrue(parsed.proxyEnabled)
        assertEquals(0, parsed.flags and WindowsConnectionSettings.FLAG_PAC)
        assertEquals(0, parsed.flags and WindowsConnectionSettings.FLAG_WPAD)
        assertEquals("127.0.0.1:1080", parsed.server)
        assertEquals(bypass, parsed.bypass)
        assertEquals("", parsed.pacUrl)
    }

    @Test
    fun leftover_pac_is_decoded_and_stripped_on_rewrite() {
        val dirty = WindowsConnectionSettings.encode(
            enabled = true,
            server = "127.0.0.1:1080",
            bypass = bypass,
            pacUrl = "http://127.0.0.1:10811/pac?t=638977310287752167",
            counter = 2036
        )
        val parsed = assertNotNull(WindowsConnectionSettings.decode(dirty))
        assertTrue(parsed.pacUrl.startsWith("http://127.0.0.1:10811/"))
        assertTrue(parsed.flags and WindowsConnectionSettings.FLAG_PAC != 0)

        val clean = WindowsConnectionSettings.encode(
            enabled = true,
            server = parsed.server,
            bypass = parsed.bypass,
            pacUrl = "",
            counter = WindowsConnectionSettings.nextCounter(dirty)
        )
        val rewritten = assertNotNull(WindowsConnectionSettings.decode(clean))
        assertEquals("", rewritten.pacUrl)
        assertEquals(0, rewritten.flags and WindowsConnectionSettings.FLAG_PAC)
        assertTrue(rewritten.proxyEnabled)
        assertEquals(2037, rewritten.counter)
    }

    @Test
    fun ipv6_loopback_is_stripped_from_bypass() {
        val cleaned = WindowsSystemProxy.sanitizeBypassForWinInet(
            "localhost;127.0.0.1;::1;<local>;2001:db8::1"
        )
        assertEquals("localhost;127.0.0.1;<local>", cleaned)
        assertFalse(cleaned.contains("::"))
    }

    @Test
    fun disabled_encode_clears_proxy_flag() {
        val raw = WindowsConnectionSettings.encode(
            enabled = false,
            server = "127.0.0.1:1080",
            bypass = "<local>",
            pacUrl = ""
        )
        val parsed = assertNotNull(WindowsConnectionSettings.decode(raw))
        assertFalse(parsed.proxyEnabled)
        assertEquals("", parsed.pacUrl)
    }
}
