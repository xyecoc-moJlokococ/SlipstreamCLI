package app.slipnet.tunnel

import android.net.VpnService
import app.slipnet.util.AppLog
import java.net.DatagramSocket

data class ResolverConfig(
    val host: String,
    val port: Int,
    val authoritative: Boolean = true
)

data class ResolverListConfig(
    val hosts: List<String>,
    val port: Int,
    val authoritative: Boolean = true
)

@Suppress("KotlinJniMissingFunction")
object SlipstreamBridge {
    private const val TAG = "SlipstreamBridge"
    const val DEFAULT_LISTEN_HOST = "127.0.0.1"
    const val DEFAULT_PACING_GAIN_PROBE = 1.4
    const val DEFAULT_DNS_TCP_PACKET_LOOP_BURST = 32

    @Volatile private var vpnService: VpnService? = null
    @Volatile private var loaded = false
    @Volatile private var currentPort = 1080
    @Volatile var proxyOnlyMode = true

    init {
        try {
            System.loadLibrary("slipstream")
            loaded = true
            AppLog.i(TAG, "libslipstream loaded")
        } catch (e: UnsatisfiedLinkError) {
            AppLog.e(TAG, "libslipstream load failed", e)
        }
    }

    fun setVpnService(service: VpnService?) {
        vpnService = service
        proxyOnlyMode = service == null
        AppLog.i(TAG, "VpnService ${if (service == null) "cleared" else "set"}")
    }

    fun setLogFilePath(path: String) {
        if (!loaded) return
        runCatching { nativeSetLogFilePath(path) }
            .onSuccess { AppLog.i(TAG, "native log file set: $path") }
            .onFailure { AppLog.e(TAG, "native log file set failed", it) }
    }

    @JvmStatic
    fun protectSocket(fd: Int): Boolean {
        if (proxyOnlyMode) return true
        return try {
            val ok = vpnService?.protect(fd) ?: false
            AppLog.d(TAG, "protectSocket fd=$fd ok=$ok")
            ok
        } catch (e: Throwable) {
            AppLog.e(TAG, "protectSocket fd=$fd failed", e)
            false
        }
    }

    fun protectDatagramSocket(socket: DatagramSocket): Boolean {
        if (proxyOnlyMode) return true
        return try {
            val ok = vpnService?.protect(socket) ?: false
            AppLog.d(TAG, "protectDatagramSocket ok=$ok")
            ok
        } catch (e: Throwable) {
            AppLog.e(TAG, "protectDatagramSocket failed", e)
            false
        }
    }

    @JvmStatic
    fun nativeLog(priority: Int, tag: String, message: String) {
        when (priority) {
            6 -> AppLog.e(tag, message)
            5 -> AppLog.w(tag, message)
            4 -> AppLog.i(tag, message)
            3 -> AppLog.d(tag, message)
            else -> AppLog.d(tag, message)
        }
    }

    fun startClient(
        domain: String,
        resolver: ResolverConfig,
        listenPort: Int,
        qnameMtu: Int = 0,
        resolverTransport: String = "tcp"
    ): Result<Unit> {
        return startClient(
            domain,
            ResolverListConfig(listOf(resolver.host), resolver.port, resolver.authoritative),
            listenPort,
            qnameMtu,
            resolverTransport
        )
    }

    fun startClient(
        domain: String,
        resolver: ResolverListConfig,
        listenPort: Int,
        qnameMtu: Int = 0,
        resolverTransport: String = "tcp"
    ): Result<Unit> {
        if (!loaded) return Result.failure(IllegalStateException("libslipstream is not loaded"))
        val hosts = resolver.hosts.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        require(hosts.isNotEmpty()) { "resolver is empty" }
        val transport = resolverTransport.lowercase().takeIf { it == "udp" || it == "tcp" } ?: "tcp"
        if (nativeIsClientRunning()) stopClient()
        currentPort = listenPort
        AppLog.i(
            TAG,
            "start domain=$domain resolvers=${hosts.joinToString { "$it:${resolver.port}" }} " +
                "mode=authoritative transport=$transport cc=authoritative-fast listen=$DEFAULT_LISTEN_HOST:$listenPort " +
                "pacingGain=$DEFAULT_PACING_GAIN_PROBE dnsTcpBurst=$DEFAULT_DNS_TCP_PACKET_LOOP_BURST " +
                "upstream=qname qnameMtu=${if (qnameMtu > 0) qnameMtu else "max"}"
        )
        val code = nativeStartSlipstreamClient(
            domain,
            hosts.toTypedArray(),
            IntArray(hosts.size) { resolver.port },
            BooleanArray(hosts.size) { resolver.authoritative },
            listenPort,
            DEFAULT_LISTEN_HOST,
            "",
            5000,
            false,
            false,
            false,
            10000,
            120000,
            transport,
            DEFAULT_PACING_GAIN_PROBE,
            DEFAULT_DNS_TCP_PACKET_LOOP_BURST,
            true,
            qnameMtu.coerceAtLeast(0)
        )
        return if (code == 0) {
            AppLog.i(TAG, "slipstream started")
            Result.success(Unit)
        } else {
            val err = nativeGetLastError() ?: "native error $code"
            AppLog.e(TAG, "slipstream start failed: $err")
            Result.failure(RuntimeException(err))
        }
    }

