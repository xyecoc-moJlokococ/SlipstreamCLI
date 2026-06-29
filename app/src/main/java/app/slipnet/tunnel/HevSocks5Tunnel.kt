package app.slipnet.tunnel

import android.os.ParcelFileDescriptor
import app.slipnet.util.AppLog

object HevSocks5Tunnel {
    private const val TAG = "HevSocks5Tunnel"
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
        password: String?
    ): Result<Unit> {
        if (!loaded) return Result.failure(IllegalStateException("hev-socks5-tunnel is not loaded"))
        if (isRunning()) stop()
        val config = buildConfig(socksAddress, socksPort, username, password)
        AppLog.i(TAG, "start tun2socks socks=$socksAddress:$socksPort")
        AppLog.d(TAG, config)
        nativeSetRejectQuic(false)
        nativeSetRejectNonDnsUdp(false)
        val code = nativeStart(config, tunFd.fd)
        return if (code == 0) Result.success(Unit) else Result.failure(RuntimeException("hev start error $code"))
    }

    fun stop() {
        if (loaded) runCatching { nativeStop() }
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

    private fun buildConfig(address: String, port: Int, username: String?, password: String?): String = buildString {
        appendLine("tunnel:")
        appendLine("  mtu: 1500")
        appendLine("  ipv4: 10.255.0.2")
        appendLine("  ipv6: 'fd00::2'")
        appendLine()
        appendLine("socks5:")
        appendLine("  address: $address")
        appendLine("  port: $port")
        appendLine("  udp: 'tcp'")
        if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
            appendLine("  username: '${username.replace("'", "''")}'")
            appendLine("  password: '${password.replace("'", "''")}'")
        }
        appendLine()
        appendLine("misc:")
        appendLine("  task-stack-size: 32768")
        appendLine("  tcp-buffer-size: 1048576")
        appendLine("  udp-recv-buffer-size: 1048576")
        appendLine("  udp-copy-buffer-nums: 64")
        appendLine("  connect-timeout: 8000")
        appendLine("  tcp-read-write-timeout: 120000")
        appendLine("  udp-read-write-timeout: 60000")
        appendLine("  log-level: warn")
    }

    data class TrafficStats(val txPackets: Long, val txBytes: Long, val rxPackets: Long, val rxBytes: Long)

    private external fun nativeStart(config: String, tunFd: Int): Int
    private external fun nativeStop()
    private external fun nativeSetRejectQuic(enabled: Boolean)
    private external fun nativeSetRejectNonDnsUdp(enabled: Boolean)
    private external fun nativeIsRunning(): Boolean
    private external fun nativeGetStats(): LongArray?
}
