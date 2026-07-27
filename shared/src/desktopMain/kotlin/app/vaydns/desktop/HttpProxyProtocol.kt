package app.vaydns.desktop

import java.util.Base64

/**
 * Parsing for the HTTP side of the local proxy.
 *
 * Windows' "manual proxy" setting is an **HTTP** proxy address — `ProxyServer=127.0.0.1:1080` with
 * no scheme means WinINET sends every scheme there as HTTP, so a SOCKS5-only listener is invisible
 * to the system proxy. That is why this exists next to the SOCKS path.
 *
 * Two request shapes matter:
 *  - `CONNECT host:443 HTTP/1.1` — used for https:// and anything tunnelled. We answer 200 and go
 *    byte-transparent.
 *  - `GET http://host/path HTTP/1.1` (absolute-form) — used for plain http://. The origin server
 *    only understands origin-form, so the request line is rewritten and proxy hop-by-hop headers
 *    are dropped.
 *
 * Pure functions over an accumulating buffer, mirroring `SocksProtocol` — no I/O, so it is testable.
 */
internal object HttpProxyProtocol {

    sealed interface Result {
        /** Headers are not complete yet. */
        object NeedMore : Result

        /** Malformed or unsupported; [status] is a full HTTP response to send before closing. */
        data class Reject(val reason: String, val status: ByteArray) : Result

        data class Ok(
            val host: String,
            val port: Int,
            val isConnect: Boolean,
            /**
             * Bytes to send upstream once connected — the rewritten request head for absolute-form
             * requests, or null for CONNECT (which is byte-transparent after the 200).
             */
            val upstreamHead: ByteArray?,
            /** Offset just past the request head; anything after it is body/pipelined data. */
            val consumed: Int
        ) : Result
    }

    /** Largest request head we will buffer before giving up (headers can carry big cookies). */
    const val MAX_HEAD = 32 * 1024

    private val METHODS = listOf(
        "GET", "POST", "HEAD", "PUT", "DELETE", "OPTIONS", "TRACE", "PATCH", "CONNECT"
    )

    fun badRequest(reason: String): ByteArray = response(400, "Bad Request", reason)
    fun authRequired(): ByteArray =
        ("HTTP/1.1 407 Proxy Authentication Required\r\n" +
            "Proxy-Authenticate: Basic realm=\"vaydns\"\r\n" +
            "Content-Length: 0\r\n" +
            "Connection: close\r\n\r\n").toByteArray()

    fun connectEstablished(): ByteArray =
        "HTTP/1.1 200 Connection established\r\nProxy-Agent: vaydns\r\n\r\n".toByteArray()

    fun badGateway(reason: String): ByteArray = response(502, "Bad Gateway", reason)

    private fun response(code: Int, text: String, body: String): ByteArray {
        val payload = body.toByteArray()
        return ("HTTP/1.1 $code $text\r\nContent-Length: ${payload.size}\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\nConnection: close\r\n\r\n").toByteArray() + payload
    }

    /** True as soon as the first byte rules out SOCKS (0x05 / 0x04) and looks like a method. */
    fun looksLikeHttp(firstByte: Int): Boolean = firstByte != 0x05 && firstByte != 0x04

