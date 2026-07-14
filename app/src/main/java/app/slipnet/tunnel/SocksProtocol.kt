package app.slipnet.tunnel

/**
 * Pure, incremental SOCKS5 protocol helpers for the non-blocking (NIO) bridge. Every parser works
 * over an accumulating byte buffer and returns [NeedMore] when the message isn't complete yet, so a
 * selector loop can call it again after the next readable event without ever blocking a thread.
 *
 * This replaces the blocking `input.read()` handshake of the old thread-per-connection bridge,
 * whose model (2 threads + 30s read-timeout per connection) piled up hundreds of threads/fds under
 * a slow carrier. The wire format here is byte-for-byte identical to the old
 * readSocksGreeting/readSocksRequest/openSlipstreamSocksCommand paths.
 */
object SocksProtocol {

    sealed interface ParseResult<out T> {
        /** Not enough bytes accumulated yet; read more and retry. */
        object NeedMore : ParseResult<Nothing>
        /** Parsed [value]; [consumed] bytes should be dropped from the front of the buffer. */
        data class Ok<T>(val value: T, val consumed: Int) : ParseResult<T>
        /** Malformed/rejected; the connection should be closed. */
        data class Bad(val reason: String) : ParseResult<Nothing>
    }

    private fun u(b: Byte) = b.toInt() and 0xFF

    // ---- client side (we act as the SOCKS server toward tun2socks) ----

    /** Client greeting: [ver=5, nmethods, methods...]. Returns the offered method bytes. */
    fun parseClientGreeting(buf: ByteArray, len: Int): ParseResult<ByteArray> {
        if (len < 2) return ParseResult.NeedMore
        if (u(buf[0]) != 0x05) return ParseResult.Bad("greeting ver=${u(buf[0])}")
        val n = u(buf[1])
        if (n <= 0) return ParseResult.Bad("no methods")
        if (len < 2 + n) return ParseResult.NeedMore
        return ParseResult.Ok(buf.copyOfRange(2, 2 + n), 2 + n)
    }

    /** Username/password auth (RFC1929): [ver=1, ulen, user, plen, pass]. */
    fun parseClientAuth(buf: ByteArray, len: Int): ParseResult<Pair<String, String>> {
        if (len < 2) return ParseResult.NeedMore
        if (u(buf[0]) != 0x01) return ParseResult.Bad("auth ver=${u(buf[0])}")
        val ulen = u(buf[1])
        if (len < 2 + ulen + 1) return ParseResult.NeedMore
        val plen = u(buf[2 + ulen])
        val total = 3 + ulen + plen
        if (len < total) return ParseResult.NeedMore
        val user = String(buf, 2, ulen, Charsets.UTF_8)
        val pass = String(buf, 3 + ulen, plen, Charsets.UTF_8)
        return ParseResult.Ok(user to pass, total)
    }

    /** A parsed CONNECT/associate request. [rawAddr] is `atyp + addr` bytes exactly as the old
     * code assembled it (for a domain: `[0x03, len, domain]`); [portBytes] the 2 port bytes. */
    data class Request(val cmd: Int, val rawAddr: ByteArray, val portBytes: ByteArray, val host: String) {
        override fun equals(other: Any?) = other is Request && cmd == other.cmd &&
            rawAddr.contentEquals(other.rawAddr) && portBytes.contentEquals(other.portBytes) && host == other.host
        override fun hashCode() = cmd
    }

    /** Client request: [ver=5, cmd, rsv, atyp, addr, port]. */
    fun parseClientRequest(buf: ByteArray, len: Int): ParseResult<Request> {
        if (len < 4) return ParseResult.NeedMore
        if (u(buf[0]) != 0x05) return ParseResult.Bad("request ver=${u(buf[0])}")
        val cmd = u(buf[1])
        val atyp = u(buf[3])
        // addrFieldLen = bytes after the atyp byte that make up the address
        val addrFieldLen = when (atyp) {
            0x01 -> 4
            0x04 -> 16
            0x03 -> {
                if (len < 5) return ParseResult.NeedMore
                1 + u(buf[4]) // length byte + domain
            }
            else -> return ParseResult.Bad("atyp=$atyp")
        }
        val total = 4 + addrFieldLen + 2
        if (len < total) return ParseResult.NeedMore
        val rawAddr = buf.copyOfRange(3, 4 + addrFieldLen) // atyp + addr field
        val portBytes = buf.copyOfRange(4 + addrFieldLen, 6 + addrFieldLen)
        return ParseResult.Ok(Request(cmd, rawAddr, portBytes, hostOf(rawAddr)), total)
    }

