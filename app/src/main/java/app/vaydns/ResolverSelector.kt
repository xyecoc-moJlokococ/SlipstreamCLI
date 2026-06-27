package app.vaydns

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.slipnet.tunnel.MiniSlipstreamSocksBridge
import app.slipnet.tunnel.ResolverListConfig
import app.slipnet.tunnel.SlipstreamBridge
import app.slipnet.util.AppLog
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class ResolverChoice(
    val hosts: List<String>,
    val port: Int,
    val selectedHost: String,
    val source: String,
    val qnameMtu: Int = 0,
    val testedCount: Int = hosts.size,
    val aliveCount: Int = hosts.size,
    val skippedCount: Int = 0
)

object ResolverSelector {
    private const val TAG = "ResolverSelector"
    private const val CONNECT_TIMEOUT_MS = 1200
    private const val PROBE_THREADS = 8
    private const val SPEED_PROBE_BYTES = 5_000
    private const val SPEED_PROBE_MIN_BYTES = 512
    private const val SPEED_PROBE_BATCH_SIZE = 8
    private const val SPEED_PROBE_READY_TIMEOUT_MS = 5000
    private const val SPEED_PROBE_SOCKET_TIMEOUT_MS = 5000
    private const val SPEED_PROBE_HOST = "speed.cloudflare.com"
    private const val SPEED_PROBE_PATH = "/__down?bytes=$SPEED_PROBE_BYTES"
    private val QNAME_MTU_PROBE_ORDER = intArrayOf(0)

    @Volatile var lastProgress: Progress = Progress()

    data class Progress(
        val active: Boolean = false,
        val reason: String = "",
        val phase: String = "",
        val currentHost: String = "",
        val tested: Int = 0,
        val total: Int = 0,
        val alive: Int = 0,
        val speedTested: Int = 0,
        val speedTotal: Int = 0,
        val speedOk: Int = 0,
        val selected: String = ""
    )

    private val operatorResolvers = listOf(
        "176.59.159.161", "176.59.159.157",
        "212.188.4.10", "195.34.32.116", "213.87.0.1", "213.87.1.1",
        "213.87.142.95", "213.87.142.85", "213.87.142.94", "213.87.142.84",
        "213.87.74.21", "213.87.74.5", "213.87.211.20", "213.87.210.20",
        "10.10.22.3", "194.67.2.114", "194.67.1.154",
        "85.249.22.248", "85.249.22.249", "85.249.22.251", "85.249.22.250",
        "176.59.62.125", "176.59.62.126", "176.59.31.182", "176.59.31.183",
        "176.59.223.159", "176.59.95.243", "176.59.63.148", "176.59.63.204",
        "176.59.127.156",
        "80.245.112.23", "195.208.4.1", "194.147.49.16",
        "84.201.166.221", "84.201.166.139", "84.201.166.50", "84.201.166.116",
        "83.169.217.22", "10.112.248.238", "10.112.250.2", "10.112.248.226",
        "10.148.25.144", "10.205.171.77", "10.205.171.69",
        "10.93.233.220", "10.93.233.252"
    )

    private val unshapedResolvers = listOf(
        "185.22.235.137",
        "82.151.127.188",
        "188.0.190.47",
        "46.254.19.23"
    )

    private val blockedAutoResolvers = setOf(
        "1.1.1.1",
        "1.0.0.1",
        "8.8.8.8",
        "8.8.4.4"
    )

    private data class DefaultNetworkResolvers(
        val resolvers: List<String>,
        val isCellular: Boolean,
        val isWifi: Boolean
    )

