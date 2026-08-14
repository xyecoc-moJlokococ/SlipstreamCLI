package app.smugly.tunnel

import android.os.ParcelFileDescriptor
import app.smugly.util.AppLog

object HevSocks5Tunnel {
    private const val TAG = "HevSocks5Tunnel"
    /** Local DNS the VPN hands to apps when mapdns is on. */
    const val MAPDNS_ADDRESS = "10.255.0.1"
    private const val MAPDNS_NETWORK = "198.18.0.0"
    private const val MAPDNS_NETMASK = "255.254.0.0"
    @Volatile private var loaded = false

    init {
        try {
            System.loadLibrary("hev-socks5-tunnel")
            System.loadLibrary("hev-tunnel-jni")
            loaded = true
            AppLog.i(TAG, "hev native libraries loaded")
        } catch (e: UnsatisfiedLinkError) {
            AppLog.e(TAG, "hev native load failed", e)
        }
    }

    fun start(
        tunFd: ParcelFileDescriptor,
        socksAddress: String,
        socksPort: Int,
        username: String?,
        password: String?,
        // How UDP is carried to the SOCKS proxy: 'tcp' = hev's UDP-in-TCP scheme
        // (what MiniSlipstreamSocksBridge speaks), 'udp' = standard SOCKS5 UDP
        // ASSOCIATE (what the s3fu client implements natively).
        udpMode: String = "tcp",
        /**
         * Local fake-IP DNS (hev mapdns). Apps query [mapDnsAddress]; hostnames are restored
         * on SOCKS CONNECT so the tunnel never has to carry raw DNS UDP. Essential for
         * protocols where every UDP datagram is an expensive remote session (cdn-fuckup).
         */
        mapDns: Boolean = false,
        mapDnsAddress: String = MAPDNS_ADDRESS,
        /** Drop non-DNS UDP at hev (QUIC noise, etc.). DNS itself is handled by mapdns when on. */
        rejectNonDnsUdp: Boolean = false
    ): Result<Unit> {
        if (!loaded) return Result.failure(IllegalStateException("hev-socks5-tunnel is not loaded"))
        if (isRunning()) stop()
        val config = buildConfig(
            socksAddress, socksPort, username, password, udpMode, mapDns, mapDnsAddress
        )
        AppLog.i(
            TAG,
            "start tun2socks socks=$socksAddress:$socksPort udp=$udpMode mapDns=$mapDns rejectNonDnsUdp=$rejectNonDnsUdp"
        )
        AppLog.d(TAG, config)
        nativeSetRejectQuic(true)
        nativeSetRejectNonDnsUdp(rejectNonDnsUdp)
        val code = nativeStart(config, tunFd.fd)
        return if (code == 0) Result.success(Unit) else Result.failure(RuntimeException("hev start error $code"))
    }

    fun stop() {
        if (loaded) runCatching { nativeStop() }
    }

    fun setCrashLogPath(path: String) {
        if (loaded) runCatching { nativeSetCrashLogPath(path) }
    }

    fun isRunning(): Boolean = loaded && runCatching { nativeIsRunning() }.getOrDefault(false)

    fun stats(): TrafficStats {
        val a = if (loaded) runCatching { nativeGetStats() }.getOrNull() else null
        return TrafficStats(
            txPackets = a?.getOrNull(0) ?: 0,
            txBytes = a?.getOrNull(1) ?: 0,
            rxPackets = a?.getOrNull(2) ?: 0,
            rxBytes = a?.getOrNull(3) ?: 0
        )
    }

    private fun buildConfig(
        address: String,
        port: Int,
        username: String?,
        password: String?,
        udpMode: String,
        mapDns: Boolean,
        mapDnsAddress: String
    ): String = buildString {
        val udp = if (udpMode == "udp") "udp" else "tcp"
        appendLine("tunnel:")
        appendLine("  mtu: 1500")
        appendLine("  ipv4: 10.255.0.2")
        appendLine("  ipv6: 'fd00::2'")
        appendLine()
        appendLine("socks5:")
        appendLine("  address: $address")
        appendLine("  port: $port")
        appendLine("  udp: '$udp'")
        if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
            appendLine("  username: '${username.replace("'", "''")}'")
            appendLine("  password: '${password.replace("'", "''")}'")
        }
        if (mapDns) {
            // Fake-IP DNS: answers on mapDnsAddress; SOCKS CONNECT gets the real hostname.
            appendLine()
            appendLine("mapdns:")
            appendLine("  address: $mapDnsAddress")
            appendLine("  port: 53")
            appendLine("  network: $MAPDNS_NETWORK")
            appendLine("  netmask: $MAPDNS_NETMASK")
            appendLine("  cache-size: 10000")
        }
        appendLine()
        appendLine("misc:")
        appendLine("  task-stack-size: 32768")
        appendLine("  tcp-buffer-size: 1048576")
        appendLine("  udp-recv-buffer-size: 1048576")
        appendLine("  udp-copy-buffer-nums: 64")

        appendLine("  connect-timeout: 8000")
        appendLine("  tcp-read-write-timeout: 120000")
        // After the SOCKS UDP ASSOCIATE handshake hev applies this timeout to the
        // whole session, including the silent TCP control socket. 15s then 90s both
        // killed live Parsec associates (2026-08-15: 9cc8dac3 closed at 95s with
        // `udp associate closed`, origin torn down 30s later). YAML 0/-1 falls back
        // to the 60s library default, so this has to be a large positive. 1h matches
        // the client udp_idle lease; hev.stop() may block up to this on teardown.
        appendLine("  udp-read-write-timeout: 3600000")
        appendLine("  log-level: warn")
    }

    data class TrafficStats(val txPackets: Long, val txBytes: Long, val rxPackets: Long, val rxBytes: Long)

    private external fun nativeStart(config: String, tunFd: Int): Int
    private external fun nativeStop()
    private external fun nativeSetRejectQuic(enabled: Boolean)
    private external fun nativeSetRejectNonDnsUdp(enabled: Boolean)
    private external fun nativeSetCrashLogPath(path: String)
    private external fun nativeIsRunning(): Boolean
    private external fun nativeGetStats(): LongArray?
}
