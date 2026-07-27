package app.slipnet.tunnel

import app.slipnet.tunnel.SocksProtocol.ParseResult
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SocksProtocolTest {

    private fun <T> ok(r: ParseResult<T>): ParseResult.Ok<T> {
        assertTrue(r is ParseResult.Ok, "expected Ok, got $r")
        @Suppress("UNCHECKED_CAST")
        return r as ParseResult.Ok<T>
    }

    @Test
    fun greeting_needs_more_until_all_methods_present() {
        assertTrue(SocksProtocol.parseClientGreeting(byteArrayOf(0x05), 1) is ParseResult.NeedMore)
        assertTrue(SocksProtocol.parseClientGreeting(byteArrayOf(0x05, 0x02, 0x00), 3) is ParseResult.NeedMore)
    }

    @Test
    fun greeting_parses_methods_and_consumes_exact_length() {
        val buf = byteArrayOf(0x05, 0x02, 0x00, 0x02, 0x7F)
        val r = ok(SocksProtocol.parseClientGreeting(buf, 4))
        assertContentEquals(byteArrayOf(0x00, 0x02), r.value)
        assertEquals(4, r.consumed)
    }

    @Test
    fun greeting_rejects_wrong_version() {
        assertTrue(SocksProtocol.parseClientGreeting(byteArrayOf(0x04, 0x01, 0x00), 3) is ParseResult.Bad)
    }

    @Test
    fun auth_partial_then_complete() {
        val full = byteArrayOf(
            0x01, 3, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(),
            2, 'x'.code.toByte(), 'y'.code.toByte()
        )
        for (n in 0 until full.size) {
            assertTrue(
                SocksProtocol.parseClientAuth(full, n) is ParseResult.NeedMore,
                "len=$n should NeedMore"
            )
        }
        val r = ok(SocksProtocol.parseClientAuth(full, full.size))
        assertEquals("abc" to "xy", r.value)
        assertEquals(full.size, r.consumed)
    }

    @Test
    fun request_ipv4() {
        val buf = byteArrayOf(0x05, 0x01, 0x00, 0x01, 1, 2, 3, 4, 0x01, 0xBB.toByte())
        val r = ok(SocksProtocol.parseClientRequest(buf, buf.size))
        assertEquals(0x01, r.value.cmd)
        assertContentEquals(byteArrayOf(0x01, 1, 2, 3, 4), r.value.rawAddr)
        assertContentEquals(byteArrayOf(0x01, 0xBB.toByte()), r.value.portBytes)
        assertEquals("1.2.3.4", r.value.host)
        assertEquals(10, r.consumed)
    }

    @Test
    fun request_domain() {
        val host = "example.com"
        val buf = byteArrayOf(0x05, 0x01, 0x00, 0x03, host.length.toByte()) +
            host.encodeToByteArray() + byteArrayOf(0x00, 0x50)
        assertTrue(SocksProtocol.parseClientRequest(buf, 6) is ParseResult.NeedMore)
        val r = ok(SocksProtocol.parseClientRequest(buf, buf.size))
        assertEquals(host, r.value.host)
        assertEquals(0x50, ((r.value.portBytes[0].toInt() and 0xFF) shl 8) or (r.value.portBytes[1].toInt() and 0xFF))
    }

    @Test
    fun client_reply_shape() {
        val reply = SocksProtocol.clientReply(0)
        assertEquals(10, reply.size)
        assertEquals(0x05, reply[0].toInt() and 0xFF)
        assertEquals(0, reply[1].toInt())
    }

    @Test
    fun upstream_auth_roundtrip_shape() {
        val frame = SocksProtocol.upstreamAuth("user", "pass")
        assertEquals(1, frame[0].toInt())
        assertEquals(4, frame[1].toInt())
        val r = ok(SocksProtocol.parseClientAuth(frame, frame.size))
        assertEquals("user" to "pass", r.value)
    }
}