    /**
     * @param requireAuth when set, a matching `Proxy-Authorization: Basic` header must be present.
     */
    fun parse(buf: ByteArray, len: Int, requireAuth: Pair<String, String>? = null): Result {
        val headEnd = indexOfHeadEnd(buf, len)
        if (headEnd < 0) {
            return if (len >= MAX_HEAD) Result.Reject("head too large", badRequest("header too large"))
            else Result.NeedMore
        }
        val head = String(buf, 0, headEnd - 4, Charsets.ISO_8859_1)
        val lines = head.split("\r\n")
        val requestLine = lines.firstOrNull().orEmpty()
        val parts = requestLine.split(' ')
        if (parts.size < 3) return Result.Reject("bad request line", badRequest("malformed request line"))
        val method = parts[0].uppercase()
        val target = parts[1]
        val version = parts[2]
        if (method !in METHODS) return Result.Reject("method $method", badRequest("unsupported method"))

        if (requireAuth != null && !authOk(lines, requireAuth)) {
            return Result.Reject("proxy auth", authRequired())
        }

        if (method == "CONNECT") {
            val hp = splitHostPort(target, defaultPort = 443)
                ?: return Result.Reject("bad authority", badRequest("malformed CONNECT target"))
            return Result.Ok(hp.first, hp.second, isConnect = true, upstreamHead = null, consumed = headEnd)
        }

        // Absolute-form: scheme://host[:port]/path
        if (!target.startsWith("http://", ignoreCase = true)) {
            // Origin-form means the client thinks we are the origin server, not a proxy.
            return Result.Reject("not absolute-form", badRequest("this port is a proxy; use an absolute URI"))
        }
        val afterScheme = target.substring("http://".length)
        val slash = afterScheme.indexOf('/')
        val authority = if (slash < 0) afterScheme else afterScheme.substring(0, slash)
        val path = if (slash < 0) "/" else afterScheme.substring(slash)
        val hp = splitHostPort(authority, defaultPort = 80)
            ?: return Result.Reject("bad authority", badRequest("malformed absolute URI"))

        val rebuilt = StringBuilder()
        rebuilt.append(method).append(' ').append(path).append(' ').append(version).append("\r\n")
        var sawHost = false
        for (i in 1 until lines.size) {
            val lineText = lines[i]
            if (lineText.isEmpty()) continue
            val name = lineText.substringBefore(':', "").trim().lowercase()
            when (name) {
                // Hop-by-hop / proxy-only headers must not reach the origin.
                "proxy-connection", "proxy-authorization", "connection", "keep-alive",
                "transfer-encoding", "upgrade", "te", "trailer" -> continue
                "host" -> sawHost = true
            }
            rebuilt.append(lineText).append("\r\n")
        }
        if (!sawHost) rebuilt.append("Host: ").append(authority).append("\r\n")
        // One request per upstream connection. Keep-alive would let the client pipeline a request
        // for a *different* host down a socket already pinned to this one.
        rebuilt.append("Connection: close\r\n")
        rebuilt.append("\r\n")

        return Result.Ok(
            hp.first, hp.second,
            isConnect = false,
            upstreamHead = rebuilt.toString().toByteArray(Charsets.ISO_8859_1),
            consumed = headEnd
        )
    }

    private fun authOk(lines: List<String>, expected: Pair<String, String>): Boolean {
        val header = lines.drop(1).firstOrNull {
            it.substringBefore(':', "").trim().equals("Proxy-Authorization", ignoreCase = true)
        } ?: return false
        val value = header.substringAfter(':').trim()
        if (!value.startsWith("Basic ", ignoreCase = true)) return false
        val decoded = runCatching {
            String(Base64.getDecoder().decode(value.substring(6).trim()), Charsets.UTF_8)
        }.getOrNull() ?: return false
        val user = decoded.substringBefore(':', "")
        val pass = decoded.substringAfter(':', "")
        return user == expected.first && pass == expected.second
    }

    /** Returns the offset just past the CRLFCRLF that ends the head, or -1 if incomplete. */
    private fun indexOfHeadEnd(buf: ByteArray, len: Int): Int {
        var i = 3
        while (i < len) {
            if (buf[i] == '\n'.code.toByte() && buf[i - 1] == '\r'.code.toByte() &&
                buf[i - 2] == '\n'.code.toByte() && buf[i - 3] == '\r'.code.toByte()
            ) {
                return i + 1
            }
            i++
        }
        return -1
    }

    /** "host:port" / "host" / "[::1]:port" → host + port. */
    fun splitHostPort(authority: String, defaultPort: Int): Pair<String, Int>? {
        if (authority.isBlank()) return null
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            val host = authority.substring(1, close)
            val rest = authority.substring(close + 1)
            val port = if (rest.startsWith(":")) rest.substring(1).toIntOrNull() ?: return null else defaultPort
            return if (host.isBlank() || port !in 1..65535) null else host to port
        }
        val colon = authority.lastIndexOf(':')
        if (colon < 0) return authority to defaultPort
        val host = authority.substring(0, colon)
        val port = authority.substring(colon + 1).toIntOrNull() ?: return null
        return if (host.isBlank() || port !in 1..65535) null else host to port
    }

    /** SOCKS5 address block (ATYP=domain) for [host], plus the two port bytes. */
    fun socksAddress(host: String, port: Int): Pair<ByteArray, ByteArray> {
        val hostBytes = host.toByteArray(Charsets.US_ASCII)
        val raw = ByteArray(2 + hostBytes.size)
        raw[0] = 0x03
        raw[1] = hostBytes.size.toByte()
        hostBytes.copyInto(raw, 2)
        val portBytes = byteArrayOf(((port shr 8) and 0xFF).toByte(), (port and 0xFF).toByte())
        return raw to portBytes
    }
}
