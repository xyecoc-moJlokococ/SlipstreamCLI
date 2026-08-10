package app.smugly.tunnel

import app.smugly.util.AppLog

/**
 * JNI bridge to `libcdnfu.so` (cdn-fuckup XHTTP packet-up client).
 *
 * Same shape as [S3fuBridge]: native side exposes a local SOCKS5 proxy; the VPN
 * path layers hev-socks5-tunnel on top. TLS to the CDN edge leaves the device
 * outside the TUN because [app.smugly.service.TinyVpnService] excludes the app
 * package (`addDisallowedApplication`). wreq carries its own trust roots, so no
 * CA file is required.
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
     * @param url CDN base URL (e.g. `https://jarvis-media.ru/`).
     * @param psk passphrase for ChaCha20-Poly1305 (empty = VLESS mode).
     * @param mimic path cover: image|video|static|mixed (blank → mixed).
     * @param socksListen host:port for SOCKS5 (e.g. `127.0.0.1:1080`).
     * @param uplinkMethod POST|PUT|GET|… (blank → auto).
     * @param uplinkPath auto|asset|api (blank → auto).
     * @param uplinkData body|query|cookie|auto (blank → auto).
     * @param xhttpPlacement cookie|query for session/seq/pad meta (blank → query).
     * @param downlinkMode poll|stream|auto (blank → poll). TCP only — UDP picks its own
     *   (streaming) downlink, because polling costs one request per datagram.
     * @param multipathPaths 0 = default (4); 1 = single path; 2..=32 = stripe.
     * @param hostName hostname to present when [url] is a bare edge IP, e.g.
     *   `url = http://151.236.109.225/` + `hostName = jarvis-media.ru`. It becomes the
     *   `Host` header (and h2 `:authority` / TLS SNI) while the socket goes to that IP.
     *   Blank → taken from the URL as usual.
     */
    fun startClient(
        url: String,
        psk: String,
        mimic: String,
        socksListen: String,
        uplinkMethod: String,
        uplinkPath: String,
        uplinkData: String,
        xhttpPlacement: String,
        downlinkMode: String,
        multipathPaths: Int,
        hostName: String = ""
    ): Result<Unit> {
        if (!loaded) return Result.failure(IllegalStateException("libcdnfu is not loaded"))
        AppLog.i(
            TAG,
            "start url=$url host=$hostName mimic=$mimic method=$uplinkMethod path=$uplinkPath " +
                "data=$uplinkData xhttp=$xhttpPlacement dl=$downlinkMode " +
                "mp=$multipathPaths socks=$socksListen"
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
                downlinkMode,
                multipathPaths,
                hostName
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
        downlinkMode: String,
        multipathPaths: Int,
        hostName: String
    ): Int

    private external fun nativeStopClient()
    private external fun nativeIsRunning(): Boolean
    private external fun nativeLastError(): String?
}