    private fun hostOf(rawAddr: ByteArray): String = when (u(rawAddr[0])) {
        0x01 -> (1..4).joinToString(".") { u(rawAddr[it]).toString() }
        0x03 -> String(rawAddr, 2, u(rawAddr[1]), Charsets.US_ASCII)
        0x04 -> (1..16 step 2).joinToString(":") {
            ((u(rawAddr[it]) shl 8) or u(rawAddr[it + 1])).toString(16)
        }
        else -> "unknown"
    }

    /** The 10-byte reply we send tun2socks: [5, rep, 0, 1, 0,0,0,0, 0,0]. */
    fun clientReply(rep: Int): ByteArray =
        byteArrayOf(0x05, rep.toByte(), 0x00, 0x01, 0, 0, 0, 0, 0, 0)

    // ---- upstream side (we act as a SOCKS client toward slipstream) ----

    fun upstreamGreeting(hasAuth: Boolean): ByteArray =
        if (hasAuth) byteArrayOf(0x05, 0x01, 0x02) else byteArrayOf(0x05, 0x01, 0x00)

    /** Upstream greeting reply: [ver, method]. Ok value = chosen method. */
    fun parseUpstreamGreetingReply(buf: ByteArray, len: Int): ParseResult<Int> {
        if (len < 2) return ParseResult.NeedMore
        if (u(buf[0]) != 0x05 || u(buf[1]) == 0xFF) return ParseResult.Bad("upstream greeting rejected")
        return ParseResult.Ok(u(buf[1]), 2)
    }

    fun upstreamAuth(user: String, pass: String): ByteArray {
        val u = user.toByteArray(Charsets.UTF_8)
        val p = pass.toByteArray(Charsets.UTF_8)
        require(u.size <= 255 && p.size <= 255) { "auth too long" }
        val f = ByteArray(3 + u.size + p.size)
        f[0] = 0x01
        f[1] = u.size.toByte()
        System.arraycopy(u, 0, f, 2, u.size)
        f[2 + u.size] = p.size.toByte()
        System.arraycopy(p, 0, f, 3 + u.size, p.size)
        return f
    }

    /** Upstream auth reply: [ver, status]. Ok = auth accepted. */
    fun parseUpstreamAuthReply(buf: ByteArray, len: Int): ParseResult<Unit> {
        if (len < 2) return ParseResult.NeedMore
        if (u(buf[1]) != 0x00) return ParseResult.Bad("upstream auth failed")
        return ParseResult.Ok(Unit, 2)
    }

    fun upstreamCommand(cmd: Int, rawAddr: ByteArray, portBytes: ByteArray): ByteArray {
        val f = ByteArray(3 + rawAddr.size + portBytes.size)
        f[0] = 0x05
        f[1] = cmd.toByte()
        f[2] = 0x00
        System.arraycopy(rawAddr, 0, f, 3, rawAddr.size)
        System.arraycopy(portBytes, 0, f, 3 + rawAddr.size, portBytes.size)
        return f
    }

    /** Upstream command reply: [ver, rep, rsv, atyp, bind_addr, bind_port]. We only need to know it
     * succeeded (rep==0) and how many bytes the whole reply spans, to consume it before relaying. */
    fun parseUpstreamCommandReply(buf: ByteArray, len: Int): ParseResult<Unit> {
        if (len < 4) return ParseResult.NeedMore
        if (u(buf[1]) != 0x00) return ParseResult.Bad("upstream connect rejected rep=${u(buf[1])}")
        val atyp = u(buf[3])
        val bindLen = when (atyp) {
            0x01 -> 4 + 2
            0x04 -> 16 + 2
            0x03 -> {
                if (len < 5) return ParseResult.NeedMore
                1 + u(buf[4]) + 2 // len byte + domain + port
            }
            else -> return ParseResult.Bad("upstream bind atyp=$atyp")
        }
        val total = 4 + bindLen
        if (len < total) return ParseResult.NeedMore
        return ParseResult.Ok(Unit, total)
    }
}