    fun localMobileResolvers(context: Context): List<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        return cm.allNetworks
            .filter { network ->
                val caps = cm.getNetworkCapabilities(network)
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            }
            .flatMap { network ->
                cm.getLinkProperties(network)?.dnsServers.orEmpty().mapNotNull { it.hostAddress }
            }
            .mapNotNull { normalizeDnsHost(it) }
            .distinct()
    }

    fun localDefaultResolvers(context: Context): List<String> =
        defaultNetworkResolvers(context).resolvers

    fun preferredLocalResolver(context: Context): String? =
        localDefaultResolvers(context).firstOrNull()

    private fun defaultNetworkResolvers(context: Context): DefaultNetworkResolvers {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return DefaultNetworkResolvers(emptyList(), isCellular = false, isWifi = false)
        val network = cm.activeNetwork
            ?: return DefaultNetworkResolvers(emptyList(), isCellular = false, isWifi = false)
        val caps = cm.getNetworkCapabilities(network)
        val dns = cm.getLinkProperties(network)?.dnsServers.orEmpty()
            .mapNotNull { it.hostAddress }
            .mapNotNull { normalizeDnsHost(it) }
            .distinct()
        return DefaultNetworkResolvers(
            resolvers = dns,
            isCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
            isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        )
    }

    private fun normalizeDnsHost(host: String): String? {
        val value = host.substringBefore('%').trim()
        return value.takeIf { it.isNotBlank() && !it.startsWith("fe80:", ignoreCase = true) }
    }

    fun choose(context: Context, config: Config, reason: String, skipHosts: Set<String> = emptySet()): ResolverChoice {
        val port = config.resolverPort
        if (config.resolverMode == Config.ResolverMode.MANUAL) {
            val host = config.resolverHost.trim()
            AppLog.i(TAG, "manual resolver selected host=$host:$port reason=$reason")
            lastProgress = Progress(active = false, reason = reason, phase = "done", tested = 1, total = 1, alive = 1, selected = host)
            return ResolverChoice(listOf(host), port, host, "manual", qnameMtu = 0, testedCount = 1, aliveCount = 1)
        }

        val defaultNetwork = defaultNetworkResolvers(context)
        val local = defaultNetwork.resolvers
        val candidates = buildList {
            addAll(unshapedResolvers)
            addAll(operatorResolvers)
            addAll(local)
        }.map { it.trim() }
            .filter { it.isNotBlank() }
            .filter { it !in blockedAutoResolvers }
            .distinct()

        AppLog.i(
            TAG,
            "auto resolver probe start reason=$reason port=$port defaultDns=${defaultNetwork.resolvers.joinToString()} " +
                "netCellular=${defaultNetwork.isCellular} netWifi=${defaultNetwork.isWifi} local=${local.joinToString()} total=${candidates.size}"
        )
        lastProgress = Progress(active = true, reason = reason, phase = "tcp", total = candidates.size)

        val alive = probeParallel(candidates, port, reason)

        val speedCandidates = (if (alive.isNotEmpty()) alive else candidates)
            .filter { it !in skipHosts }
        if (alive.isEmpty()) {
            AppLog.w(TAG, "auto resolver tcp probe found no alive hosts; trying direct slipstream speed probe candidates=${speedCandidates.take(SPEED_PROBE_BATCH_SIZE).joinToString()} reason=$reason")
        }
        val speedResults = if (speedCandidates.isNotEmpty()) {
            speedProbeAlive(
                domain = config.domain,
                alive = speedCandidates,
                port = port,
                reason = reason,
                preferred = emptyList(),
                username = if (config.authMode == Config.AuthMode.LOGIN_PASSWORD) config.username else null,
                password = if (config.authMode == Config.AuthMode.LOGIN_PASSWORD) config.password else null
            )
        } else {
            emptyList()
        }
        val speedOk = speedResults.filter { it.ok }
        val speedEligible = speedOk.filter { it.host !in skipHosts }
        val speedBest = speedEligible.minByOrNull { it.totalMs }
        val fallback = speedCandidates.firstOrNull { it !in skipHosts }
            ?: speedCandidates.firstOrNull()
            ?: candidates.firstOrNull { it !in skipHosts }
            ?: candidates.firstOrNull()

        val selected = when {
            speedBest != null -> speedBest.host
            fallback != null -> fallback
            alive.any { it !in skipHosts } -> alive.first { it !in skipHosts }
            alive.isNotEmpty() -> alive.first()
            candidates.any { it !in skipHosts } -> candidates.first { it !in skipHosts }
            candidates.isNotEmpty() -> candidates.first()
            else -> ""
        }
        val hosts = speedBest?.hosts ?: when {
            selected.isNotBlank() -> listOf(selected)
            else -> emptyList()
        }
        AppLog.i(
            TAG,
            "auto resolver summary reason=$reason tested=${candidates.size} alive=${alive.size} " +
                "failed=${candidates.size - alive.size} skipped=${skipHosts.size} selected=$selected:$port " +
                "local=${local.joinToString()} aliveList=${alive.joinToString()} " +
                "speedTested=${speedResults.size} speedOk=${speedOk.size} speedBest=${speedBest?.host}:${speedBest?.totalMs}ms " +
                "fallback=${fallback ?: ""}"
        )
        lastProgress = Progress(
            active = false,
            reason = reason,
            phase = "done",
            tested = candidates.size,
            total = candidates.size,
            alive = alive.size,
            speedTested = speedResults.size,
            speedTotal = speedResults.size,
            speedOk = speedOk.size,
            selected = selected
        )
        if (speedResults.isNotEmpty() && speedOk.isEmpty() && selected.isNotBlank()) {
            error("no auto DNS passed slipstream speed probe: tested=${speedResults.size} ok=0")
        }
        if (selected.isBlank()) {
            error(
                if (speedResults.isNotEmpty()) {
                    "no auto DNS passed slipstream speed probe: tested=${speedResults.size} ok=${speedOk.size}"
                } else {
                    "no auto DNS passed tcp probe: tested=${candidates.size} alive=${alive.size}"
                }
            )
        }
        return ResolverChoice(
            hosts = hosts,
            port = port,
            selectedHost = selected,
            source = "auto",
            qnameMtu = speedBest?.qnameMtu ?: 0,
            testedCount = candidates.size,
            aliveCount = alive.size,
            skippedCount = skipHosts.size
        )
    }

    fun isResolverReachable(host: String, port: Int): Boolean =
        tcpConnect(host, port)

    private fun tcpConnect(host: String, port: Int): Boolean =
        runCatching {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            }
            true
        }.getOrDefault(false)

    private fun probeParallel(candidates: List<String>, port: Int, reason: String): List<String> {
        if (candidates.isEmpty()) return emptyList()
        val executor = Executors.newFixedThreadPool(PROBE_THREADS.coerceAtMost(candidates.size))
        val completion = ExecutorCompletionService<Pair<String, Boolean>>(executor)
        val tested = AtomicInteger(0)
        val aliveCount = AtomicInteger(0)
        return try {
            candidates.forEach { host ->
                completion.submit(Callable {
                    lastProgress = lastProgress.copy(active = true, phase = "tcp", currentHost = host)
                    host to tcpConnect(host, port)
                })
            }
            val results = HashMap<String, Boolean>(candidates.size)
            repeat(candidates.size) {
                val pair = completion.poll((CONNECT_TIMEOUT_MS + 800L), TimeUnit.MILLISECONDS)?.get()
                    ?: return@repeat
                results[pair.first] = pair.second
                val testedNow = tested.incrementAndGet()
                val aliveNow = if (pair.second) aliveCount.incrementAndGet() else aliveCount.get()
                AppLog.i(TAG, "auto resolver probe host=${pair.first}:$port ok=${pair.second} tested=$testedNow/${candidates.size} alive=$aliveNow reason=$reason")
                lastProgress = Progress(
                    active = true,
                    reason = reason,
                    phase = "tcp",
                    currentHost = pair.first,
                    tested = testedNow,
                    total = candidates.size,
                    alive = aliveNow
                )
            }
            candidates.filter { results[it] == true }
        } finally {
            executor.shutdownNow()
        }
    }

    private data class SpeedProbeResult(
        val host: String,
        val hosts: List<String> = listOf(host),
        val ok: Boolean,
        val lookupMs: Long = -1,
        val connectMs: Long = -1,
        val tlsMs: Long = -1,
        val startMs: Long = -1,
        val totalMs: Long = Long.MAX_VALUE,
        val bytes: Long = 0,
        val qnameMtu: Int = 0,
        val error: String = ""
    )

    private fun speedProbeAlive(
        domain: String,
        alive: List<String>,
        port: Int,
        reason: String,
        preferred: List<String>,
        username: String?,
        password: String?,
        qnameMtuOrder: IntArray = QNAME_MTU_PROBE_ORDER
    ): List<SpeedProbeResult> {
        val allOrdered = buildList {
            preferred.forEach { if (it in alive && it !in this) add(it) }
            alive.forEach { if (it !in this) add(it) }
        }
        if (allOrdered.isEmpty()) return emptyList()
        AppLog.i(
            TAG,
            "speed probe start reason=$reason total=${allOrdered.size} batchSize=$SPEED_PROBE_BATCH_SIZE " +
                "bytes=$SPEED_PROBE_BYTES minBytes=$SPEED_PROBE_MIN_BYTES candidates=${allOrdered.joinToString()}"
        )
        lastProgress = lastProgress.copy(
            active = true,
            phase = "speed",
            currentHost = "",
            speedTested = 0,
            speedTotal = allOrdered.size,
            speedOk = 0
        )
        val results = ArrayList<SpeedProbeResult>(allOrdered.size)
        for ((batchIndex, batch) in allOrdered.chunked(SPEED_PROBE_BATCH_SIZE).withIndex()) {
            val batchStart = batchIndex * SPEED_PROBE_BATCH_SIZE + 1
            val batchEnd = batchStart + batch.size - 1
            AppLog.i(
                TAG,
                "speed probe batch ${batchIndex + 1} hosts=$batchStart-$batchEnd/${allOrdered.size} " +
                    "candidates=${batch.joinToString()} reason=$reason"
            )
            lastProgress = lastProgress.copy(active = true, phase = "speed", currentHost = batch.joinToString(","))
            val result = speedProbeBatch(domain, batch, port, username, password, qnameMtuOrder)
            results += result
            if (result.ok) {
                AppLog.i(
                    TAG,
                    "speed probe batch ${batchIndex + 1} ok=true resolvers=${batch.joinToString()} " +
                        "ready:${result.startMs}ms total:${result.totalMs}ms reason=$reason"
                )
            } else {
                AppLog.w(TAG, "speed probe batch ${batchIndex + 1} ok=false resolvers=${batch.joinToString()} error=${result.error} reason=$reason")
            }
            lastProgress = lastProgress.copy(
                active = true,
                phase = "speed",
                currentHost = batch.lastOrNull().orEmpty(),
                speedTested = batchEnd,
                speedTotal = allOrdered.size,
                speedOk = results.count { it.ok }
            )
            AppLog.i(
                TAG,
                "speed probe batch ${batchIndex + 1} done ok=${if (result.ok) 1 else 0} tested=$batchEnd/${allOrdered.size} reason=$reason"
            )
            if (result.ok) {
                AppLog.i(TAG, "speed probe stop after first data-ok batch=${batchIndex + 1} tested=$batchEnd/${allOrdered.size} reason=$reason")
                break
            }
        }
        val best = results.filter { it.ok }.minByOrNull { it.totalMs }
        AppLog.i(
            TAG,
            "speed probe summary reason=$reason tested=${results.size} ok=${results.count { it.ok }} " +
                "best=${best?.host}:${best?.totalMs}ms"
        )
        return results
    }

    private fun speedProbeBatch(
        domain: String,
        resolverHosts: List<String>,
        resolverPort: Int,
        username: String?,
        password: String?,
        qnameMtuOrder: IntArray
    ): SpeedProbeResult {
        val primaryHost = resolverHosts.firstOrNull().orEmpty()
        var lastError = ""
        for (qnameMtu in qnameMtuOrder) {
            val slipstreamPort = findFreeLocalPort()
            val socksPort = findFreeLocalPort()
            val startAt = System.nanoTime()
            try {
                AppLog.i(TAG, "speed probe try resolvers=${resolverHosts.joinToString()} qnameMtu=${if (qnameMtu > 0) qnameMtu else "max"}")
                SlipstreamBridge.startClient(
                    domain,
                    ResolverListConfig(resolverHosts, resolverPort, true),
                    slipstreamPort,
                    qnameMtu
                ).getOrThrow()
                waitReady()
                val readyMs = elapsedMs(startAt)
                val metrics = runCatching {
                    MiniSlipstreamSocksBridge.start(
                        listenHost = "127.0.0.1",
                        listenPort = socksPort,
                        slipstreamHost = "127.0.0.1",
                        slipstreamPort = slipstreamPort,
                        dnsHost = primaryHost,
                        username = username?.takeIf { it.isNotBlank() },
                        password = password?.takeIf { it.isNotBlank() }
                    ).getOrThrow()
                    downloadProbe(socksPort, startAt)
                }
                metrics.onFailure {
                    AppLog.w(
                        TAG,
                        "speed probe data path failed after ready; rejecting resolver " +
                            "qnameMtu=${if (qnameMtu > 0) qnameMtu else "max"} error=${it.message ?: it::class.java.simpleName}"
                    )
                }
                val value = metrics.getOrThrow()
                return SpeedProbeResult(
                    host = primaryHost,
                    hosts = resolverHosts,
                    ok = true,
                    lookupMs = readyMs,
                    connectMs = value.connectMs,
                    tlsMs = value.tlsMs,
                    startMs = value.startMs,
                    totalMs = value.totalMs,
                    bytes = value.bytes,
                    qnameMtu = qnameMtu,
                    error = ""
                )
            } catch (e: Throwable) {
                lastError = "qnameMtu=${if (qnameMtu > 0) qnameMtu else "max"} ${e.message ?: e::class.java.simpleName}"
                AppLog.w(TAG, "speed probe try failed resolvers=${resolverHosts.joinToString()} $lastError")
            } finally {
                runCatching { MiniSlipstreamSocksBridge.stop() }
                runCatching { SlipstreamBridge.stopClient() }
            }
        }
        return SpeedProbeResult(host = primaryHost, hosts = resolverHosts, ok = false, error = lastError)
    }

    private data class DownloadMetrics(
        val connectMs: Long,
        val tlsMs: Long,
        val startMs: Long,
        val totalMs: Long,
        val bytes: Long
    )

    private fun downloadProbe(socksPort: Int, startAt: Long): DownloadMetrics {
        val socket = socksConnect(socksPort, SPEED_PROBE_HOST, 443)
        socket.use { raw ->
            val connectedMs = elapsedMs(startAt)
            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = sslFactory
                .createSocket(raw, SPEED_PROBE_HOST, 443, true) as SSLSocket
            ssl.use { tls ->
                tls.soTimeout = SPEED_PROBE_SOCKET_TIMEOUT_MS
                tls.startHandshake()
                val tlsMs = elapsedMs(startAt)
                val out = tls.getOutputStream()
                val input = tls.getInputStream()
                out.write(
                    (
                        "GET $SPEED_PROBE_PATH HTTP/1.1\r\n" +
                            "Host: $SPEED_PROBE_HOST\r\n" +
                            "User-Agent: SlipstreamCLI-SpeedProbe\r\n" +
                            "Connection: close\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII)
                )
                out.flush()
                val header = readHttpHeader(input)
                val startMs = elapsedMs(startAt)
                if (!header.startsWith("HTTP/1.1 200") && !header.startsWith("HTTP/2 200")) {
                    error("speed http rejected: ${header.lineSequence().firstOrNull().orEmpty()}")
                }
                val bytes = drainBytes(input, SPEED_PROBE_BYTES)
                if (bytes < SPEED_PROBE_MIN_BYTES) {
                    error("speed probe too few bytes: $bytes")
                }
                return DownloadMetrics(
                    connectMs = connectedMs,
                    tlsMs = tlsMs,
                    startMs = startMs,
                    totalMs = elapsedMs(startAt),
                    bytes = bytes
                )
            }
        }
    }

    private fun waitReady() {
        val deadline = System.currentTimeMillis() + SPEED_PROBE_READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (SlipstreamBridge.isReady()) return
            Thread.sleep(50)
        }
        error("slipstream speed probe not ready")
    }

    private fun socksConnect(listenPort: Int, host: String, port: Int): Socket {
        val sock = Socket()
        try {
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress("127.0.0.1", listenPort), SPEED_PROBE_SOCKET_TIMEOUT_MS)
            sock.soTimeout = SPEED_PROBE_SOCKET_TIMEOUT_MS
            val input = sock.getInputStream()
            val output = sock.getOutputStream()
            output.write(byteArrayOf(0x05, 0x01, 0x00))
            output.flush()
            val greeting = ByteArray(2)
            input.readFullyStrict(greeting)
            if (greeting[0] != 0x05.toByte() || greeting[1] != 0x00.toByte()) error("socks greeting rejected")
            val hostBytes = host.toByteArray(Charsets.US_ASCII)
            require(hostBytes.size <= 255) { "host too long" }
            output.write(byteArrayOf(0x05, 0x01, 0x00, 0x03, hostBytes.size.toByte()))
            output.write(hostBytes)
            output.write(byteArrayOf(((port shr 8) and 0xFF).toByte(), (port and 0xFF).toByte()))
            output.flush()
            val header = ByteArray(4)
            input.readFullyStrict(header)
            if (header[1] != 0x00.toByte()) error("socks connect rejected rep=${header[1].toInt() and 0xFF}")
            skipSocksBindAddress(input, header[3].toInt() and 0xFF)
            return sock
        } catch (e: Throwable) {
            runCatching { sock.close() }
            throw e
        }
    }

    private fun readHttpHeader(input: InputStream): String {
        val bytes = ArrayList<Byte>(512)
        var matched = 0
        while (bytes.size < 16384) {
            val b = input.read()
            if (b < 0) break
            bytes.add(b.toByte())
            matched = when {
                matched == 0 && b == '\r'.code -> 1
                matched == 1 && b == '\n'.code -> 2
                matched == 2 && b == '\r'.code -> 3
                matched == 3 && b == '\n'.code -> 4
                b == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) break
        }
        return bytes.toByteArray().toString(Charsets.ISO_8859_1)
    }

    private fun drainBytes(input: InputStream, maxBytes: Int): Long {
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (total < maxBytes) {
            val read = input.read(buffer, 0, minOf(buffer.size, maxBytes - total.toInt()))
            if (read < 0) break
            total += read
        }
        return total
    }

    private fun skipSocksBindAddress(input: InputStream, atyp: Int) {
        val bytes = when (atyp) {
            0x01 -> 4
            0x03 -> input.read().coerceAtLeast(0)
            0x04 -> 16
            else -> 0
        }
        if (bytes > 0) input.readFullyStrict(ByteArray(bytes))
        input.readFullyStrict(ByteArray(2))
    }

    private fun findFreeLocalPort(): Int =
        ServerSocket(0).use { it.localPort }

    private fun elapsedMs(startNs: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)

    private fun InputStream.readFullyStrict(buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read < 0) error("unexpected eof")
            offset += read
        }
    }
}
