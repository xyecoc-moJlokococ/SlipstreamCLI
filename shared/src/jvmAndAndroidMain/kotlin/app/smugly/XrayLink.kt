package app.smugly

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/**
 * `vless://` import for the Xray transport.
 *
 * Xray profiles carry no structured fields in this app -- the whole server
 * definition lives in [Config.xrayConfigJson] as literal Xray JSON. Importing a
 * link is therefore a one-shot translation: parse the URI into [VlessLink], then
 * render a complete, runnable config with [XrayConfigBuilder.build]. After that
 * the JSON is the source of truth and the user edits it directly.
 *
 * The query-parameter vocabulary follows v2rayNG's VlessFmt/FmtBase (which is the
 * de-facto standard for these links); the emitted JSON follows Xray-core's own
 * infra/conf schema for the version bundled in libxray.aar.
 */
data class VlessLink(
    val remarks: String,
    val server: String,
    val port: Int,
    val uuid: String,
    val encryption: String,
    val flow: String?,
    /** "tls", "reality", or null for plaintext. */
    val security: String?,
    val sni: String?,
    val fingerprint: String?,
    val alpn: List<String>,
    val allowInsecure: Boolean,
    val echConfigList: String?,
    val verifyPeerCertByName: String?,
    val pinnedPeerCertSha256: String?,
    val publicKey: String?,
    val shortId: String?,
    val spiderX: String?,
    val mldsa65Verify: String?,
    /** raw/tcp, ws, grpc, xhttp, httpupgrade, kcp -- passed through as given. */
    val network: String,
    val headerType: String?,
    val host: String?,
    val path: String?,
    val serviceName: String?,
    val mode: String?,
    val authority: String?,
    val xhttpExtra: String?,
    val seed: String?
)

object VlessLinkParser {
    private const val SCHEME = "vless://"

    /** True when [text] looks like a vless link (possibly embedded in other text). */
    fun looksLikeLink(text: String): Boolean =
        text.contains(SCHEME, ignoreCase = true)

    /** Extract every vless:// link found in [text], in order. */
    fun findAll(text: String): List<String> =
        Regex("vless://[^\\s\"'<>]+", RegexOption.IGNORE_CASE).findAll(text).map { it.value }.toList()

