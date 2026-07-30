package app.smugly.desktop

import app.smugly.tunnel.SocksProtocol
import app.smugly.tunnel.SocksProtocol.ParseResult
import app.smugly.platform.LogLevel
import app.smugly.platform.PlatformLog
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Local "mixed" proxy: one port that speaks **both HTTP and SOCKS5** to apps and forwards
 * everything to an upstream SOCKS5 server (the engine process).
 *
 * One port for both is what Windows needs in practice — the system proxy setting is an HTTP proxy
 * address, while apps configured by hand usually want SOCKS5. The protocol is decided from the
 * first byte (0x05 = SOCKS5, anything else = HTTP), exactly like sing-box's mixed inbound.
 *
 * Single selector thread, same shape as the Android `MiniSlipstreamSocksBridge`: backpressure is
 * expressed only through interestOps, so a slow tunnel never blocks a thread — it just stops us
 * reading from the corresponding peer.
 */
class MixedProxyServer(
    private val listenHost: String,
    private val listenPort: Int,
    private val upstreamHost: String,
    private val upstreamPort: Int,
    /** Credentials the engine's SOCKS5 expects, if any. */
    private val upstreamUser: String? = null,
    private val upstreamPass: String? = null,
    /** Credentials we demand from local clients, if any. */
    private val localUser: String? = null,
    private val localPass: String? = null,
    private val maxActiveClients: Int = 128
) {
    private companion object {
        const val TAG = "MixedProxy"
        const val RELAY_BUF = 64 * 1024
        const val HS_BUF = HttpProxyProtocol.MAX_HEAD
        const val SELECT_TIMEOUT_MS = 5_000L
        const val IDLE_MS = 300_000L
        const val HANDSHAKE_TIMEOUT_MS = 30_000L
    }

    private enum class Phase {
        DETECT,
        S_GREETING, S_AUTH, S_REQUEST,
        H_HEAD,
        UP_CONNECTING, UP_GREET, UP_AUTH, UP_CMD,
        RELAY
    }

    private class Conn(val client: SocketChannel) {
        var remote: SocketChannel? = null
        var clientKey: SelectionKey? = null
        var remoteKey: SelectionKey? = null
        var phase = Phase.DETECT
        val hs: ByteBuffer = ByteBuffer.allocate(HS_BUF)
        val toRemote: ByteBuffer = ByteBuffer.allocate(RELAY_BUF)
        val toClient: ByteBuffer = ByteBuffer.allocate(RELAY_BUF)
        var clientEof = false
        var remoteEof = false
        var closeAfterFlush = false
        var cmd = 1
        var rawAddr: ByteArray = ByteArray(0)
        var portBytes: ByteArray = ByteArray(0)
        var earlyData: ByteArray? = null
        /** Set for HTTP clients so the success reply is an HTTP response, not a SOCKS reply. */
        var http = false
        var httpConnect = false
        var target = "?"
        var lastActivityMs = 0L
        var createdAtMs = 0L
    }

    private val running = AtomicBoolean(false)
    private val txBytes = AtomicLong(0)
    private val rxBytes = AtomicLong(0)
    private val connectOk = AtomicLong(0)
    private val connectFail = AtomicLong(0)
    private val activeClients = AtomicInteger(0)

    @Volatile private var selector: Selector? = null
    @Volatile private var serverChannel: ServerSocketChannel? = null
    @Volatile private var loopThread: Thread? = null
    private val conns = HashSet<Conn>()

    fun isRunning(): Boolean = running.get()
    fun txBytes(): Long = txBytes.get()
    fun rxBytes(): Long = rxBytes.get()
    fun activeConnections(): Int = activeClients.get()
    fun connectOkCount(): Long = connectOk.get()
    fun connectFailCount(): Long = connectFail.get()

    fun start(): Result<Unit> {
        stop()
        txBytes.set(0); rxBytes.set(0); connectOk.set(0); connectFail.set(0); activeClients.set(0)
        return runCatching {
            val sel = Selector.open()
            val ssc = ServerSocketChannel.open()
            ssc.configureBlocking(false)
            ssc.setOption(StandardSocketOptions.SO_REUSEADDR, true)
            ssc.bind(InetSocketAddress(listenHost, listenPort))
            ssc.register(sel, SelectionKey.OP_ACCEPT)
            selector = sel
            serverChannel = ssc
            running.set(true)
            PlatformLog.log(
                LogLevel.INFO, TAG,
                "listen $listenHost:$listenPort -> socks5 $upstreamHost:$upstreamPort " +
                    "localAuth=${if (localUser != null) "basic" else "none"}"
            )
            loopThread = Thread({ loop() }, "smugly-mixed-proxy").also { it.isDaemon = true; it.start() }
            Unit
        }.onFailure {
            runCatching { selector?.close() }
            runCatching { serverChannel?.close() }
            selector = null; serverChannel = null; running.set(false)
            PlatformLog.log(LogLevel.ERROR, TAG, "bind $listenHost:$listenPort failed: ${it.message}")
        }
    }

    fun stop() {
        if (!running.getAndSet(false) && selector == null) return
        selector?.wakeup()
        runCatching { loopThread?.join(800) }
        loopThread = null
        runCatching { serverChannel?.close() }
        runCatching { selector?.close() }
        serverChannel = null
        selector = null
    }

    // ---- selector loop ----

    private fun loop() {
        val sel = selector ?: return
        try {
            while (running.get()) {
                sel.select(SELECT_TIMEOUT_MS)
                if (!running.get()) break
                val it = sel.selectedKeys().iterator()
                while (it.hasNext()) {
                    val key = it.next(); it.remove()
                    if (!key.isValid) continue
                    try {
                        when {
                            key.isAcceptable -> onAccept()
                            else -> {
                                val conn = key.attachment() as? Conn ?: continue
                                if (key.isConnectable) onConnectable(conn)
                                if (key.isValid && (key.isReadable || key.isWritable)) service(conn, key)
                            }
                        }
                    } catch (e: Throwable) {
                        (key.attachment() as? Conn)?.let { c -> closeConn(c, "key error: ${e.message}") }
                    }
                }
                reapIdle()
            }
        } catch (e: Throwable) {
            if (running.get()) PlatformLog.log(LogLevel.WARN, TAG, "selector loop error: ${e.message}")
        } finally {
            for (c in conns.toList()) closeConn(c, "server stopping")
            conns.clear()
            runCatching { serverChannel?.close() }
            runCatching { sel.close() }
        }
    }

    private fun onAccept() {
        val ssc = serverChannel ?: return
        while (true) {
            val ch = ssc.accept() ?: break
            if (activeClients.get() >= maxActiveClients) {
                runCatching { ch.close() }
                PlatformLog.log(LogLevel.WARN, TAG, "connection limit $maxActiveClients reached; dropped")
                continue
            }
            runCatching {
                ch.configureBlocking(false)
                configure(ch)
            }.onFailure { runCatching { ch.close() }; return@onAccept }
            val conn = Conn(ch)
            val t = System.currentTimeMillis()
            conn.lastActivityMs = t
            conn.createdAtMs = t
            conn.clientKey = ch.register(selector, SelectionKey.OP_READ, conn)
            conns.add(conn)
            activeClients.incrementAndGet()
        }
    }

    private fun configure(ch: SocketChannel) {
        runCatching { ch.setOption(StandardSocketOptions.TCP_NODELAY, true) }
        runCatching { ch.setOption(StandardSocketOptions.SO_KEEPALIVE, true) }
    }

    private fun service(conn: Conn, key: SelectionKey) {
        val isClientSide = key === conn.clientKey
        if (key.isWritable) {
            if (isClientSide) flush(conn.client, conn.toClient) else conn.remote?.let { flush(it, conn.toRemote) }
            if (conn.closeAfterFlush && conn.toClient.position() == 0) {
                closeConn(conn, "closed after flush"); return
            }
        }
        if (key.isReadable) {
            when (conn.phase) {
                Phase.DETECT, Phase.S_GREETING, Phase.S_AUTH, Phase.S_REQUEST, Phase.H_HEAD ->
                    if (isClientSide) readClientHandshake(conn)
                Phase.UP_CONNECTING -> { /* driven by onConnectable */ }
                Phase.UP_GREET, Phase.UP_AUTH, Phase.UP_CMD ->
                    if (!isClientSide) readUpstreamHandshake(conn)
                Phase.RELAY -> relay(conn)
            }
        }
        if (conn.phase != Phase.RELAY) updateInterest(conn)
    }

    // ---- client side ----

    private fun readClientHandshake(conn: Conn) {
        val n = readInto(conn.client, conn.hs)
        if (n == -1) { closeConn(conn, "client eof during handshake"); return }
        if (n > 0) touch(conn)
        var progressed = true
        while (progressed) {
            progressed = false
            val arr = conn.hs.array()
            val len = conn.hs.position()
            when (conn.phase) {
                Phase.DETECT -> if (len >= 1) {
                    conn.phase = if (HttpProxyProtocol.looksLikeHttp(arr[0].toInt() and 0xFF)) {
                        conn.http = true
                        Phase.H_HEAD
                    } else {
                        Phase.S_GREETING
                    }
                    progressed = true
                }
                Phase.S_GREETING -> when (val r = SocksProtocol.parseClientGreeting(arr, len)) {
                    is ParseResult.NeedMore -> {}
                    is ParseResult.Bad -> closeConn(conn, "greeting: ${r.reason}")
                    is ParseResult.Ok -> {
                        consume(conn.hs, r.consumed)
                        val needAuth = !localUser.isNullOrBlank() && !localPass.isNullOrBlank()
                        if (needAuth) {
                            if (r.value.contains(0x02.toByte())) {
                                queue(conn.toClient, byteArrayOf(0x05, 0x02)); conn.phase = Phase.S_AUTH; progressed = true
                            } else rejectSocks(conn)
                        } else {
                            if (r.value.contains(0x00.toByte())) {
                                queue(conn.toClient, byteArrayOf(0x05, 0x00)); conn.phase = Phase.S_REQUEST; progressed = true
                            } else rejectSocks(conn)
                        }
                    }
                }
                Phase.S_AUTH -> when (val r = SocksProtocol.parseClientAuth(arr, len)) {
                    is ParseResult.NeedMore -> {}
                    is ParseResult.Bad -> closeConn(conn, "auth: ${r.reason}")
                    is ParseResult.Ok -> {
                        consume(conn.hs, r.consumed)
                        val ok = r.value.first == localUser.orEmpty() && r.value.second == localPass.orEmpty()
                        queue(conn.toClient, byteArrayOf(0x01, if (ok) 0x00 else 0x01))
                        if (ok) { conn.phase = Phase.S_REQUEST; progressed = true } else conn.closeAfterFlush = true
                    }
                }
                Phase.S_REQUEST -> when (val r = SocksProtocol.parseClientRequest(arr, len)) {
                    is ParseResult.NeedMore -> {}
                    is ParseResult.Bad -> { queue(conn.toClient, SocksProtocol.clientReply(0x07)); conn.closeAfterFlush = true }
                    is ParseResult.Ok -> {
                        consume(conn.hs, r.consumed)
                        if (r.value.cmd != 0x01) {
                            // Only CONNECT: BIND/UDP-ASSOCIATE make no sense for a system proxy.
                            queue(conn.toClient, SocksProtocol.clientReply(0x07)); conn.closeAfterFlush = true
                        } else {
                            conn.cmd = r.value.cmd
                            conn.rawAddr = r.value.rawAddr
                            conn.portBytes = r.value.portBytes
                            conn.target = r.value.host
                            if (conn.hs.position() > 0) {
                                conn.earlyData = conn.hs.array().copyOf(conn.hs.position()); conn.hs.clear()
                            }
                            startUpstream(conn)
                        }
                    }
                }
                Phase.H_HEAD -> {
                    val auth = if (!localUser.isNullOrBlank() && !localPass.isNullOrBlank()) {
                        localUser to localPass
                    } else null
                    when (val r = HttpProxyProtocol.parse(arr, len, auth)) {
                        is HttpProxyProtocol.Result.NeedMore -> {}
                        is HttpProxyProtocol.Result.Reject -> {
                            PlatformLog.log(LogLevel.WARN, TAG, "http reject: ${r.reason}")
                            queue(conn.toClient, r.status); conn.closeAfterFlush = true
                        }
                        is HttpProxyProtocol.Result.Ok -> {
                            val (raw, portBytes) = HttpProxyProtocol.socksAddress(r.host, r.port)
                            conn.cmd = 0x01
                            conn.rawAddr = raw
                            conn.portBytes = portBytes
                            conn.httpConnect = r.isConnect
                            conn.target = "${r.host}:${r.port}"
                            // Body / pipelined bytes past the head travel after the rewritten head.
                            val rest = if (conn.hs.position() > r.consumed) {
                                conn.hs.array().copyOfRange(r.consumed, conn.hs.position())
                            } else ByteArray(0)
                            conn.earlyData = when {
                                r.upstreamHead == null -> rest.takeIf { it.isNotEmpty() }
                                else -> r.upstreamHead + rest
                            }
                            conn.hs.clear()
                            startUpstream(conn)
                        }
                    }
                }
                else -> {}
            }
            flush(conn.client, conn.toClient)
        }
        if (conn.closeAfterFlush && conn.toClient.position() == 0) {
            closeConn(conn, "rejected"); return
        }
        if (conn.phase != Phase.RELAY) updateInterest(conn)
    }

    private fun rejectSocks(conn: Conn) {
        queue(conn.toClient, byteArrayOf(0x05, 0xFF.toByte()))
        conn.closeAfterFlush = true
    }

    // ---- upstream side ----

    private fun startUpstream(conn: Conn) {
        try {
            val remote = SocketChannel.open()
            remote.configureBlocking(false)
            configure(remote)
            conn.remote = remote
            conn.phase = Phase.UP_CONNECTING
            conn.remoteKey = remote.register(selector, SelectionKey.OP_CONNECT, conn)
            remote.connect(InetSocketAddress(upstreamHost, upstreamPort))
            touch(conn)
        } catch (e: Throwable) {
            failUpstream(conn, "open failed: ${e.message}")
        }
    }

    private fun onConnectable(conn: Conn) {
        val remote = conn.remote ?: return
        try {
            if (remote.finishConnect()) {
                queue(conn.toRemote, SocksProtocol.upstreamGreeting(upstreamUser != null && upstreamPass != null))
                conn.phase = Phase.UP_GREET
                conn.hs.clear()
                touch(conn)
                updateInterest(conn)
            }
        } catch (e: Throwable) {
            failUpstream(conn, "connect failed: ${e.message}")
        }
    }

    private fun readUpstreamHandshake(conn: Conn) {
        val remote = conn.remote ?: return
        val n = readInto(remote, conn.hs)
        if (n == -1) { failUpstream(conn, "upstream eof during handshake"); return }
        if (n > 0) touch(conn)
        var progressed = true
        while (progressed) {
            progressed = false
            val arr = conn.hs.array(); val len = conn.hs.position()
            when (conn.phase) {
                Phase.UP_GREET -> when (val r = SocksProtocol.parseUpstreamGreetingReply(arr, len)) {
                    is ParseResult.NeedMore -> {}
                    is ParseResult.Bad -> failUpstream(conn, r.reason)
                    is ParseResult.Ok -> {
                        consume(conn.hs, r.consumed)
                        if (r.value == 0x02) {
                            queue(conn.toRemote, SocksProtocol.upstreamAuth(upstreamUser.orEmpty(), upstreamPass.orEmpty()))
                            conn.phase = Phase.UP_AUTH
                        } else {
                            queue(conn.toRemote, SocksProtocol.upstreamCommand(conn.cmd, conn.rawAddr, conn.portBytes))
                            conn.phase = Phase.UP_CMD
                        }
                        progressed = true
                    }
                }
                Phase.UP_AUTH -> when (val r = SocksProtocol.parseUpstreamAuthReply(arr, len)) {
                    is ParseResult.NeedMore -> {}
                    is ParseResult.Bad -> failUpstream(conn, r.reason)
                    is ParseResult.Ok -> {
                        consume(conn.hs, r.consumed)
                        queue(conn.toRemote, SocksProtocol.upstreamCommand(conn.cmd, conn.rawAddr, conn.portBytes))
                        conn.phase = Phase.UP_CMD; progressed = true
                    }
                }
                Phase.UP_CMD -> when (val r = SocksProtocol.parseUpstreamCommandReply(arr, len)) {
                    is ParseResult.NeedMore -> {}
                    is ParseResult.Bad -> failUpstream(conn, r.reason)
                    is ParseResult.Ok -> {
                        consume(conn.hs, r.consumed)
                        connectOk.incrementAndGet()
                        // Success reply depends on which protocol the client speaks. An absolute-form
                        // HTTP request gets nothing here — its response comes from the origin.
                        when {
                            !conn.http -> queue(conn.toClient, SocksProtocol.clientReply(0x00))
                            conn.httpConnect -> queue(conn.toClient, HttpProxyProtocol.connectEstablished())
                        }
                        conn.earlyData?.let { queue(conn.toRemote, it) }
                        conn.earlyData = null
                        if (conn.hs.position() > 0) {
                            queue(conn.toClient, conn.hs.array().copyOf(conn.hs.position())); conn.hs.clear()
                        }
                        conn.phase = Phase.RELAY
                        relay(conn)
                        return
                    }
                }
                else -> {}
            }
            conn.remote?.let { flush(it, conn.toRemote) }
        }
        if (conn.phase != Phase.RELAY) updateInterest(conn)
    }

    private fun failUpstream(conn: Conn, reason: String) {
        connectFail.incrementAndGet()
        PlatformLog.log(LogLevel.WARN, TAG, "upstream ${conn.target} failed: $reason")
        runCatching { conn.remote?.close() }
        conn.remote = null; conn.remoteKey?.cancel(); conn.remoteKey = null
        if (conn.toClient.position() == 0) {
            queue(
                conn.toClient,
                if (conn.http) HttpProxyProtocol.badGateway("tunnel upstream unavailable: $reason")
                else SocksProtocol.clientReply(0x05)
            )
        }
        conn.closeAfterFlush = true
        conn.phase = Phase.S_REQUEST
        flush(conn.client, conn.toClient)
        if (conn.toClient.position() == 0) closeConn(conn, "upstream failed") else updateInterest(conn)
    }

    // ---- relay ----

    private fun relay(conn: Conn) {
        val remote = conn.remote ?: run { closeConn(conn, "relay without remote"); return }
        if (!conn.clientEof && conn.toRemote.hasRemaining()) {
            when (val n = readInto(conn.client, conn.toRemote)) {
                -1 -> {
                    conn.clientEof = true
                    runCatching { remote.shutdownOutput() }
                }
                else -> if (n > 0) { txBytes.addAndGet(n.toLong()); touch(conn) }
            }
        }
        if (!conn.remoteEof && conn.toClient.hasRemaining()) {
            when (val n = readInto(remote, conn.toClient)) {
                -1 -> {
                    conn.remoteEof = true
                    runCatching { conn.client.shutdownOutput() }
                }
                else -> if (n > 0) { rxBytes.addAndGet(n.toLong()); touch(conn) }
            }
        }
        flush(remote, conn.toRemote)
        flush(conn.client, conn.toClient)
        if (conn.clientEof && conn.remoteEof && conn.toRemote.position() == 0 && conn.toClient.position() == 0) {
            closeConn(conn, "both sides closed"); return
        }
        updateInterest(conn)
    }

    private fun updateInterest(conn: Conn) {
        val ck = conn.clientKey
        if (ck != null && ck.isValid) {
            var ops = 0
            when (conn.phase) {
                Phase.DETECT, Phase.S_GREETING, Phase.S_AUTH, Phase.S_REQUEST, Phase.H_HEAD ->
                    if (!conn.closeAfterFlush) ops = ops or SelectionKey.OP_READ
                Phase.RELAY -> if (!conn.clientEof && conn.toRemote.hasRemaining()) ops = ops or SelectionKey.OP_READ
                else -> {}
            }
            if (conn.toClient.position() > 0) ops = ops or SelectionKey.OP_WRITE
            runCatching { ck.interestOps(ops) }
        }
        val rk = conn.remoteKey
        if (rk != null && rk.isValid) {
            var ops = 0
            when (conn.phase) {
                Phase.UP_CONNECTING -> ops = ops or SelectionKey.OP_CONNECT
                Phase.UP_GREET, Phase.UP_AUTH, Phase.UP_CMD -> ops = ops or SelectionKey.OP_READ
                Phase.RELAY -> if (!conn.remoteEof && conn.toClient.hasRemaining()) ops = ops or SelectionKey.OP_READ
                else -> {}
            }
            if (conn.toRemote.position() > 0) ops = ops or SelectionKey.OP_WRITE
            runCatching { rk.interestOps(ops) }
        }
    }

    private fun reapIdle() {
        if (conns.isEmpty()) return
        val now = System.currentTimeMillis()
        for (c in conns.toList()) {
            val idle = now - c.lastActivityMs
            val stuckInHandshake = c.phase != Phase.RELAY && now - c.createdAtMs > HANDSHAKE_TIMEOUT_MS
            if (stuckInHandshake) closeConn(c, "handshake timeout")
            else if (idle > IDLE_MS) closeConn(c, "idle ${idle}ms")
        }
    }

    // ---- buffer helpers (buffers are kept in WRITE mode; position = pending bytes) ----

    private fun readInto(ch: SocketChannel, buf: ByteBuffer): Int =
        if (!buf.hasRemaining()) 0 else runCatching { ch.read(buf) }.getOrElse { -1 }

    private fun flush(ch: SocketChannel, buf: ByteBuffer) {
        if (buf.position() == 0) return
        buf.flip()
        runCatching { ch.write(buf) }
        buf.compact()
    }

    private fun queue(buf: ByteBuffer, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        if (buf.remaining() < bytes.size) return // caller-side overflow: drop, connection will fail
        buf.put(bytes)
    }

    private fun consume(buf: ByteBuffer, count: Int) {
        if (count <= 0) return
        buf.flip()
        buf.position(count)
        buf.compact()
    }

    private fun touch(conn: Conn) { conn.lastActivityMs = System.currentTimeMillis() }

    private fun closeConn(conn: Conn, reason: String) {
        if (!conns.remove(conn)) return
        activeClients.decrementAndGet()
        runCatching { conn.clientKey?.cancel() }
        runCatching { conn.remoteKey?.cancel() }
        runCatching { conn.client.close() }
        runCatching { conn.remote?.close() }
        if (conn.phase != Phase.RELAY) {
            PlatformLog.log(LogLevel.DEBUG, TAG, "close ${conn.target}: $reason")
        }
    }
}
