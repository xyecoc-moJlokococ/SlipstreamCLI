package app.smugly.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpProxyPacTest {
    @Test
    fun origin_form_pac_is_served_locally() {
        val req = "GET /smugly.pac HTTP/1.1\r\nHost: 127.0.0.1:1080\r\n\r\n".toByteArray()
        val r = HttpProxyProtocol.parse(req, req.size)
        assertTrue(r is HttpProxyProtocol.Result.Local)
        val body = String((r as HttpProxyProtocol.Result.Local).status)
        assertTrue(body.contains("FindProxyForURL"), body)
        assertTrue(body.contains("PROXY 127.0.0.1:1080"), body)
        assertTrue(body.startsWith("HTTP/1.1 200"))
    }

    @Test
    fun absolute_form_pac_is_served_locally() {
        val req = "GET http://127.0.0.1:1080/smugly.pac HTTP/1.1\r\n\r\n".toByteArray()
        val r = HttpProxyProtocol.parse(req, req.size)
        assertTrue(r is HttpProxyProtocol.Result.Local)
    }

    @Test
    fun connect_is_unchanged() {
        val req = "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n".toByteArray()
        val r = HttpProxyProtocol.parse(req, req.size) as HttpProxyProtocol.Result.Ok
        assertEquals("example.com", r.host)
        assertEquals(443, r.port)
        assertTrue(r.isConnect)
    }
}