    /**
     * Parse a single `vless://uuid@host:port?params#remarks` URI.
     * Returns null when the link is malformed (no user info, no port, ...).
     */
    fun parse(raw: String): VlessLink? {
        var rest = raw.trim()
        if (!rest.startsWith(SCHEME, ignoreCase = true)) return null
        rest = rest.substring(SCHEME.length)
        if (rest.isBlank()) return null

        val fragmentAt = rest.indexOf('#')
        val remarks = if (fragmentAt >= 0) decode(rest.substring(fragmentAt + 1)) else ""
        if (fragmentAt >= 0) rest = rest.substring(0, fragmentAt)

        val queryAt = rest.indexOf('?')
        val query = if (queryAt >= 0) rest.substring(queryAt + 1) else ""
        if (queryAt >= 0) rest = rest.substring(0, queryAt)

        // Last '@' wins: the user-info half may itself contain '@' once the new
        // VLESS encryption blobs are percent-decoded.
        val atIndex = rest.lastIndexOf('@')
        if (atIndex <= 0) return null
        val uuid = decode(rest.substring(0, atIndex)).trim()
        if (uuid.isEmpty()) return null

        val (server, port) = parseHostPort(rest.substring(atIndex + 1)) ?: return null
        val q = parseQuery(query)

        val security = q["security"]?.lowercase()?.takeIf { it == "tls" || it == "reality" }
        val insecure = listOf("insecure", "allowinsecure", "allow_insecure")
            .any { q[it] == "1" || q[it].equals("true", ignoreCase = true) }

        return VlessLink(
            remarks = remarks.ifBlank { server },
            server = server,
            port = port,
            uuid = uuid,
            encryption = q["encryption"]?.takeIf { it.isNotBlank() } ?: "none",
            flow = q["flow"]?.takeIf { it.isNotBlank() },
            security = security,
            sni = q["sni"]?.takeIf { it.isNotBlank() },
            fingerprint = q["fp"]?.takeIf { it.isNotBlank() },
            alpn = q["alpn"].orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() },
            allowInsecure = insecure,
            echConfigList = q["ech"]?.takeIf { it.isNotBlank() },
            verifyPeerCertByName = q["vcn"]?.takeIf { it.isNotBlank() },
            pinnedPeerCertSha256 = q["pcs"]?.takeIf { it.isNotBlank() },
            publicKey = q["pbk"]?.takeIf { it.isNotBlank() },
            shortId = q["sid"]?.takeIf { it.isNotBlank() },
            spiderX = q["spx"]?.takeIf { it.isNotBlank() },
            mldsa65Verify = q["pqv"]?.takeIf { it.isNotBlank() },
            network = q["type"]?.lowercase()?.takeIf { it.isNotBlank() } ?: "tcp",
            headerType = q["headertype"]?.takeIf { it.isNotBlank() },
            host = q["host"]?.takeIf { it.isNotBlank() },
            path = q["path"]?.takeIf { it.isNotBlank() },
            serviceName = q["servicename"]?.takeIf { it.isNotBlank() },
            mode = q["mode"]?.takeIf { it.isNotBlank() },
            authority = q["authority"]?.takeIf { it.isNotBlank() },
            xhttpExtra = q["extra"]?.takeIf { it.isNotBlank() },
            seed = q["seed"]?.takeIf { it.isNotBlank() }
        )
    }

    private fun parseHostPort(value: String): Pair<String, Int>? {
        if (value.isBlank()) return null
        if (value.startsWith("[")) { // [2001:db8::1]:443
            val close = value.indexOf(']')
            if (close < 0) return null
            val host = value.substring(1, close)
            val port = value.substring(close + 1).removePrefix(":").toIntOrNull() ?: return null
            return if (host.isBlank() || port !in 1..65535) null else host to port
        }
        val colon = value.lastIndexOf(':')
        if (colon <= 0) return null
        val host = value.substring(0, colon)
        val port = value.substring(colon + 1).toIntOrNull() ?: return null
        return if (host.isBlank() || port !in 1..65535) null else host to port
    }

    /** Query keys are lowercased so `headerType` and `headertype` both resolve. */
    private fun parseQuery(query: String): Map<String, String> =
        query.split('&')
            .mapNotNull { pair ->
                if (pair.isBlank()) return@mapNotNull null
                val eq = pair.indexOf('=')
                if (eq < 0) decode(pair).lowercase() to "" else decode(pair.substring(0, eq)).lowercase() to decode(pair.substring(eq + 1))
            }
            .toMap()

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}

object XrayConfigBuilder {
    /** Tag of the outbound the traffic-stats counters are read from. */
    const val PROXY_TAG = "proxy"

    /** Xray user level the generated policy block is written for. */
    private const val USER_LEVEL = 8

    /**
     * Render a complete Xray config for [link], with a SOCKS inbound on
     * 127.0.0.1:[socksPort] for the tun2socks bridge to attach to.
     */
    fun build(link: VlessLink, socksPort: Int): String {
        val root = JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("stats", JSONObject())
            .put(
                "policy",
                JSONObject()
                    .put(
                        "levels",
                        JSONObject().put(
                            USER_LEVEL.toString(),
                            JSONObject()
                                .put("handshake", 4)
                                .put("connIdle", 300)
                                .put("uplinkOnly", 1)
                                .put("downlinkOnly", 1)
                        )
                    )
                    .put(
                        "system",
                        JSONObject()
                            .put("statsOutboundUplink", true)
                            .put("statsOutboundDownlink", true)
                    )
            )
            .put("inbounds", JSONArray().put(socksInbound(socksPort)))
            .put(
                "outbounds",
                JSONArray()
                    .put(vlessOutbound(link))
                    .put(JSONObject().put("tag", "direct").put("protocol", "freedom").put("settings", JSONObject()))
                    .put(
                        JSONObject()
                            .put("tag", "block")
                            .put("protocol", "blackhole")
                            .put("settings", JSONObject().put("response", JSONObject().put("type", "http")))
                    )
            )
            // AsIs: everything goes out the first outbound, so Xray never has to
            // resolve a name locally -- no DNS leak outside the tunnel, and the
            // sniffed domain is what reaches the server.
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", JSONArray()))

        return root.toString(2)
    }

