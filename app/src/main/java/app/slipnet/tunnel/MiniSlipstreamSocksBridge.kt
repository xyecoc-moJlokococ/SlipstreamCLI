package app.slipnet.tunnel

import app.slipnet.util.AppLog
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object MiniSlipstreamSocksBridge {
    private const val TAG = "MiniSlipstreamSocksBridge"
    private const val BUFFER_SIZE = 65536
    private const val SOCKET_BUFFER_SIZE = 1024 * 1024
    private const val MAX_ACTIVE_CLIENTS = 128
    private const val OVERLOAD_CLOSE_BATCH = 16
    // The SOCKS handshake reads (greeting/auth/connect-reply) traverse the DNS tunnel to the
    // server and back. Under a heavy upload, these small control round-trips get queued behind
    // bulk data (head-of-line blocking) and tunnel latency balloons past several seconds
    // (observed slowResponseMs up to ~35s). A 5s timeout here caused every fresh connection a
    // parallel upload tried to open (a speedtest / large file opens many at once) to fail with
    // "Read timed out", so upload stalled while small reused-connection traffic went through.
    // Raised to 20s to ride out congestion bursts; the drop in spurious connect failures also
    // stops the failure-accumulation recovery from tearing down a working tunnel mid-upload.
    private const val CONNECT_TIMEOUT_MS = 20000
    private const val READ_TIMEOUT_MS = 30000
    private const val DNS_TIMEOUT_MS = 5000
    private val REMOTE_DNS_FALLBACKS = listOf("1.1.1.1", "8.8.8.8")

    private val running = AtomicBoolean(false)
    private val clients = CopyOnWriteArrayList<Socket>()
    private val remotes = CopyOnWriteArrayList<Socket>()
    private val threads = CopyOnWriteArrayList<Thread>()
    private val txBytes = AtomicLong(0)
    private val rxBytes = AtomicLong(0)
    private val connectOk = AtomicLong(0)
    private val connectFail = AtomicLong(0)
    private val dnsOk = AtomicLong(0)
    private val dnsFail = AtomicLong(0)
    private val udpDroppedPackets = AtomicLong(0)
    private val udpDnsPackets = AtomicLong(0)
    private val activeClients = AtomicInteger(0)

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    @Volatile private var slipstreamHost = "127.0.0.1"
    @Volatile private var slipstreamPort = 1081
    @Volatile private var dnsHost = ""
    @Volatile private var username: String? = null
    @Volatile private var password: String? = null
    @Volatile private var localUsername: String? = null
    @Volatile private var localPassword: String? = null

    fun start(
        listenHost: String,
        listenPort: Int,
        slipstreamHost: String,
        slipstreamPort: Int,
        dnsHost: String,
        username: String?,
        password: String?,
        localUsername: String? = null,
        localPassword: String? = null
    ): Result<Unit> {
        stop()
        this.slipstreamHost = slipstreamHost
        this.slipstreamPort = slipstreamPort
        this.dnsHost = dnsHost.trim()
        this.username = username?.takeIf { it.isNotBlank() }
        this.password = password?.takeIf { it.isNotBlank() }
        this.localUsername = localUsername?.takeIf { it.isNotBlank() }
        this.localPassword = localPassword?.takeIf { it.isNotBlank() }
        txBytes.set(0)
        rxBytes.set(0)
        connectOk.set(0)
        connectFail.set(0)
        dnsOk.set(0)
        dnsFail.set(0)
        udpDroppedPackets.set(0)
        udpDnsPackets.set(0)
        activeClients.set(0)

        return runCatching {
            val ss = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(listenHost, listenPort))
            }
            serverSocket = ss
            running.set(true)
            AppLog.i(
                TAG,
                "bridge start listen=$listenHost:$listenPort slipstream=$slipstreamHost:$slipstreamPort transportDns=$dnsHost " +
                    "upstreamAuth=${if (this.username != null) "login/password" else "no-auth"} " +
                    "localAuth=${if (this.localUsername != null && this.localPassword != null) "login/password" else "no-auth"}"
            )
            acceptThread = Thread({
                while (running.get()) {
                    try {
                        handleClient(ss.accept())
                    } catch (e: Throwable) {
                        if (running.get()) AppLog.w(TAG, "accept failed: ${e.message}")
                    }
                }
            }, "mini-slip-bridge-accept").also { it.isDaemon = true; it.start() }
        }
    }

    fun stop() {
        if (!running.getAndSet(false) && serverSocket == null) return
        AppLog.i(TAG, "bridge stop tx=${txBytes.get()} rx=${rxBytes.get()} connectOk=${connectOk.get()} connectFail=${connectFail.get()} dnsOk=${dnsOk.get()} dnsFail=${dnsFail.get()} active=${activeClients.get()} clients=${clients.size} remotes=${remotes.size} threads=${threads.size}")
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { acceptThread?.interrupt() }
        runCatching { acceptThread?.join(200) }
        acceptThread = null
        clients.forEach { closeSocketNow(it) }
        remotes.forEach { closeSocketNow(it) }
        clients.clear()
        remotes.clear()
        threads.forEach { it.interrupt() }
        threads.clear()
    }

    fun isRunning(): Boolean = running.get()

    fun stats(): TrafficStats = TrafficStats(
        txBytes.get(),
        rxBytes.get(),
        connectOk.get(),
        connectFail.get(),
        dnsOk.get(),
        dnsFail.get(),
        activeClients.get(),
        clients.size,
        remotes.size,
        threads.size
    )

    private fun handleClient(socket: Socket) {
        if (activeClients.get() >= MAX_ACTIVE_CLIENTS) {
            closeOldBridgeSockets("active limit pre-trim")
        }
        if (activeClients.incrementAndGet() > MAX_ACTIVE_CLIENTS) {
            activeClients.decrementAndGet()
            connectFail.incrementAndGet()
            AppLog.w(TAG, "client rejected: active limit $MAX_ACTIVE_CLIENTS reached")
            runCatching { socket.close() }
            return
        }
        val thread = Thread({
            clients.add(socket)
            try {
                socket.use {
                    configureBridgeSocket(it)
                    it.soTimeout = 30000
                    val input = it.getInputStream()
                    val output = it.getOutputStream()
                    if (!readSocksGreeting(input, output)) return@use
                    val req = readSocksRequest(input) ?: run {
                        writeSocksReply(output, 0x07)
                        return@use
                    }
                    when (req.cmd) {
                        0x01 -> handleConnect(req, it, input, output)
                        0x05 -> handleFwdUdp(it, input, output)
                        else -> writeSocksReply(output, 0x07)
                    }
                }
            } catch (e: Throwable) {
                if (running.get()) AppLog.d(TAG, "client error: ${e.message}")
            } finally {
                clients.remove(socket)
                activeClients.decrementAndGet()
                threads.remove(Thread.currentThread())
            }
        }, "mini-slip-bridge-client")
        thread.isDaemon = true
        threads.add(thread)
        thread.start()
    }

    private fun closeOldBridgeSockets(reason: String) {
        val clientSnapshot = clients.take(OVERLOAD_CLOSE_BATCH)
        val remoteSnapshot = remotes.take(OVERLOAD_CLOSE_BATCH)
        if (clientSnapshot.isEmpty() && remoteSnapshot.isEmpty()) return
        AppLog.w(
            TAG,
            "overload cleanup reason=$reason active=${activeClients.get()} " +
                "closingClients=${clientSnapshot.size} closingRemotes=${remoteSnapshot.size}"
        )
        clientSnapshot.forEach { closeSocketNow(it) }
        remoteSnapshot.forEach { closeSocketNow(it) }
    }

    private fun readSocksGreeting(input: InputStream, output: OutputStream): Boolean {
        val ver = input.read()
        if (ver != 0x05) return false
        val nMethods = input.read()
        if (nMethods <= 0) return false
        val methods = ByteArray(nMethods)
        input.readFullyStrict(methods)
        val requiresAuth = !localUsername.isNullOrBlank() && !localPassword.isNullOrBlank()
        if (requiresAuth) {
            if (0x02.toByte() !in methods) {
                output.write(byteArrayOf(0x05, 0xFF.toByte()))
                output.flush()
                return false
            }
            output.write(byteArrayOf(0x05, 0x02))
            output.flush()
            return readSocksPasswordAuth(input, output)
        }
        if (0x00.toByte() !in methods) {
            output.write(byteArrayOf(0x05, 0xFF.toByte()))
            output.flush()
            return false
        }
        output.write(byteArrayOf(0x05, 0x00))
        output.flush()
        return true
    }

    private fun readSocksPasswordAuth(input: InputStream, output: OutputStream): Boolean {
        val ver = input.read()
        if (ver != 0x01) return false
        val userLen = input.read()
        if (userLen < 0) return false
        val user = ByteArray(userLen)
        input.readFullyStrict(user)
        val passLen = input.read()
        if (passLen < 0) return false
        val pass = ByteArray(passLen)
        input.readFullyStrict(pass)
        val ok = String(user, Charsets.UTF_8) == localUsername.orEmpty() &&
            String(pass, Charsets.UTF_8) == localPassword.orEmpty()
        output.write(byteArrayOf(0x01, if (ok) 0x00 else 0x01))
        output.flush()
        return ok
    }

    private fun readSocksRequest(input: InputStream): SocksRequest? {
        val ver = input.read()
        val cmd = input.read()
        input.read()
        val atyp = input.read()
        if (ver != 0x05 || cmd < 0 || atyp < 0) return null
        val rawAddr = when (atyp) {
            0x01 -> byteArrayOf(0x01) + ByteArray(4).also { input.readFullyStrict(it) }
            0x03 -> {
                val len = input.read()
                if (len <= 0) return null
                byteArrayOf(0x03, len.toByte()) + ByteArray(len).also { input.readFullyStrict(it) }
            }
            0x04 -> byteArrayOf(0x04) + ByteArray(16).also { input.readFullyStrict(it) }
            else -> return null
        }
        val port = ByteArray(2)
        input.readFullyStrict(port)
        val host = parseHost(rawAddr) ?: "unknown"
        val portInt = ((port[0].toInt() and 0xFF) shl 8) or (port[1].toInt() and 0xFF)
        return SocksRequest(cmd, rawAddr, port, host, portInt)
    }

    private fun handleConnect(req: SocksRequest, client: Socket, clientInput: InputStream, clientOutput: OutputStream) {
        var remote: Socket? = null
        try {
            remote = openSlipstreamSocks(req.rawAddr, req.portBytes)
            connectOk.incrementAndGet()
            writeSocksReply(clientOutput, 0x00)
            client.soTimeout = READ_TIMEOUT_MS
            remote.soTimeout = READ_TIMEOUT_MS
            AppLog.d(TAG, "CONNECT ${req.host}:${req.port} OK")
            bridgeSockets(client, clientInput, clientOutput, remote)
        } catch (e: Throwable) {
            connectFail.incrementAndGet()
            AppLog.w(TAG, "CONNECT ${req.host}:${req.port} failed: ${e.message}")
            writeSocksReply(clientOutput, 0x05)
            runCatching { remote?.close() }
        }
    }

    private fun handleFwdUdp(client: Socket, input: InputStream, output: OutputStream) {
        var remote: Socket? = null
        try {
            remote = openSlipstreamFwdUdp()
            connectOk.incrementAndGet()
            writeSocksReply(output, 0x00)
            remote.soTimeout = READ_TIMEOUT_MS
            AppLog.d(TAG, "FWD_UDP upstream OK")
            bridgeSockets(client, input, output, remote)
        } catch (e: Throwable) {
            connectFail.incrementAndGet()
            AppLog.w(TAG, "FWD_UDP upstream failed: ${e.message}")
            writeSocksReply(output, 0x05)
            remote?.let { closeSocketNow(it) }
        }
    }

    private fun forwardDns(packetHost: String, payload: ByteArray): ByteArray? {
        var firstNegative: ByteArray? = null
        for (host in dnsForwarders(packetHost)) {
            val response = forwardDnsTcpTo(host, payload) ?: continue
            val rcode = dnsRcode(response)
            if (rcode == 0) return response
            if (firstNegative == null) firstNegative = response
            AppLog.d(TAG, "DNS tcp $host returned rcode=$rcode; trying next resolver")
        }
        return firstNegative
    }

    private fun dnsForwarders(packetHost: String): List<String> =
        (REMOTE_DNS_FALLBACKS + listOf(packetHost))
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "0.0.0.0" && it != dnsHost.trim() }
            .distinct()

    private fun forwardDnsTcpTo(host: String, payload: ByteArray): ByteArray? {
        val rawAddr = byteArrayOf(0x03, host.length.toByte()) + host.toByteArray(Charsets.US_ASCII)
        val port = byteArrayOf(0x00, 0x35)
        var sock: Socket? = null
        return try {
            sock = openSlipstreamSocks(rawAddr, port)
            sock.soTimeout = DNS_TIMEOUT_MS
            val input = sock.getInputStream()
            val output = sock.getOutputStream()
            val frame = ByteArray(payload.size + 2)
            frame[0] = ((payload.size shr 8) and 0xFF).toByte()
            frame[1] = (payload.size and 0xFF).toByte()
            System.arraycopy(payload, 0, frame, 2, payload.size)
            output.write(frame)
            output.flush()
            val lenBytes = ByteArray(2)
            input.readFullyStrict(lenBytes)
            val len = ((lenBytes[0].toInt() and 0xFF) shl 8) or (lenBytes[1].toInt() and 0xFF)
            if (len <= 0 || len > 65535) return null
            ByteArray(len).also { input.readFullyStrict(it) }
        } catch (e: Throwable) {
            AppLog.d(TAG, "DNS tcp $host failed: ${e.message}")
            null
        } finally {
            runCatching { sock?.close() }
            if (sock != null) remotes.remove(sock)
        }
    }

    private fun dnsRcode(response: ByteArray): Int {
        if (response.size < 4) return -1
        return response[3].toInt() and 0x0F
    }

    private fun openSlipstreamSocks(rawAddr: ByteArray, portBytes: ByteArray): Socket {
        return openSlipstreamSocksCommand(0x01, rawAddr, portBytes)
    }

    private fun openSlipstreamFwdUdp(): Socket {
        return openSlipstreamSocksCommand(0x05, byteArrayOf(0x01, 0, 0, 0, 0), byteArrayOf(0, 0))
    }

    private fun openSlipstreamSocksCommand(cmd: Int, rawAddr: ByteArray, portBytes: ByteArray): Socket {
        val sock = Socket()
        configureBridgeSocket(sock)
        sock.connect(InetSocketAddress(slipstreamHost, slipstreamPort), CONNECT_TIMEOUT_MS)
        sock.soTimeout = CONNECT_TIMEOUT_MS
        remotes.add(sock)
        try {
            val input = sock.getInputStream()
            val output = sock.getOutputStream()
            val hasAuth = !username.isNullOrBlank() && !password.isNullOrBlank()
            output.write(if (hasAuth) byteArrayOf(0x05, 0x01, 0x02) else byteArrayOf(0x05, 0x01, 0x00))
            output.flush()
            val greeting = ByteArray(2)
            input.readFullyStrict(greeting)
            if (greeting[0] != 0x05.toByte() || greeting[1] == 0xFF.toByte()) error("upstream greeting rejected")
            if (greeting[1] == 0x02.toByte()) {
                val user = username.orEmpty().toByteArray()
                val pass = password.orEmpty().toByteArray()
                require(user.size <= 255 && pass.size <= 255) { "auth too long" }
                val authFrame = ByteArray(3 + user.size + pass.size)
                authFrame[0] = 0x01
                authFrame[1] = user.size.toByte()
                System.arraycopy(user, 0, authFrame, 2, user.size)
                authFrame[2 + user.size] = pass.size.toByte()
                System.arraycopy(pass, 0, authFrame, 3 + user.size, pass.size)
                output.write(authFrame)
                output.flush()
                val auth = ByteArray(2)
                input.readFullyStrict(auth)
                if (auth[1] != 0x00.toByte()) error("upstream auth failed")
            }
            val commandFrame = ByteArray(3 + rawAddr.size + portBytes.size)
            commandFrame[0] = 0x05
            commandFrame[1] = cmd.toByte()
            commandFrame[2] = 0x00
            System.arraycopy(rawAddr, 0, commandFrame, 3, rawAddr.size)
            System.arraycopy(portBytes, 0, commandFrame, 3 + rawAddr.size, portBytes.size)
            output.write(commandFrame)
            output.flush()
            val header = ByteArray(4)
            input.readFullyStrict(header)
            if (header[1] != 0x00.toByte()) error("upstream connect rejected rep=${header[1].toInt() and 0xFF}")
            skipSocksBindAddress(input, header[3].toInt() and 0xFF)
            sock.soTimeout = READ_TIMEOUT_MS
            return sock
        } catch (e: Throwable) {
            remotes.remove(sock)
            runCatching { sock.close() }
            throw e
        }
    }

    private fun bridgeSockets(client: Socket, clientInput: InputStream, clientOutput: OutputStream, remote: Socket) {
        remote.use {
            val remoteInput = it.getInputStream()
            val remoteOutput = it.getOutputStream()
            val up = Thread({
                try {
                    copy(clientInput, remoteOutput, txBytes)
                    runCatching { remote.shutdownOutput() }
                } finally {
                    closeSocketNow(remote)
                    closeSocketNow(client)
                    threads.remove(Thread.currentThread())
                }
            }, "mini-slip-up")
            up.isDaemon = true
            threads.add(up)
            up.start()
            try {
                copy(remoteInput, clientOutput, rxBytes)
                runCatching { client.shutdownOutput() }
            } finally {
                closeSocketNow(remote)
                closeSocketNow(client)
                runCatching { up.join(500) }
                remotes.remove(remote)
            }
        }
    }

    private fun closeSocketNow(socket: Socket) {
        runCatching { socket.shutdownInput() }
        runCatching { socket.shutdownOutput() }
        runCatching { socket.close() }
    }

    private fun copy(input: InputStream, output: OutputStream, counter: AtomicLong) {
        val buf = ByteArray(BUFFER_SIZE)
        while (running.get()) {
            val n = try {
                input.read(buf)
            } catch (_: Throwable) {
                return
            }
            if (n <= 0) return
            try {
                output.write(buf, 0, n)
            } catch (_: Throwable) {
                return
            }
            counter.addAndGet(n.toLong())
        }
    }

    private fun configureBridgeSocket(socket: Socket) {
        runCatching { socket.tcpNoDelay = true }
        runCatching { socket.receiveBufferSize = SOCKET_BUFFER_SIZE }
        runCatching { socket.sendBufferSize = SOCKET_BUFFER_SIZE }
        runCatching { socket.setPerformancePreferences(0, 2, 1) }
    }

    private fun writeSocksReply(output: OutputStream, rep: Int) {
        output.write(byteArrayOf(0x05, rep.toByte(), 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        output.flush()
    }

    private fun skipSocksBindAddress(input: InputStream, atyp: Int) {
        when (atyp) {
            0x01 -> input.readFullyStrict(ByteArray(6))
            0x03 -> {
                val len = input.read()
                if (len < 0) error("bad domain bind response")
                input.readFullyStrict(ByteArray(len + 2))
            }
            0x04 -> input.readFullyStrict(ByteArray(18))
        }
    }

    private fun parseUdpDest(raw: ByteArray): Pair<String, Int>? {
        if (raw.size < 4) return null
        val host = parseHost(raw) ?: return null
        val port = ((raw[raw.size - 2].toInt() and 0xFF) shl 8) or (raw[raw.size - 1].toInt() and 0xFF)
        return host to port
    }

    private fun parseHost(raw: ByteArray): String? = when (raw[0].toInt() and 0xFF) {
        0x01 -> raw.copyOfRange(1, 5).joinToString(".") { (it.toInt() and 0xFF).toString() }
        0x03 -> {
            val len = raw[1].toInt() and 0xFF
            String(raw, 2, len, Charsets.US_ASCII)
        }
        0x04 -> InetAddress.getByAddress(raw.copyOfRange(1, 17)).hostAddress
        else -> null
    }

    private fun socksAddrBytes(address: InetAddress, port: Int): ByteArray {
        val addr = address.address
        val portBytes = byteArrayOf(((port shr 8) and 0xFF).toByte(), (port and 0xFF).toByte())
        return when (addr.size) {
            4 -> byteArrayOf(0x01) + addr + portBytes
            16 -> byteArrayOf(0x04) + addr + portBytes
            else -> {
                val host = (address.hostAddress ?: address.toString()).toByteArray(Charsets.US_ASCII)
                byteArrayOf(0x03, host.size.toByte()) + host + portBytes
            }
        }
    }

    private fun InputStream.readFullyStrict(buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = read(buf, off, buf.size - off)
            if (n < 0) error("unexpected eof")
            off += n
        }
    }

    data class TrafficStats(
        val txBytes: Long,
        val rxBytes: Long,
        val connectOk: Long = 0,
        val connectFail: Long = 0,
        val dnsOk: Long = 0,
        val dnsFail: Long = 0,
        val activeClients: Int = 0,
        val clientSockets: Int = 0,
        val remoteSockets: Int = 0,
        val threads: Int = 0
    )

    private data class SocksRequest(
        val cmd: Int,
        val rawAddr: ByteArray,
        val portBytes: ByteArray,
        val host: String,
        val port: Int
    )
}
