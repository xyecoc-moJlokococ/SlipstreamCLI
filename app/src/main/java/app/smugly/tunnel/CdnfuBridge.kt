package app.smugly.tunnel

import app.smugly.util.AppLog

/**
 * JNI bridge to `libcdnfu.so` (the cdn-fuckup XHTTP-over-CDN tunnel client).
 *
 * Like [S3fuBridge] this needs no per-socket protection: the client's own TLS
 * connections to the CDN must travel OUTSIDE the VPN, and [app.smugly.service.TinyVpnService]
 * already excludes its own package (`addDisallowedApplication`). The client just
 * exposes a local SOCKS5 proxy; the existing hev-socks5-tunnel bridges the TUN
 * onto it. wreq bundles webpki roots, so no CA file is needed.
 */
@Suppress("KotlinJniMissingFunction")
object CdnfuBridge {
    private const val TAG = "CdnfuBridge"

    @Volatile private var loaded = false

    init {
        try {
            System.loadLibrary("cdnfu")
            loaded = true
            AppLog.i(TAG, "libcdnfu loaded")
        } catch (e: UnsatisfiedLinkError) {
            AppLog.e(TAG, "libcdnfu load failed", e)
        }
    }

    fun isLoaded(): Boolean = loaded

    /**
     * Start the local SOCKS5 proxy backed by the XHTTP-over-CDN tunnel.
     *
     * @param url  CDN base, e.g. "https://pvtw3imhtp.a.trbcdn.net/". Path is taken
     *   as a static path unless [mimic] is set.
     * @param psk  pre-shared key passphrase; blank = VLESS mode, else ChaCha20.
     * @param mimic  path mimicry: image|video|static|mixed (blank = mixed).
     * @param socksListen  host:port to listen on (e.g. "127.0.0.1:1080").
     * @param uplinkMethod  auto | GET | POST | PUT | … (GET never uses upload paths).
     * @param uplinkPath  auto | asset | api.
     * @param uplinkData  auto | cookies | query | header | body.
     * @param xhttpPlacement  cookie | query | header for session/seq/pad.
     * @param downlinkMode  auto | stream | poll (image/static auto→poll on Beeline).
     */
    fun startClient(
        url: String,
        psk: String,
        mimic: String,
        socksListen: String,
        uplinkMethod: String = "GET",
        uplinkPath: String = "asset",
        uplinkData: String = "query",
        xhttpPlacement: String = "query",
        downlinkMode: String = "auto"
    ): Result<Unit> {
        if (!loaded) return Result.failure(IllegalStateException("libcdnfu is not loaded"))
        AppLog.i(
            TAG,
            "start url=$url mimic=$mimic method=$uplinkMethod path=$uplinkPath data=$uplinkData " +
                "xhttp=$xhttpPlacement downlink=$downlinkMode chacha=${psk.isNotBlank()} socks=$socksListen"
        )
        val code = runCatching {
            nativeStartClient(
                url,
                psk,
                mimic,
                socksListen,
                uplinkMethod,
                uplinkPath,
                uplinkData,
                xhttpPlacement,
                downlinkMode
            )
        }.getOrElse {
            AppLog.e(TAG, "nativeStartClient threw", it)
            return Result.failure(it)
        }
        return if (code == 0) {
            AppLog.i(TAG, "cdnfu client started")
            Result.success(Unit)
        } else {
            val err = lastError() ?: "cdnfu native error $code"
            AppLog.e(TAG, "cdnfu start failed: $err")
            Result.failure(RuntimeException(err))
        }
    }

    fun stopClient() {
        if (!loaded) return
        AppLog.i(TAG, "stop cdnfu")
        runCatching { nativeStopClient() }
    }

    fun isRunning(): Boolean = loaded && runCatching { nativeIsRunning() }.getOrDefault(false)

    fun lastError(): String? = if (loaded) runCatching { nativeLastError() }.getOrNull() else null

    private external fun nativeStartClient(
        url: String,
        psk: String,
        mimic: String,
        socksListen: String,
        uplinkMethod: String,
        uplinkPath: String,
        uplinkData: String,
        xhttpPlacement: String,
        downlinkMode: String
    ): Int

    private external fun nativeStopClient()
    private external fun nativeIsRunning(): Boolean
    private external fun nativeLastError(): String?
}