    /** Starting point for a hand-written Xray profile (no link imported). */
    fun blankTemplate(socksPort: Int): String =
        JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray().put(socksInbound(socksPort)))
            .put(
                "outbounds",
                JSONArray().put(
                    JSONObject()
                        .put("tag", PROXY_TAG)
                        .put("protocol", "vless")
                        .put(
                            "settings",
                            JSONObject()
                                .put("address", "example.com")
                                .put("port", 443)
                                .put("id", "00000000-0000-0000-0000-000000000000")
                                .put("encryption", "none")
                                .put("level", USER_LEVEL)
                        )
                        .put(
                            "streamSettings",
                            JSONObject()
                                .put("network", "tcp")
                                .put("security", "tls")
                                .put("tlsSettings", JSONObject().put("serverName", "example.com"))
                        )
                )
            )
            .toString(2)

    /**
     * Force the config's SOCKS inbound onto 127.0.0.1:[socksPort] so the TUN
     * bridge always knows where to connect, injecting one if the config has none.
     * Returns the original text unchanged when it is not parseable JSON -- the
     * caller surfaces the parse error from Xray itself.
     */
    fun withSocksPort(configJson: String, socksPort: Int): String {
        val root = runCatching { JSONObject(configJson) }.getOrNull() ?: return configJson
        val inbounds = root.optJSONArray("inbounds") ?: JSONArray().also { root.put("inbounds", it) }
        for (i in 0 until inbounds.length()) {
            val inbound = inbounds.optJSONObject(i) ?: continue
            if (!inbound.optString("protocol").equals("socks", ignoreCase = true)) continue
            inbound.put("listen", "127.0.0.1").put("port", socksPort)
            // Force no-auth too. Panel configs routinely ship this inbound with
            // `auth: "password"` + accounts for desktop clients, and the TUN bridge that dials it
            // is ours and offers no credentials — Xray then rejects every single connection with
            // "proxy/socks: no matching auth method", so the tunnel comes up and carries nothing.
            val settings = inbound.optJSONObject("settings")
                ?: JSONObject().also { inbound.put("settings", it) }
            settings.put("auth", "noauth")
            settings.remove("accounts")
            return root.toString(2)
        }
        inbounds.put(socksInbound(socksPort))
        return root.toString(2)
    }

    /**
     * The config with **every** inbound replaced by one SOCKS5 listener on 127.0.0.1:[socksPort].
     * Outbounds, routing and dns are untouched, so what gets measured or relayed is the profile.
     *
     * [withSocksPort] only moves the SOCKS inbound and leaves the rest alone, which is right for
     * the live tunnel but wrong for anything that runs a second instance on the side: panel configs
     * ship an HTTP inbound on a fixed port too, so two of them would fight over it and the second
     * would fail to start for a reason that has nothing to do with the profile.
     */
    fun withOnlySocksInbound(configJson: String, socksPort: Int): String {
        val root = runCatching { JSONObject(configJson) }.getOrNull() ?: return configJson
        root.put("inbounds", JSONArray().put(socksInbound(socksPort)))
        return root.toString(2)
    }

    /**
     * Pin an HTTP inbound onto 127.0.0.1:[httpPort], adding one when the config has none.
     *
     * This is what apps are pointed at by `VpnService.setHttpProxy`. It matters beyond
     * convenience: through the TUN a browser must resolve the name itself and connect to an
     * address, so a name with no DNS behind it (`.onion`, `.i2p`) never becomes a connection at
     * all. Through the proxy the app sends `CONNECT host:port`, and the host name reaches the
     * routing rules — which is the only reason those addresses can work.
     */
    fun withHttpPort(configJson: String, httpPort: Int): String {
        val root = runCatching { JSONObject(configJson) }.getOrNull() ?: return configJson
        val inbounds = root.optJSONArray("inbounds") ?: JSONArray().also { root.put("inbounds", it) }
        for (i in 0 until inbounds.length()) {
            val inbound = inbounds.optJSONObject(i) ?: continue
            if (!inbound.optString("protocol").equals("http", ignoreCase = true)) continue
            inbound.put("listen", "127.0.0.1").put("port", httpPort)
            // Same reason as the SOCKS inbound: panel configs ship accounts for desktop clients,
            // and the apps dialling this one have no credentials to offer.
            inbound.optJSONObject("settings")?.remove("accounts")
            if (!inbound.has("sniffing")) inbound.put("sniffing", sniffing())
            return root.toString(2)
        }
        inbounds.put(httpInbound(httpPort))
        return root.toString(2)
    }

    /** Port of the config's first HTTP inbound, or null when it has none. */
    fun httpPortOf(configJson: String): Int? {
        val root = runCatching { JSONObject(configJson) }.getOrNull() ?: return null
        val inbounds = root.optJSONArray("inbounds") ?: return null
        for (i in 0 until inbounds.length()) {
            val inbound = inbounds.optJSONObject(i) ?: continue
            if (inbound.optString("protocol").equals("http", ignoreCase = true)) {
                return inbound.optInt("port").takeIf { it in 1..65535 }
            }
        }
        return null
    }

    /** Port of the config's first SOCKS inbound, or null when it has none. */
    fun socksPortOf(configJson: String): Int? {
        val root = runCatching { JSONObject(configJson) }.getOrNull() ?: return null
        val inbounds = root.optJSONArray("inbounds") ?: return null
        for (i in 0 until inbounds.length()) {
            val inbound = inbounds.optJSONObject(i) ?: continue
            if (inbound.optString("protocol").equals("socks", ignoreCase = true)) {
                return inbound.optInt("port").takeIf { it in 1..65535 }
            }
        }
        return null
    }

    /** Server address of the first outbound that has one -- used for profile subtitles. */
    fun describeServer(configJson: String): String? {
        val root = runCatching { JSONObject(configJson) }.getOrNull() ?: return null
        val outbounds = root.optJSONArray("outbounds") ?: return null
        for (i in 0 until outbounds.length()) {
            val settings = outbounds.optJSONObject(i)?.optJSONObject("settings") ?: continue
            settings.optString("address").takeIf { it.isNotBlank() }?.let { address ->
                val port = settings.optInt("port")
                return if (port > 0) "$address:$port" else address
            }
            val vnext = settings.optJSONArray("vnext")?.optJSONObject(0)
            vnext?.optString("address")?.takeIf { it.isNotBlank() }?.let { address ->
                val port = vnext.optInt("port")
                return if (port > 0) "$address:$port" else address
            }
        }
        return null
    }

    private fun socksInbound(socksPort: Int): JSONObject =
        JSONObject()
            .put("tag", "socks")
            .put("protocol", "socks")
            .put("listen", "127.0.0.1")
            .put("port", socksPort)
            .put(
                "settings",
                JSONObject()
                    .put("auth", "noauth")
                    .put("udp", true)
                    .put("userLevel", USER_LEVEL)
            )
            .put("sniffing", sniffing())

    private fun httpInbound(httpPort: Int): JSONObject =
        JSONObject()
            .put("tag", "http")
            .put("protocol", "http")
            .put("listen", "127.0.0.1")
            .put("port", httpPort)
            .put("settings", JSONObject().put("userLevel", USER_LEVEL))
            .put("sniffing", sniffing())

    /**
     * Recover the destination from the stream itself. Without it the routing rules only ever see
     * an address, so every `domain:` rule in the config is dead weight.
     */
    private fun sniffing(): JSONObject =
        JSONObject()
            .put("enabled", true)
            .put("destOverride", JSONArray().put("http").put("tls").put("quic"))
            .put("routeOnly", false)

    private fun vlessOutbound(link: VlessLink): JSONObject {
        // Flat form (address/port/id/... directly under settings); Xray rewrites it
        // into a single-member vnext internally. See infra/conf/vless.go.
        val settings = JSONObject()
            .put("address", link.server)
            .put("port", link.port)
            .put("id", link.uuid)
            .put("encryption", link.encryption)
            .put("level", USER_LEVEL)
        link.flow?.let { settings.put("flow", it) }

        return JSONObject()
            .put("tag", PROXY_TAG)
            .put("protocol", "vless")
            .put("settings", settings)
            .put("streamSettings", streamSettings(link))
            .put("mux", JSONObject().put("enabled", false).put("concurrency", -1))
    }

    private fun streamSettings(link: VlessLink): JSONObject {
        val stream = JSONObject().put("network", link.network)
        val transportSni = populateTransport(stream, link)
        populateSecurity(stream, link, transportSni)
        return stream
    }

    /**
     * Fill in the per-transport settings object. Returns the SNI implied by the
     * transport (the Host header, gRPC authority, ...) which is used as the TLS
     * server name when the link carries no explicit `sni`.
     */
    private fun populateTransport(stream: JSONObject, link: VlessLink): String? {
        when (link.network) {
            "tcp", "raw" -> {
                val header = JSONObject()
                if (link.headerType == "http") {
                    header.put("type", "http").put("request", tcpHttpRequest(link.host, link.path))
                    return link.host?.split(",")?.firstOrNull()?.trim()
                }
                header.put("type", "none")
                stream.put("rawSettings", JSONObject().put("header", header))
                return link.host
            }

            "ws", "websocket" -> {
                stream.put(
                    "wsSettings",
                    JSONObject()
                        .put("host", link.host.orEmpty())
                        .put("path", link.path ?: "/")
                )
                return link.host
            }

            "httpupgrade" -> {
                stream.put(
                    "httpupgradeSettings",
                    JSONObject()
                        .put("host", link.host.orEmpty())
                        .put("path", link.path ?: "/")
                )
                return link.host
            }

            "xhttp", "splithttp" -> {
                val xhttp = JSONObject()
                    .put("host", link.host.orEmpty())
                    .put("path", link.path ?: "/")
                link.mode?.let { xhttp.put("mode", it) }
                link.xhttpExtra?.let { extra ->
                    runCatching { xhttp.put("extra", JSONObject(extra)) }
                }
                stream.put("xhttpSettings", xhttp)
                return link.host
            }

            "grpc" -> {
                stream.put(
                    "grpcSettings",
                    JSONObject()
                        .put("serviceName", link.serviceName.orEmpty())
                        .put("authority", link.authority.orEmpty())
                        .put("multiMode", link.mode == "multi")
                        .put("idle_timeout", 60)
                        .put("health_check_timeout", 20)
                )
                return link.authority
            }

            "kcp", "mkcp" -> {
                val kcp = JSONObject().put(
                    "header",
                    JSONObject().put("type", link.headerType?.takeIf { it.isNotBlank() } ?: "none")
                )
                link.seed?.let { kcp.put("seed", it) }
                stream.put("kcpSettings", kcp)
                return null
            }

            // h2/http/quic were removed from Xray; leave the network as the user
            // wrote it so the core reports its own "removed feature" error rather
            // than us silently rewriting the profile into something else.
            else -> return link.host
        }
    }

    private fun populateSecurity(stream: JSONObject, link: VlessLink, transportSni: String?) {
        val security = link.security ?: return
        stream.put("security", security)

        val serverName = link.sni
            ?: transportSni?.takeIf { it.isNotBlank() && !isIpLiteral(it) }
            ?: link.server.takeIf { !isIpLiteral(it) }

        val tls = JSONObject()
        serverName?.let { tls.put("serverName", it) }
        link.fingerprint?.let { tls.put("fingerprint", it) }
        if (link.alpn.isNotEmpty()) {
            tls.put("alpn", JSONArray().apply { link.alpn.forEach { put(it) } })
        }
        link.echConfigList?.let { tls.put("echConfigList", it) }
        link.verifyPeerCertByName?.let { tls.put("verifyPeerCertByName", it) }
        link.pinnedPeerCertSha256?.let { tls.put("pinnedPeerCertSha256", it) }

        if (security == "reality") {
            link.publicKey?.let { tls.put("publicKey", it) }
            link.shortId?.let { tls.put("shortId", it) }
            link.spiderX?.let { tls.put("spiderX", it) }
            link.mldsa65Verify?.let { tls.put("mldsa65Verify", it) }
            stream.put("realitySettings", tls)
        } else {
            // A pinned peer certificate already authenticates the server, so
            // allowInsecure would only weaken it -- keep the two mutually exclusive.
            tls.put("allowInsecure", link.allowInsecure && link.pinnedPeerCertSha256.isNullOrBlank())
            stream.put("tlsSettings", tls)
        }
    }

    private fun tcpHttpRequest(host: String?, path: String?): JSONObject {
        val hosts = host.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val paths = path.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val headers = JSONObject()
            .put(
                "User-Agent",
                JSONArray().put(
                    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36"
                )
            )
            .put("Accept-Encoding", JSONArray().put("gzip, deflate"))
            .put("Connection", JSONArray().put("keep-alive"))
            .put("Pragma", "no-cache")
        if (hosts.isNotEmpty()) {
            headers.put("Host", JSONArray().apply { hosts.forEach { put(it) } })
        }
        return JSONObject()
            .put("version", "1.1")
            .put("method", "GET")
            .put("path", JSONArray().apply { paths.ifEmpty { listOf("/") }.forEach { put(it) } })
            .put("headers", headers)
    }

    /** Rough IPv4/IPv6 literal check -- SNI must be a name, never an address. */
    private fun isIpLiteral(value: String): Boolean =
        value.contains(':') || value.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))
}
