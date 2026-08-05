package app.smugly.subscription

import app.smugly.platform.LogLevel
import app.smugly.platform.PlatformLog
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Downloads a subscription. Shared by Android and desktop — both are JVM, and this is plain
 * `HttpURLConnection` with no platform dependency.
 *
 * Deliberately kept separate from parsing ([SubscriptionParser]) so the format can be tested
 * without a network.
 */
object SubscriptionFetcher {
    private const val TAG = "SubscriptionFetcher"
    // Kept short on purpose: several routes are tried in sequence, so a long per-route
    // timeout would make a failing refresh take the sum of them all.
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val MAX_REDIRECTS = 5
    private const val MAX_BODY_BYTES = 4 * 1024 * 1024

    /**
     * Panels commonly gate the payload on the client: some return a plain link list to unknown
     * agents and a richer one to known clients. Identifying honestly as this app, with a
     * v2rayNG-compatible token, gets the standard format from most of them.
     */
    const val DEFAULT_USER_AGENT = "Smugly/1.0 (compatible; v2rayNG)"

    data class Response(
        val body: String,
        /** Header names lowercased; multi-value headers joined with ", ". */
        val headers: Map<String, String>
    )

    /** An HTTP proxy to route the request through; null means a direct connection. */
    data class ProxySpec(val host: String, val port: Int, val socks: Boolean = true) {
        /**
         * SOCKS by default, because that is what the local listener actually speaks.
         *
         * The tunnel route was being built as an HTTP proxy while every engine on Android
         * (s3fu, Xray, Slipstream via hev) exposes a plain SOCKS5 inbound — so the "through the
         * tunnel" attempt could never succeed, and a panel that is blocked on the bare mobile
         * network failed both ways round with "Failed to connect".
         */
        fun toJavaProxy(): java.net.Proxy = java.net.Proxy(
            if (socks) java.net.Proxy.Type.SOCKS else java.net.Proxy.Type.HTTP,
            java.net.InetSocketAddress(host, port)
        )
    }

    /**
     * Try each route in order and return the first that works.
     *
     * Subscription panels are very often only reachable **through** the tunnel or an existing
     * proxy — the same censorship that makes the VPN necessary blocks its panel. Verified in the
     * field: a direct fetch timed out while the identical request through a local proxy succeeded.
     * A null entry means "direct".
     */
    fun fetch(
        url: String,
        userAgent: String = "",
        routes: List<ProxySpec?> = listOf(null)
    ): Result<Response> {
        val candidates = routes.ifEmpty { listOf(null) }
        var last: Throwable? = null
        for (route in candidates) {
            val result = fetchVia(url, userAgent, route)
            result.onSuccess { return Result.success(it) }
            result.onFailure { error ->
                last = error
                PlatformLog.log(
                    LogLevel.INFO, TAG,
                    "route ${route?.let { "${if (it.socks) "socks" else "http"} ${it.host}:${it.port}" } ?: "direct"} failed: ${error.message}"
                )
            }
        }
        return Result.failure(last ?: IllegalStateException("no route to subscription"))
    }

    private fun fetchVia(url: String, userAgent: String, route: ProxySpec?): Result<Response> = runCatching {
        var current = url.trim()
        require(current.isNotEmpty()) { "subscription URL is empty" }
        var redirects = 0
        while (true) {
            val parsed = URL(current)
            require(parsed.protocol.equals("http", true) || parsed.protocol.equals("https", true)) {
                "unsupported URL scheme '${parsed.protocol}'"
            }
            val opened = if (route == null) {
                parsed.openConnection()
            } else {
                parsed.openConnection(route.toJavaProxy())
            }
            val conn = (opened as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                // Redirects are followed by hand so http->https and cross-host hops are visible.
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", userAgent.ifBlank { DEFAULT_USER_AGENT })
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Encoding", "gzip")
            }
            try {
                val code = conn.responseCode
                if (code in 301..308 && code != 304) {
                    val location = conn.getHeaderField("Location")
                        ?: error("redirect $code without Location")
                    if (++redirects > MAX_REDIRECTS) error("too many redirects")
                    current = URL(parsed, location).toString()
                    continue
                }
                if (code !in 200..299) {
                    error("HTTP $code${conn.responseMessage?.let { " $it" } ?: ""}")
                }
                val raw = conn.inputStream.use { input ->
                    val stream = if (conn.contentEncoding?.contains("gzip", true) == true) {
                        GZIPInputStream(input)
                    } else {
                        input
                    }
                    stream.readNBytesCompat(MAX_BODY_BYTES)
                }
                val headers = buildMap {
                    conn.headerFields.forEach { (name, values) ->
                        if (name != null) put(name.lowercase(), values.joinToString(", "))
                    }
                }
                PlatformLog.log(
                    LogLevel.INFO, TAG,
                    "fetched ${raw.size} bytes from $current " +
                        "(redirects=$redirects, via=${route?.let { "${it.host}:${it.port}" } ?: "direct"})"
                )
                return@runCatching Response(raw.decodeToString(), headers)
            } finally {
                runCatching { conn.disconnect() }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    /** Bounded read: a hostile or misconfigured URL should not stream unbounded data into memory. */
    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = read(buf)
            if (n <= 0) break
            total += n
            require(total <= limit) { "subscription body larger than ${limit / 1024} KB" }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
