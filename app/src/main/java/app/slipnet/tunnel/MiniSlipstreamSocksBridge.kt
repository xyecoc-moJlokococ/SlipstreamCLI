package app.slipnet.tunnel

import app.slipnet.tunnel.SocksProtocol.ParseResult
import app.slipnet.util.AppLog
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
 * Non-blocking (NIO) SOCKS5 bridge. A SINGLE selector thread services all connections, replacing
 * the old thread-per-connection design (2 threads + a 30s blocking read-timeout per connection),
 * which piled up hundreds of leaked threads/fds and CLOSE-WAIT sockets under a slow DNS carrier --
 * the root cause of the phone-heating incident (confirmed by a debuggerd dump: 116 threads parked
 * in blocking SocketInputStream.socketRead, growing 38->116 between snapshots).
 *
 * Each connection is a state machine: client SOCKS handshake -> connect+handshake to the local
 * slipstream SOCKS server -> bidirectional relay. Backpressure is expressed purely through
 * interestOps (stop reading a side while its outbound buffer is full), so a slow upstream can never
 * block a thread -- it just stops us reading from the corresponding peer. A time-based idle reaper
 * closes connections that make no progress, so stuck/orphaned connections can't accumulate.
 *
 * Public API (start/stop/isRunning/stats/TrafficStats) is unchanged so TinyVpnService needs no edits.
 */
object MiniSlipstreamSocksBridge {
    private const val TAG = "MiniSlipstreamSocksBridge"
    private const val RELAY_BUF = 64 * 1024
    private const val HS_BUF = 1024 // SOCKS handshake messages are tiny (<~520 bytes)
    const val DEFAULT_MAX_ACTIVE_CLIENTS = 48
    // Idle reaper timeouts. A fully-open connection is reaped only after a long quiet stretch; a
    // HALF-closed one (one side already EOF) is reaped much sooner -- otherwise a byte-trickle
    // download over a degraded carrier keeps refreshing activity forever and the half-open socket
    // lingers in CLOSE-WAIT, which is exactly what accumulated in the field after the NIO rewrite.
    private const val FULL_IDLE_MS = 90_000L
    private const val HALF_IDLE_MS = 15_000L
    private const val HALF_MAX_MS = 45_000L
    // A relay buffer that can't drain for this long means the far side (slipstream over a dead
    // carrier, or a departed client) isn't accepting -- force the connection closed. Also the only
    // way to reclaim a CLOSE-WAIT whose FIN we never read because write-backpressure stopped us.
    private const val STUCK_MS = 30_000L
    // Hard ceiling on any single connection lifetime (fully open or half-closed). Prevents
    // pathological linger when activity trickles and half-closed status was never armed.
    private const val MAX_AGE_MS = 120_000L
    private const val SELECT_TIMEOUT_MS = 5_000L

    private enum class Phase {
        CLIENT_GREETING, CLIENT_AUTH, CLIENT_REQUEST,
        UPSTREAM_CONNECTING, UPSTREAM_GREETING, UPSTREAM_AUTH, UPSTREAM_COMMAND,
        RELAY
    }

    private class Conn(val client: SocketChannel) {
        var remote: SocketChannel? = null
        var clientKey: SelectionKey? = null
        var remoteKey: SelectionKey? = null
        var phase = Phase.CLIENT_GREETING
        val hs = ByteBuffer.allocate(HS_BUF) // accumulates the current handshake message
        val toRemote = ByteBuffer.allocate(RELAY_BUF) // pending bytes to write to remote (write-mode)
        val toClient = ByteBuffer.allocate(RELAY_BUF) // pending bytes to write to client (write-mode)
        var clientEof = false
        var remoteEof = false
        var closeAfterFlush = false // flush toClient (e.g. an error reply) then close
        var cmd = 0
        var rawAddr: ByteArray = ByteArray(0)
        var portBytes: ByteArray = ByteArray(0)
        var earlyData: ByteArray? = null // client bytes received before relay began (rare pipelining)
        var lastActivityMs = 0L
        var createdAtMs = 0L
        var halfClosedAtMs = 0L // set when the first side hits EOF; 0 while fully open
        var bufferStuckSinceMs = 0L // set while a relay buffer stays non-empty; 0 when both drain
    }

