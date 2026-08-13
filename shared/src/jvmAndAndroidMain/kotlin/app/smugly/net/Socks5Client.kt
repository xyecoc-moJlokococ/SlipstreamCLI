package app.smugly.net

import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Minimal SOCKS5 client for talking to a tunnel engine's own local listener.
 *
 * Java's built-in `Proxy.Type.SOCKS` could do the handshake, but it resolves the destination name
 * locally first and only falls back to sending the name when resolution fails. That defeats the
 * point here: the name must travel through the tunnel and be resolved on the far side, exactly as
 * it is when the app is really connected — otherwise a probe would measure the local network's DNS
 * and pass on a profile whose own exit resolves nothing.
 */
object Socks5Client {

    /**
     * Open a connection to [host]:[port] through the SOCKS5 proxy on 127.0.0.1:[proxyPort].
     *
     * Credentials are offered *alongside* no-auth rather than instead of it: the engines differ on
     * whether their local listener enforces the profile's login, and a client that offers only
     * user/pass gets `0xFF` from one that wants none.
     */
    fun connect(
        proxyPort: Int,
        host: String,
        port: Int,
        username: String? = null,
        password: String? = null,
        timeoutMs: Int = 10_000,
        proxyHost: String = "127.0.0.1"
    ): Socket {
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(proxyHost, proxyPort), timeoutMs)
            socket.soTimeout = timeoutMs
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val hasAuth = !username.isNullOrBlank() && !password.isNullOrBlank()
            if (hasAuth) {
                output.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
            } else {
                output.write(byteArrayOf(0x05, 0x01, 0x00))
            }
            output.flush()

            val greeting = ByteArray(2)
            input.readFully(greeting)
            if (greeting[0] != PROTOCOL_VERSION) error("not a SOCKS5 proxy")
            when (greeting[1]) {
                METHOD_NONE -> Unit
                METHOD_USER_PASS -> {
                    check(hasAuth) { "proxy asked for a login this profile does not have" }
                    authenticate(socket, username.orEmpty(), password.orEmpty())
                }
                else -> error("proxy rejected every auth method we offered")
            }

            val hostBytes = host.toByteArray(Charsets.US_ASCII)
            require(hostBytes.size in 1..255) { "host name too long" }
            output.write(byteArrayOf(0x05, CMD_CONNECT, 0x00, ATYP_DOMAIN, hostBytes.size.toByte()))
            output.write(hostBytes)
            output.write(byteArrayOf(((port shr 8) and 0xFF).toByte(), (port and 0xFF).toByte()))
            output.flush()

            val reply = ByteArray(4)
            input.readFully(reply)
            if (reply[1] != REPLY_OK) error("proxy refused the connection (${replyName(reply[1])})")
            // The bound address that follows is of no use to us, but it has to leave the stream.
            skipBoundAddress(input, reply[3].toInt() and 0xFF)
            return socket
        } catch (e: Throwable) {
            runCatching { socket.close() }
            throw e
        }
    }

    private fun authenticate(socket: Socket, username: String, password: String) {
        val user = username.toByteArray(Charsets.UTF_8)
        val pass = password.toByteArray(Charsets.UTF_8)
        require(user.size <= 255 && pass.size <= 255) { "credentials too long for SOCKS5" }
        val output = socket.getOutputStream()
        output.write(byteArrayOf(0x01, user.size.toByte()))
        output.write(user)
        output.write(pass.size)
        output.write(pass)
        output.flush()
        val answer = ByteArray(2)
        socket.getInputStream().readFully(answer)
        if (answer[1] != 0x00.toByte()) error("proxy rejected the login")
    }

    private fun skipBoundAddress(input: InputStream, addressType: Int) {
        val length = when (addressType) {
            ATYP_IPV4.toInt() -> 4
            ATYP_IPV6.toInt() -> 16
            ATYP_DOMAIN.toInt() -> {
                val size = input.read()
                if (size < 0) throw EOFException("truncated SOCKS5 reply")
                size
            }
            else -> error("unknown SOCKS5 address type $addressType")
        }
        input.readFully(ByteArray(length + 2)) // + port
    }

    private fun InputStream.readFully(buffer: ByteArray) {
        var read = 0
        while (read < buffer.size) {
            val n = read(buffer, read, buffer.size - read)
            if (n < 0) throw EOFException("proxy closed the connection mid-handshake")
            read += n
        }
    }

    private fun replyName(code: Byte): String = when (code.toInt() and 0xFF) {
        1 -> "general failure"
        2 -> "not allowed"
        3 -> "network unreachable"
        4 -> "host unreachable"
        5 -> "connection refused"
        6 -> "TTL expired"
        7 -> "command not supported"
        8 -> "address type not supported"
        else -> "rep=${code.toInt() and 0xFF}"
    }

    private const val PROTOCOL_VERSION: Byte = 0x05
    private const val CMD_CONNECT: Byte = 0x01
    private const val METHOD_NONE: Byte = 0x00
    private const val METHOD_USER_PASS: Byte = 0x02
    private const val REPLY_OK: Byte = 0x00
    private const val ATYP_IPV4: Byte = 0x01
    private const val ATYP_DOMAIN: Byte = 0x03
    private const val ATYP_IPV6: Byte = 0x04
}
