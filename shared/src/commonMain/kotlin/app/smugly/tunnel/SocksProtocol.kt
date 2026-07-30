package app.smugly.tunnel

/**
 * Pure, incremental SOCKS5 protocol helpers for the non-blocking (NIO) bridge. Every parser works
 * over an accumulating byte buffer and returns [NeedMore] when the message isn't complete yet.
 * Multiplatform: no Android / JVM-only APIs.
 */
object SocksProtocol {

    sealed interface ParseResult<out T> {
        object NeedMore : ParseResult<Nothing>
        data class Ok<T>(val value: T, val consumed: Int) : ParseResult<T>
        data class Bad(val reason: String) : ParseResult<Nothing>
    }

    private fun u(b: Byte) = b.toInt() and 0xFF

    fun parseClientGreeting(buf: ByteArray, len: Int): ParseResult<ByteArray> {
        if (len < 2) return ParseResult.NeedMore
        if (u(buf[0]) != 0x05) return ParseResult.Bad("greeting ver=${u(buf[0])}")
        val n = u(buf[1])
        if (n <= 0) return ParseResult.Bad("no methods")
        if (len < 2 + n) return ParseResult.NeedMore
        return ParseResult.Ok(buf.copyOfRange(2, 2 + n), 2 + n)
    }

    fun parseClientAuth(buf: ByteArray, len: Int): ParseResult<Pair<String, String>> {
        if (len < 2) return ParseResult.NeedMore
        if (u(buf[0]) != 0x01) return ParseResult.Bad("auth ver=${u(buf[0])}")
        val ulen = u(buf[1])
        if (len < 2 + ulen + 1) return ParseResult.NeedMore
        val plen = u(buf[2 + ulen])
        val total = 3 + ulen + plen
        if (len < total) return ParseResult.NeedMore
        val user = buf.decodeToString(2, 2 + ulen)
        val pass = buf.decodeToString(3 + ulen, 3 + ulen + plen)
        return ParseResult.Ok(user to pass, total)
    }

    data class Request(val cmd: Int, val rawAddr: ByteArray, val portBytes: ByteArray, val host: String) {
        override fun equals(other: Any?) = other is Request && cmd == other.cmd &&
            rawAddr.contentEquals(other.rawAddr) && portBytes.contentEquals(other.portBytes) && host == other.host
        override fun hashCode() = cmd
    }

    fun parseClientRequest(buf: ByteArray, len: Int): ParseResult<Request> {
        if (len < 4) return ParseResult.NeedMore
        if (u(buf[0]) != 0x05) return ParseResult.Bad("request ver=${u(buf[0])}")
        val cmd = u(buf[1])
        val atyp = u(buf[3])
        val addrFieldLen = when (atyp) {
            0x01 -> 4
            0x04 -> 16
            0x03 -> {
                if (len < 5) return ParseResult.NeedMore
                1 + u(buf[4])
            }
            else -> return ParseResult.Bad("atyp=$atyp")
        }
        val total = 4 + addrFieldLen + 2
        if (len < total) return ParseResult.NeedMore
        val rawAddr = buf.copyOfRange(3, 4 + addrFieldLen)
        val portBytes = buf.copyOfRange(4 + addrFieldLen, 6 + addrFieldLen)
        return ParseResult.Ok(Request(cmd, rawAddr, portBytes, hostOf(rawAddr)), total)
    }

    private fun hostOf(rawAddr: ByteArray): String = when (u(rawAddr[0])) {
        0x01 -> (1..4).joinToString(".") { u(rawAddr[it]).toString() }
        0x03 -> rawAddr.decodeToString(2, 2 + u(rawAddr[1]))
        0x04 -> (1..16 step 2).joinToString(":") {
            ((u(rawAddr[it]) shl 8) or u(rawAddr[it + 1])).toString(16)
        }
        else -> "unknown"
    }

    fun clientReply(rep: Int): ByteArray =
        byteArrayOf(0x05, rep.toByte(), 0x00, 0x01, 0, 0, 0, 0, 0, 0)

    fun upstreamGreeting(hasAuth: Boolean): ByteArray =
        if (hasAuth) byteArrayOf(0x05, 0x01, 0x02) else byteArrayOf(0x05, 0x01, 0x00)

    fun parseUpstreamGreetingReply(buf: ByteArray, len: Int): ParseResult<Int> {
        if (len < 2) return ParseResult.NeedMore
        if (u(buf[0]) != 0x05 || u(buf[1]) == 0xFF) return ParseResult.Bad("upstream greeting rejected")
        return ParseResult.Ok(u(buf[1]), 2)
    }

    fun upstreamAuth(user: String, pass: String): ByteArray {
        val uBytes = user.encodeToByteArray()
        val pBytes = pass.encodeToByteArray()
        require(uBytes.size <= 255 && pBytes.size <= 255) { "auth too long" }
        val f = ByteArray(3 + uBytes.size + pBytes.size)
        f[0] = 0x01
        f[1] = uBytes.size.toByte()
        uBytes.copyInto(f, 2)
        f[2 + uBytes.size] = pBytes.size.toByte()
        pBytes.copyInto(f, 3 + uBytes.size)
        return f
    }

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
        rawAddr.copyInto(f, 3)
        portBytes.copyInto(f, 3 + rawAddr.size)
        return f
    }

    fun parseUpstreamCommandReply(buf: ByteArray, len: Int): ParseResult<Unit> {
        if (len < 4) return ParseResult.NeedMore
        if (u(buf[1]) != 0x00) return ParseResult.Bad("upstream connect rejected rep=${u(buf[1])}")
        val atyp = u(buf[3])
        val bindLen = when (atyp) {
            0x01 -> 4 + 2
            0x04 -> 16 + 2
            0x03 -> {
                if (len < 5) return ParseResult.NeedMore
                1 + u(buf[4]) + 2
            }
            else -> return ParseResult.Bad("upstream bind atyp=$atyp")
        }
        val total = 4 + bindLen
        if (len < total) return ParseResult.NeedMore
        return ParseResult.Ok(Unit, total)
    }
}
