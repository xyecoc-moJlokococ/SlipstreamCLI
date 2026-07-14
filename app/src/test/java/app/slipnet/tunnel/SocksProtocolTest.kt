package app.slipnet.tunnel

import app.slipnet.tunnel.SocksProtocol.ParseResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the incremental SOCKS5 parsers/builders that replace the old blocking handshake. The key
 * property under test is partial-read safety: every parser must return NeedMore (never throw, never
 * over-read) until the full message has accumulated, because a non-blocking selector feeds bytes in
 * arbitrary chunks.
 */
class SocksProtocolTest {

    private fun <T> ok(r: ParseResult<T>): ParseResult.Ok<T> {
        assertTrue("expected Ok, got $r", r is ParseResult.Ok)
        @Suppress("UNCHECKED_CAST")
        return r as ParseResult.Ok<T>
    }

    // ---- client greeting ----

    @Test
    fun greeting_needs_more_until_all_methods_present() {
        assertTrue(SocksProtocol.parseClientGreeting(byteArrayOf(0x05), 1) is ParseResult.NeedMore)
        // ver, nmethods=2, but only 1 method byte so far
        assertTrue(SocksProtocol.parseClientGreeting(byteArrayOf(0x05, 0x02, 0x00), 3) is ParseResult.NeedMore)
    }

    @Test
    fun greeting_parses_methods_and_consumes_exact_length() {
        val buf = byteArrayOf(0x05, 0x02, 0x00, 0x02, 0x7F /* trailing garbage */)
        val r = ok(SocksProtocol.parseClientGreeting(buf, 4))
        assertArrayEquals(byteArrayOf(0x00, 0x02), r.value)
        assertEquals(4, r.consumed)
    }

    @Test
    fun greeting_rejects_wrong_version() {
        assertTrue(SocksProtocol.parseClientGreeting(byteArrayOf(0x04, 0x01, 0x00), 3) is ParseResult.Bad)
    }

    // ---- client auth ----

    @Test
    fun auth_partial_then_complete() {
        // ver=1, ulen=3 "abc", plen=2 "xy"
        val full = byteArrayOf(0x01, 3, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 2, 'x'.code.toByte(), 'y'.code.toByte())
        for (n in 0 until full.size) {
            assertTrue("len=$n should NeedMore", SocksProtocol.parseClientAuth(full, n) is ParseResult.NeedMore)
        }
        val r = ok(SocksProtocol.parseClientAuth(full, full.size))
        assertEquals("abc" to "xy", r.value)
        assertEquals(full.size, r.consumed)
    }

    // ---- client request: all address types ----

    @Test
    fun request_ipv4() {
        // ver,cmd=1,rsv,atyp=1, 1.2.3.4, port 443
        val buf = byteArrayOf(0x05, 0x01, 0x00, 0x01, 1, 2, 3, 4, 0x01, 0xBB.toByte())
        val r = ok(SocksProtocol.parseClientRequest(buf, buf.size))
        assertEquals(0x01, r.value.cmd)
        assertArrayEquals(byteArrayOf(0x01, 1, 2, 3, 4), r.value.rawAddr)
        assertArrayEquals(byteArrayOf(0x01, 0xBB.toByte()), r.value.portBytes)
        assertEquals("1.2.3.4", r.value.host)
        assertEquals(10, r.consumed)
    }

    @Test
    fun request_domain() {
        val host = "example.com"
        val buf = byteArrayOf(0x05, 0x01, 0x00, 0x03, host.length.toByte()) +
            host.toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00, 0x50)
        // NeedMore before the full domain+port arrives
        assertTrue(SocksProtocol.parseClientRequest(buf, 6) is ParseResult.NeedMore)
        val r = ok(SocksProtocol.parseClientRequest(buf, buf.size))
        assertEquals("example.com", r.value.host)
        assertArrayEquals(byteArrayOf(0x03, host.length.toByte()) + host.toByteArray(Charsets.US_ASCII), r.value.rawAddr)
        assertEquals(buf.size, r.consumed)
    }

    @Test
    fun request_fwd_udp_command_preserved() {
        val buf = byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0)
        val r = ok(SocksProtocol.parseClientRequest(buf, buf.size))
        assertEquals(0x05, r.value.cmd)
    }

    @Test
    fun request_ipv6_length() {
        val buf = byteArrayOf(0x05, 0x01, 0x00, 0x04) + ByteArray(16) + byteArrayOf(0x01, 0xBB.toByte())
        assertTrue(SocksProtocol.parseClientRequest(buf, 20) is ParseResult.NeedMore)
        val r = ok(SocksProtocol.parseClientRequest(buf, buf.size))
        assertEquals(22, r.consumed)
        assertEquals(17, r.value.rawAddr.size)
    }

    // ---- upstream builders + reply parsers round-trip ----

    @Test
    fun upstream_greeting_reflects_auth() {
        assertArrayEquals(byteArrayOf(0x05, 0x01, 0x00), SocksProtocol.upstreamGreeting(false))
        assertArrayEquals(byteArrayOf(0x05, 0x01, 0x02), SocksProtocol.upstreamGreeting(true))
    }

    @Test
    fun upstream_greeting_reply_selects_method_or_rejects() {
        assertEquals(0x02, ok(SocksProtocol.parseUpstreamGreetingReply(byteArrayOf(0x05, 0x02), 2)).value)
        assertTrue(SocksProtocol.parseUpstreamGreetingReply(byteArrayOf(0x05, 0xFF.toByte()), 2) is ParseResult.Bad)
        assertTrue(SocksProtocol.parseUpstreamGreetingReply(byteArrayOf(0x05), 1) is ParseResult.NeedMore)
    }

    @Test
    fun upstream_auth_frame_and_reply() {
        val frame = SocksProtocol.upstreamAuth("user", "pass")
        assertArrayEquals(
            byteArrayOf(0x01, 4) + "user".toByteArray() + byteArrayOf(4) + "pass".toByteArray(),
            frame
        )
        assertTrue(SocksProtocol.parseUpstreamAuthReply(byteArrayOf(0x01, 0x00), 2) is ParseResult.Ok)
        assertTrue(SocksProtocol.parseUpstreamAuthReply(byteArrayOf(0x01, 0x01), 2) is ParseResult.Bad)
    }

    @Test
    fun upstream_command_reply_consumes_bind_address() {
        // ver, rep=0, rsv, atyp=1, bind 0.0.0.0:0  -> total 10
        val ipv4 = byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)
        assertEquals(10, ok(SocksProtocol.parseUpstreamCommandReply(ipv4, ipv4.size)).consumed)
        // domain bind
        val dom = byteArrayOf(0x05, 0x00, 0x00, 0x03, 3, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 0, 0)
        assertEquals(10, ok(SocksProtocol.parseUpstreamCommandReply(dom, dom.size)).consumed)
        // partial
        assertTrue(SocksProtocol.parseUpstreamCommandReply(ipv4, 4) is ParseResult.NeedMore)
        // rejected
        assertTrue(SocksProtocol.parseUpstreamCommandReply(byteArrayOf(0x05, 0x05, 0, 0x01), 4) is ParseResult.Bad)
    }

    @Test
    fun client_reply_shape() {
        assertArrayEquals(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0), SocksProtocol.clientReply(0x00))
        assertArrayEquals(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0), SocksProtocol.clientReply(0x05))
    }
}