    fun startProbeClient(
        domain: String,
        resolver: ResolverListConfig,
        listenPort: Int,
        qnameMtu: Int = 0
    ): Result<Unit> {
        if (!loaded) return Result.failure(IllegalStateException("libslipstream is not loaded"))
        val hosts = resolver.hosts.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        require(hosts.isNotEmpty()) { "resolver is empty" }
        AppLog.i(
            TAG,
            "probe start domain=$domain resolvers=${hosts.joinToString { "$it:${resolver.port}" }} " +
                "mode=authoritative transport=tcp cc=authoritative-fast listen=$DEFAULT_LISTEN_HOST:$listenPort " +
                "pacingGain=$DEFAULT_PACING_GAIN_PROBE dnsTcpBurst=$DEFAULT_DNS_TCP_PACKET_LOOP_BURST " +
                "upstream=qname qnameMtu=${if (qnameMtu > 0) qnameMtu else "max"}"
        )
        val code = nativeStartProbeClient(
            domain,
            hosts.toTypedArray(),
            IntArray(hosts.size) { resolver.port },
            BooleanArray(hosts.size) { resolver.authoritative },
            listenPort,
            DEFAULT_LISTEN_HOST,
            "",
            5000,
            false,
            false,
            false,
            10000,
            120000,
            "tcp",
            DEFAULT_PACING_GAIN_PROBE,
            DEFAULT_DNS_TCP_PACKET_LOOP_BURST,
            true,
            qnameMtu.coerceAtLeast(0)
        )
        return if (code == 0) {
            AppLog.i(TAG, "probe slipstream started")
            Result.success(Unit)
        } else {
            val err = nativeGetLastError() ?: "native probe error $code"
            AppLog.e(TAG, "probe slipstream start failed: $err")
            Result.failure(RuntimeException(err))
        }
    }

    fun stopClient() {
        if (!loaded) return
        AppLog.i(TAG, "stop slipstream")
        runCatching { nativeStopSlipstreamClient() }
    }

    fun stopProbeClient(listenPort: Int = 0) {
        if (!loaded) return
        AppLog.i(TAG, "stop probe slipstream port=$listenPort")
        runCatching { nativeStopProbeClient(listenPort) }
    }

    fun isRunning(): Boolean = loaded && runCatching { nativeIsClientRunning() }.getOrDefault(false)
    fun isProbeRunning(listenPort: Int = 0): Boolean = loaded && runCatching { nativeIsProbeRunning(listenPort) }.getOrDefault(false)
    fun isReady(): Boolean = loaded && runCatching { nativeIsQuicReady() }.getOrDefault(false)
    fun isProbeReady(listenPort: Int = 0): Boolean = loaded && runCatching { nativeIsProbeReady(listenPort) }.getOrDefault(false)
    fun port(): Int = currentPort
    fun lastError(): String? = if (loaded) runCatching { nativeGetLastError() }.getOrNull() else null

    private external fun nativeStartSlipstreamClient(
        domain: String,
        resolverHosts: Array<String>,
        resolverPorts: IntArray,
        resolverAuthoritative: BooleanArray,
        listenPort: Int,
        listenHost: String,
        congestionControl: String,
        keepAliveInterval: Int,
        gsoEnabled: Boolean,
        debugPoll: Boolean,
        debugStreams: Boolean,
        idlePollInterval: Int,
        idleTimeoutMs: Int,
        resolverTransport: String,
        pacingGainProbe: Double,
        dnsTcpPacketLoopBurst: Int,
        qnameCompatibilityMode: Boolean,
        qnameMtu: Int
    ): Int

    private external fun nativeStopSlipstreamClient()
    private external fun nativeStartProbeClient(
        domain: String,
        resolverHosts: Array<String>,
        resolverPorts: IntArray,
        resolverAuthoritative: BooleanArray,
        listenPort: Int,
        listenHost: String,
        congestionControl: String,
        keepAliveInterval: Int,
        gsoEnabled: Boolean,
        debugPoll: Boolean,
        debugStreams: Boolean,
        idlePollInterval: Int,
        idleTimeoutMs: Int,
        resolverTransport: String,
        pacingGainProbe: Double,
        dnsTcpPacketLoopBurst: Int,
        qnameCompatibilityMode: Boolean,
        qnameMtu: Int
    ): Int
    private external fun nativeStopProbeClient(listenPort: Int)
    private external fun nativeIsClientRunning(): Boolean
    private external fun nativeIsProbeRunning(listenPort: Int): Boolean
    private external fun nativeIsQuicReady(): Boolean
    private external fun nativeIsProbeReady(listenPort: Int): Boolean
    private external fun nativeGetLastError(): String?
    private external fun nativeSetLogFilePath(path: String)
}
