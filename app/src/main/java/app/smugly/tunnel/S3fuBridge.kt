package app.smugly.tunnel

import app.smugly.util.AppLog

/**
 * JNI bridge to `libs3fu.so` (the s3-fuckup S3 dead-drop tunnel client).
 *
 * Unlike [SlipstreamBridge], this needs no per-socket protection: the s3fu
 * client's own TLS connections to the S3 endpoint must travel OUTSIDE the VPN,
 * and [app.smugly.service.TinyVpnService] already excludes its own package from
 * the tunnel (`addDisallowedApplication`), so the app's sockets are never routed
 * back into the TUN. The client just exposes a local SOCKS5 proxy; the existing
 * hev-socks5-tunnel bridges the TUN onto it exactly like the DNS tunnel.
 */
@Suppress("KotlinJniMissingFunction")
object S3fuBridge {
    private const val TAG = "S3fuBridge"

    @Volatile private var loaded = false

    init {
        try {
            System.loadLibrary("s3fu")
            loaded = true
            AppLog.i(TAG, "libs3fu loaded")
        } catch (e: UnsatisfiedLinkError) {
            AppLog.e(TAG, "libs3fu load failed", e)
        }
    }

    fun isLoaded(): Boolean = loaded

    /**
     * Start the local SOCKS5 proxy backed by the S3 tunnel.
     *
     * @param prefix bucket namespace this client talks in. Each server owns one
     *   prefix, so this is what selects WHICH server serves you when several
     *   share one bucket. Blank falls back to "s3fu".
     * @param socksListen host:port to listen on (e.g. "127.0.0.1:1080").
     * @param caFile absolute path to a PEM CA bundle; the native side points
     *   rustls at it via SSL_CERT_FILE (Android exposes no OpenSSL cert dir, so
     *   without it the trust store is empty and every TLS handshake fails).
     */
    /**
     * Start the client from a whole config TOML — the same file `s3fu --config` reads.
     *
     * This used to take one parameter per setting, so the phone could only reach the few
     * knobs that had been plumbed through JNI. [socksListen] and [caFile] stay parameters
     * because they belong to the service, not to the operator's config.
     */
    fun startClient(
        configToml: String,
        socksListen: String,
        caFile: String
    ): Result<Unit> {
        if (!loaded) return Result.failure(IllegalStateException("libs3fu is not loaded"))
        AppLog.i(TAG, "start socks=$socksListen configBytes=${configToml.length}")
        val code = runCatching {
            nativeStartClientToml(configToml, socksListen, caFile)
        }.getOrElse {
            AppLog.e(TAG, "nativeStartClientToml threw", it)
            return Result.failure(it)
        }
        return if (code == 0) {
            AppLog.i(TAG, "s3fu client started")
            Result.success(Unit)
        } else {
            val err = lastError() ?: "s3fu native error $code"
            AppLog.e(TAG, "s3fu start failed: $err")
            Result.failure(RuntimeException(err))
        }
    }

    fun stopClient() {
        if (!loaded) return
        AppLog.i(TAG, "stop s3fu")
        runCatching { nativeStopClient() }
    }

    fun isRunning(): Boolean = loaded && runCatching { nativeIsRunning() }.getOrDefault(false)

    fun lastError(): String? = if (loaded) runCatching { nativeLastError() }.getOrNull() else null

    private external fun nativeStartClientToml(
        configToml: String,
        socksListen: String,
        caFile: String
    ): Int

    private external fun nativeStopClient()
    private external fun nativeIsRunning(): Boolean
    private external fun nativeLastError(): String?
}