    private val running = AtomicBoolean(false)
    private val txBytes = AtomicLong(0)
    private val rxBytes = AtomicLong(0)
    private val connectOk = AtomicLong(0)
    private val connectFail = AtomicLong(0)
    private val activeClients = AtomicInteger(0)
    private val halfClosedClients = AtomicInteger(0)

    @Volatile private var selector: Selector? = null
    @Volatile private var serverChannel: ServerSocketChannel? = null
    @Volatile private var loopThread: Thread? = null
    @Volatile private var slipstreamHost = "127.0.0.1"
    @Volatile private var slipstreamPort = 1081
    @Volatile private var dnsHost = ""
    @Volatile private var username: String? = null
    @Volatile private var password: String? = null
    @Volatile private var localUsername: String? = null
    @Volatile private var localPassword: String? = null
    @Volatile private var maxActiveClients = DEFAULT_MAX_ACTIVE_CLIENTS

    // Live set of connections, only ever touched on the selector thread.
    private val conns = HashSet<Conn>()

    fun start(
        listenHost: String,
        listenPort: Int,
        slipstreamHost: String,
        slipstreamPort: Int,
        dnsHost: String,
        username: String?,
        password: String?,
        localUsername: String? = null,
        localPassword: String? = null,
        maxActiveClients: Int = DEFAULT_MAX_ACTIVE_CLIENTS
    ): Result<Unit> {
        stop()
        this.maxActiveClients = maxActiveClients.coerceAtLeast(1)
        this.slipstreamHost = slipstreamHost
        this.slipstreamPort = slipstreamPort
        this.dnsHost = dnsHost.trim()
        this.username = username?.takeIf { it.isNotBlank() }
        this.password = password?.takeIf { it.isNotBlank() }
        this.localUsername = localUsername?.takeIf { it.isNotBlank() }
        this.localPassword = localPassword?.takeIf { it.isNotBlank() }
        txBytes.set(0); rxBytes.set(0); connectOk.set(0); connectFail.set(0)
        activeClients.set(0); halfClosedClients.set(0)

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
            AppLog.i(
                TAG,
                "bridge start (nio) listen=$listenHost:$listenPort slipstream=$slipstreamHost:$slipstreamPort " +
                    "transportDns=$dnsHost upstreamAuth=${if (this.username != null) "login/password" else "no-auth"} " +
                    "localAuth=${if (this.localUsername != null && this.localPassword != null) "login/password" else "no-auth"}"
            )
            loopThread = Thread({ loop() }, "nio-slip-bridge").also { it.isDaemon = true; it.start() }
        }.onFailure {
            runCatching { selector?.close() }
            runCatching { serverChannel?.close() }
            selector = null; serverChannel = null; running.set(false)
        }
    }

    fun stop() {
        if (!running.getAndSet(false) && selector == null) return
        AppLog.i(
            TAG,
            "bridge stop (nio) tx=${txBytes.get()} rx=${rxBytes.get()} connectOk=${connectOk.get()} " +
                "connectFail=${connectFail.get()} active=${activeClients.get()}"
        )
        selector?.wakeup()
        runCatching { loopThread?.join(500) }
        loopThread = null
        // The loop closes everything on exit; also close here in case it never ran.
        runCatching { serverChannel?.close() }
        runCatching { selector?.close() }
        serverChannel = null
        selector = null
    }

    fun isRunning(): Boolean = running.get()

    fun stats(): TrafficStats {
        val active = activeClients.get()
        return TrafficStats(
            txBytes.get(), rxBytes.get(), connectOk.get(), connectFail.get(),
            dnsOk = 0, dnsFail = 0,
            activeClients = active, clientSockets = active, remoteSockets = active,
            threads = if (running.get()) 1 else 0,
            halfClosedClients = halfClosedClients.get()
        )
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
                                if (key.isValid && (key.isReadable || key.isWritable)) service(conn)
                            }
                        }
                    } catch (e: Throwable) {
                        (key.attachment() as? Conn)?.let { c -> closeConn(c, "key error: ${e.message}") }
                    }
                }
                reapIdle()
            }
        } catch (e: Throwable) {
            if (running.get()) AppLog.w(TAG, "selector loop error: ${e.message}")
        } finally {
            for (c in conns.toList()) closeConn(c, "bridge stopping")
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
                evictLeastRecentlyActive()
            }
            runCatching {
                ch.configureBlocking(false)
                configure(ch)
            }.onFailure { runCatching { ch.close() }; return@onAccept }
            val conn = Conn(ch)
            val t = now()
            conn.lastActivityMs = t
            conn.createdAtMs = t
            conn.clientKey = ch.register(selector, SelectionKey.OP_READ, conn)
            conns.add(conn)
            activeClients.incrementAndGet()
        }
    }

    private fun onConnectable(conn: Conn) {
        val remote = conn.remote ?: return
        try {
            if (remote.finishConnect()) {
                // Connected. Send the upstream greeting and start the upstream handshake.
                queue(conn.toRemote, SocksProtocol.upstreamGreeting(username != null && password != null))
                conn.phase = Phase.UPSTREAM_GREETING
                conn.hs.clear()
                touch(conn)
                updateInterest(conn)
            }
        } catch (e: Throwable) {
            failUpstream(conn, "upstream connect failed: ${e.message}")
        }
    }

    /** Attempt all currently-possible reads/writes for a connection, then recompute interest and
     * check for termination. Non-blocking ops that aren't ready simply move 0 bytes, so it's safe
     * to call opportunistically. */
    private fun service(conn: Conn) {
        try {
            when (conn.phase) {
                Phase.CLIENT_GREETING, Phase.CLIENT_AUTH, Phase.CLIENT_REQUEST -> {
                    flush(conn.client, conn.toClient) // e.g. a queued method-select / error reply
                    if (conn.closeAfterFlush) { if (conn.toClient.position() == 0) closeConn(conn, "handshake reply flushed"); return }
                    readClientHandshake(conn)
                }
                Phase.UPSTREAM_CONNECTING -> { /* driven by onConnectable */ }
                Phase.UPSTREAM_GREETING, Phase.UPSTREAM_AUTH, Phase.UPSTREAM_COMMAND -> {
                    conn.remote?.let { flush(it, conn.toRemote) }
                    flush(conn.client, conn.toClient)
                    if (conn.closeAfterFlush) { if (conn.toClient.position() == 0) closeConn(conn, "upstream error flushed"); return }
                    readUpstreamHandshake(conn)
                }
                Phase.RELAY -> relay(conn)
            }
            if (conn.phase != Phase.RELAY) updateInterest(conn)
        } catch (e: Throwable) {
            closeConn(conn, "service error: ${e.message}")
        }
    }

    // ---- client handshake ----

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
                Phase.CLIENT_GREETING -> when (val r = SocksProtocol.parseClientGreeting(arr, len)) {
                    is ParseResult.NeedMore -> {}
                    is ParseResult.Bad -> closeConn(conn, "greeting: ${r.reason}")
                    is ParseResult.Ok -> {
                        consume(conn.hs, r.consumed)
                        val needAuth = !localUsername.isNullOrBlank() && !localPassword.isNullOrBlank()
                        if (needAuth) {
                            if (r.value.contains(0x02.toByte())) {
                                queue(conn.toClient, byteArrayOf(0x05, 0x02)); conn.phase = Phase.CLIENT_AUTH; progressed = true
                            } else rejectClient(conn)
                        } else {
                            if (r.value.contains(0x00.toByte())) {
                                queue(conn.toClient, byteArrayOf(0x05, 0x00)); conn.phase = Phase.CLIENT_REQUEST; progressed = true
                            } else rejectClient(conn)
                        }
                    }
                }
                Phase.CLIENT_AUTH -> when (val r = SocksProtocol.parseClientAuth(arr, len)) {
                    is ParseResult.NeedMore -> {}
                    is ParseResult.Bad -> closeConn(conn, "auth: ${r.reason}")
                    is ParseResult.Ok -> {
                        consume(conn.hs, r.consumed)
                        val ok = r.value.first == localUsername.orEmpty() && r.value.second == localPassword.orEmpty()
                        queue(conn.toClient, byteArrayOf(0x01, if (ok) 0x00 else 0x01))
                        if (ok) { conn.phase = Phase.CLIENT_REQUEST; progressed = true } else conn.closeAfterFlush = true
                    }
                }
                Phase.CLIENT_REQUEST -> when (val r = SocksProtocol.parseClientRequest(arr, len)) {
                    is ParseResult.NeedMore -> {}
                    is ParseResult.Bad -> { queue(conn.toClient, SocksProtocol.clientReply(0x07)); conn.closeAfterFlush = true }
                    is ParseResult.Ok -> {
                        consume(conn.hs, r.consumed)
                        conn.cmd = r.value.cmd; conn.rawAddr = r.value.rawAddr; conn.portBytes = r.value.portBytes
                        // Any leftover client bytes are early app data; stash to inject after relay starts.
                        if (conn.hs.position() > 0) {
                            conn.earlyData = conn.hs.array().copyOf(conn.hs.position()); conn.hs.clear()
                        }
                        startUpstream(conn)
                    }
                }
                else -> {}
            }
            flush(conn.client, conn.toClient)
        }
        if (conn.phase != Phase.RELAY) updateInterest(conn)
    }

    private fun rejectClient(conn: Conn) {
        queue(conn.toClient, byteArrayOf(0x05, 0xFF.toByte()))
        conn.closeAfterFlush = true
    }

    private fun startUpstream(conn: Conn) {
        try {
            val remote = SocketChannel.open()
            remote.configureBlocking(false)
            configure(remote)
            conn.remote = remote
            conn.phase = Phase.UPSTREAM_CONNECTING
            conn.remoteKey = remote.register(selector, SelectionKey.OP_CONNECT, conn)
            remote.connect(InetSocketAddress(slipstreamHost, slipstreamPort))
            touch(conn)
        } catch (e: Throwable) {
            failUpstream(conn, "upstream open failed: ${e.message}")
        }
    }

    // ---- upstream handshake ----

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
                Phase.UPSTREAM_GREETING -> when (val r = SocksProtocol.parseUpstreamGreetingReply(arr, len)) {
                    is ParseResult.NeedMore -> {}
                    is ParseResult.Bad -> failUpstream(conn, r.reason)
                    is ParseResult.Ok -> {
                        consume(conn.hs, r.consumed)
                        if (r.value == 0x02) {
                            queue(conn.toRemote, SocksProtocol.upstreamAuth(username.orEmpty(), password.orEmpty()))
                            conn.phase = Phase.UPSTREAM_AUTH
                        } else {
                            queue(conn.toRemote, SocksProtocol.upstreamCommand(conn.cmd, conn.rawAddr, conn.portBytes))
                            conn.phase = Phase.UPSTREAM_COMMAND
                        }
                        progressed = true
                    }
                }
                Phase.UPSTREAM_AUTH -> when (val r = SocksProtocol.parseUpstreamAuthReply(arr, len)) {
                    is ParseResult.NeedMore -> {}
                    is ParseResult.Bad -> failUpstream(conn, r.reason)
                    is ParseResult.Ok -> {
                        consume(conn.hs, r.consumed)
                        queue(conn.toRemote, SocksProtocol.upstreamCommand(conn.cmd, conn.rawAddr, conn.portBytes))
                        conn.phase = Phase.UPSTREAM_COMMAND; progressed = true
                    }
                }
                Phase.UPSTREAM_COMMAND -> when (val r = SocksProtocol.parseUpstreamCommandReply(arr, len)) {
                    is ParseResult.NeedMore -> {}
                    is ParseResult.Bad -> failUpstream(conn, r.reason)
                    is ParseResult.Ok -> {
                        consume(conn.hs, r.consumed)
                        connectOk.incrementAndGet()
                        AppLog.d(TAG, "CONNECT ${hostLabel(conn)} OK")
                        // Tell tun2socks the tunnel is up, then begin relaying.
                        queue(conn.toClient, SocksProtocol.clientReply(0x00))
                        // Inject any early client data (pipelined before our reply) ahead of relay.
                        conn.earlyData?.let { queue(conn.toRemote, it) }
                        conn.earlyData = null
                        // Any leftover upstream bytes after the reply are real downstream data.
                        if (conn.hs.position() > 0) { queue(conn.toClient, conn.hs.array().copyOf(conn.hs.position())); conn.hs.clear() }
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
        AppLog.w(TAG, "upstream failed ${hostLabel(conn)}: $reason")
        runCatching { conn.remote?.close() }
        conn.remote = null; conn.remoteKey?.cancel(); conn.remoteKey = null
        // Best-effort tell the client it failed, then close.
        if (conn.toClient.position() == 0) queue(conn.toClient, SocksProtocol.clientReply(0x05))
        conn.closeAfterFlush = true
        conn.phase = Phase.CLIENT_REQUEST // so service() flushes toClient then closes
        flush(conn.client, conn.toClient)
        if (conn.toClient.position() == 0) closeConn(conn, "upstream failed")
        else updateInterest(conn)
    }

    // ---- relay ----

    private fun relay(conn: Conn) {
        val remote = conn.remote ?: run { closeConn(conn, "relay without remote"); return }
        var progress = false
        // client -> remote. When toRemote is full, stop reading (TCP backpressure). Do NOT probe-read
        // for FIN here: under upload the kernel still delivers more data while the app buffer is
        // full, and a 1-byte probe that then "overflows" would kill the connection (speedtest
        // upload regression). FIN is seen once space frees; stuck-buffer / half / max-age reapers
        // cover the rare case where we never drain.
        if (!conn.clientEof && conn.toRemote.hasRemaining()) {
            val n = readInto(conn.client, conn.toRemote)
            if (n == -1) { conn.clientEof = true; markHalfClosed(conn) }
            else if (n > 0) { txBytes.addAndGet(n.toLong()); progress = true }
        }
        if (drain(remote, conn.toRemote)) progress = true
        // remote -> client
        if (!conn.remoteEof && conn.toClient.hasRemaining()) {
            val n = readInto(remote, conn.toClient)
            if (n == -1) { conn.remoteEof = true; markHalfClosed(conn) }
            else if (n > 0) { rxBytes.addAndGet(n.toLong()); progress = true }
        }
        if (drain(conn.client, conn.toClient)) progress = true
        if (progress) touch(conn)
        // Track how long a relay buffer stays non-empty. On a healthy localhost hop buffers clear
        // in microseconds; a buffer stuck for tens of seconds means the far side isn't accepting
        // (dead carrier / gone peer) -- and this is the ONLY signal when write-backpressure has
        // stopped us reading the client, so its FIN is never observed by the EOF path.
        if (conn.toRemote.position() > 0 || conn.toClient.position() > 0) {
            if (conn.bufferStuckSinceMs == 0L) conn.bufferStuckSinceMs = now()
        } else {
            conn.bufferStuckSinceMs = 0L
        }

        // Half-close propagation: once a side is EOF and its buffer is fully flushed, send FIN onward.
        if (conn.clientEof && conn.toRemote.position() == 0) runCatching { remote.shutdownOutput() }
        if (conn.remoteEof && conn.toClient.position() == 0) runCatching { conn.client.shutdownOutput() }

        // Done when both directions are closed and drained.
        if (conn.clientEof && conn.remoteEof && conn.toRemote.position() == 0 && conn.toClient.position() == 0) {
            closeConn(conn, "relay complete"); return
        }
        updateInterest(conn)
    }

    // ---- buffer/channel helpers ----

    /** Read from [ch] into [buf] (write-mode). Returns bytes read, 0 if none, -1 on EOF. */
    private fun readInto(ch: SocketChannel, buf: ByteBuffer): Int {
        if (!buf.hasRemaining()) return 0
        return ch.read(buf)
    }

    /** Flush pending bytes in [buf] (write-mode) to [ch]. Returns true if any bytes were written. */
    private fun drain(ch: SocketChannel, buf: ByteBuffer): Boolean {
        if (buf.position() == 0) return false
        buf.flip()
        val w = ch.write(buf)
        buf.compact()
        return w > 0
    }

    private fun flush(ch: SocketChannel, buf: ByteBuffer) { drain(ch, buf) }

    private fun queue(buf: ByteBuffer, bytes: ByteArray) {
        // Handshake/reply frames are tiny and buffers are large; a full buffer here would be a bug.
        if (buf.remaining() < bytes.size) {
            AppLog.w(TAG, "queue overflow need=${bytes.size} free=${buf.remaining()}")
            return
        }
        buf.put(bytes)
    }

    private fun consume(buf: ByteBuffer, count: Int) {
        val arr = buf.array(); val cur = buf.position()
        if (count >= cur) { buf.clear(); return }
        System.arraycopy(arr, count, arr, 0, cur - count)
        buf.position(cur - count)
    }

    // ---- interest management ----

    private fun updateInterest(conn: Conn) {
        val ck = conn.clientKey
        val rk = conn.remoteKey
        if (ck != null && ck.isValid) {
            var ops = 0
            when (conn.phase) {
                Phase.CLIENT_GREETING, Phase.CLIENT_AUTH, Phase.CLIENT_REQUEST ->
                    if (conn.hs.hasRemaining() && !conn.closeAfterFlush) ops = ops or SelectionKey.OP_READ
                // Only read when there is free buffer space -- otherwise TCP window applies
                // backpressure. Keeping OP_READ + probing when full broke upload (speedtest).
                Phase.RELAY -> if (!conn.clientEof && conn.toRemote.hasRemaining()) ops = ops or SelectionKey.OP_READ
                else -> {}
            }
            if (conn.toClient.position() > 0) ops = ops or SelectionKey.OP_WRITE
            ck.interestOps(ops)
        }
        if (rk != null && rk.isValid) {
            var ops = 0
            when (conn.phase) {
                Phase.UPSTREAM_CONNECTING -> ops = ops or SelectionKey.OP_CONNECT
                Phase.UPSTREAM_GREETING, Phase.UPSTREAM_AUTH, Phase.UPSTREAM_COMMAND ->
                    if (conn.hs.hasRemaining()) ops = ops or SelectionKey.OP_READ
                Phase.RELAY -> if (!conn.remoteEof && conn.toClient.hasRemaining()) ops = ops or SelectionKey.OP_READ
                else -> {}
            }
            if (conn.toRemote.position() > 0) ops = ops or SelectionKey.OP_WRITE
            rk.interestOps(ops)
        }
    }

    // ---- lifecycle / reaping ----

    private fun evictLeastRecentlyActive() {
        val victim = conns.minByOrNull { it.lastActivityMs } ?: return
        AppLog.w(TAG, "overload cleanup: closing least-recently-active conn active=${activeClients.get()}")
        closeConn(victim, "overload eviction")
    }

    private fun markHalfClosed(conn: Conn) {
        if (conn.halfClosedAtMs == 0L) {
            conn.halfClosedAtMs = now()
            halfClosedClients.incrementAndGet()
        }
    }

    private fun reapIdle() {
        if (conns.isEmpty()) return
        val now = now()
        val stale = conns.filter {
            ConnReaper.shouldReap(
                now, it.lastActivityMs, it.halfClosedAtMs, it.bufferStuckSinceMs,
                FULL_IDLE_MS, HALF_IDLE_MS, HALF_MAX_MS, STUCK_MS,
                it.createdAtMs, MAX_AGE_MS
            )
        }
        if (stale.isNotEmpty()) {
            AppLog.w(
                TAG,
                "reaping ${stale.size} idle conn(s) active=${activeClients.get()} " +
                    "halfClosed=${halfClosedClients.get()}"
            )
            for (c in stale) closeConn(c, "idle reaped")
        }
    }

    private fun closeConn(conn: Conn, reason: String) {
        if (!conns.remove(conn)) return
        AppLog.d(TAG, "close conn: $reason")
        if (conn.halfClosedAtMs > 0L) halfClosedClients.decrementAndGet()
        conn.clientKey?.cancel(); conn.remoteKey?.cancel()
        runCatching { conn.client.close() }
        runCatching { conn.remote?.close() }
        activeClients.decrementAndGet()
    }

    private fun configure(ch: SocketChannel) {
        runCatching { ch.setOption(StandardSocketOptions.TCP_NODELAY, true) }
        runCatching { ch.setOption(StandardSocketOptions.SO_RCVBUF, RELAY_BUF) }
        runCatching { ch.setOption(StandardSocketOptions.SO_SNDBUF, RELAY_BUF) }
    }

    private fun now() = System.currentTimeMillis()
    private fun touch(conn: Conn) { conn.lastActivityMs = now() }

    private fun hostLabel(conn: Conn): String {
        val raw = conn.rawAddr
        if (raw.isEmpty()) return "?"
        val port = if (conn.portBytes.size == 2)
            ((conn.portBytes[0].toInt() and 0xFF) shl 8) or (conn.portBytes[1].toInt() and 0xFF) else 0
        val host = when (raw[0].toInt() and 0xFF) {
            0x01 -> (1..4).joinToString(".") { (raw[it].toInt() and 0xFF).toString() }
            0x03 -> String(raw, 2, raw[1].toInt() and 0xFF, Charsets.US_ASCII)
            else -> "ipv6"
        }
        return "$host:$port"
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
        val threads: Int = 0,
        /** Connections with at least one side EOF (half-closed), for CLOSE-WAIT diagnostics. */
        val halfClosedClients: Int = 0
    )
}
