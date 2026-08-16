package app.smugly

/**
 * Profile / tunnel configuration — pure multiplatform data model.
 * Persistence is platform-specific ([app.smugly.platform.KeyValueStore]).
 */
data class Config(
    val domain: String,
    val resolverHost: String,
    val resolverPort: Int,
    val resolverMode: ResolverMode,
    val resolverTransport: ResolverTransport,
    val resolverPathMode: ResolverPathMode,
    val listenPort: Int,
    val mode: Mode,
    val authMode: AuthMode,
    val username: String,
    val password: String,
    val dnsQueryType: Int = 16,
    val dnsLabelLength: Int = 57,
    val dnsLabelLengthJitter: Int = 4,
    val maxPollQps: Int = 1400,
    val maxDataQps: Int = 800,
    val maxActiveClients: Int = 40,
    val base64uEncoding: Boolean = false,
    val protocol: TunnelProtocol = TunnelProtocol.SLIPSTREAM,
    val s3Endpoint: String = "",
    val s3Bucket: String = "",
    val s3AccessKey: String = "",
    val s3SecretKey: String = "",
    val s3Prefix: String = "s3fu",
    /** The whole s3fu credential: it authenticates and derives the user's object
     *  namespace, so there is no separate login. */
    val s3Psk: String = "",
    val xrayConfigJson: String = "",
    // ---- cdn-fuckup (XHTTP packet-up over CDN / reverse proxy) ----
    /** Base URL of the edge, e.g. `https://jarvis-media.ru/` — or a bare edge IP such
     *  as `http://151.236.109.225/`, paired with [cdnfuHost]. */
    val cdnfuUrl: String = "",
    /**
     * Hostname to present when [cdnfuUrl] points at an IP: it becomes the `Host`
     * header (and HTTP/2 `:authority` / TLS SNI) while the connection goes to that IP.
     * Blank = take the name from the URL as usual.
     *
     * Needed when a CDN only serves the resource from one particular edge, or when DNS
     * hands out an edge that doesn't answer.
     */
    val cdnfuHost: String = "",
    /** ChaCha20-Poly1305 passphrase (empty = VLESS uuid mode, not used in UI). */
    val cdnfuPsk: String = "",
    /** Path cover: image | video | static | mixed. */
    val cdnfuMimic: String = "mixed",
    /** Uplink HTTP method: POST | PUT | GET | … (blank → auto). */
    val cdnfuUplinkMethod: String = "POST",
    /** Uplink path family: auto | asset | api. */
    val cdnfuUplinkPath: String = "api",
    /** Where ciphertext rides: body | query | cookie | auto. */
    val cdnfuUplinkData: String = "body",
    /** XHTTP meta placement for session/seq/pad: cookie | query. */
    val cdnfuXhttpPlacement: String = "cookie",
    /** Downlink: poll (safe behind buffering edges) | stream | auto. */
    val cdnfuDownlinkMode: String = "stream",
    /**
     * Parallel packet-up sessions per SOCKS TCP. 0 / 1 = single path (recommended on
     * Android); 2..=4 = multipath striping for lab benches only.
     */
    val cdnfuMultipath: Int = 1,
    /**
     * Whole client config as TOML, edited as text in the profile screen — the same file
     * the `s3fu --config` CLI reads. Blank = derive one from the fields above, which is
     * what every profile made before this existed does (see [effectiveS3fuToml]).
     */
    val s3fuToml: String = "",
    /** As [s3fuToml], for cdn-fuckup (`cdnfu --config`). */
    val cdnfuToml: String = ""
) {
    enum class Mode { PROXY, VPN }
    enum class AuthMode { NO_AUTH, LOGIN_PASSWORD }
    enum class ResolverMode { MANUAL, AUTO }
    enum class ResolverTransport { UDP, TCP }
    enum class ResolverPathMode { RECURSIVE, AUTHORITATIVE }
    enum class TunnelProtocol { SLIPSTREAM, S3FU, XRAY, CDNFU }
}

data class ConfigProfile(
    val id: String,
    val name: String,
    val config: Config,
    /**
     * Subscription this profile came from, or null for a hand-made one. This is the grouping key:
     * refreshing a subscription replaces exactly the profiles carrying its id and leaves the rest
     * untouched.
     */
    val subscriptionId: String? = null,
    /**
     * Sub-group inside that subscription's folder (see `SubscriptionCategory`), or null when the
     * panel does not group its servers. Only meaningful together with [subscriptionId].
     */
    val categoryId: String? = null
)

object DnsResolverPool {
    const val LOCAL_SENTINEL = "(local)"
    private const val LOCAL_SENTINEL_LEGACY = "(local dns-resolvers)"
    const val DEFAULT_RAW = "(local)\n82.151.127.188\n188.0.190.47\n185.22.235.137\n46.254.19.23"

    fun parse(raw: String): List<String> =
        raw.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.distinct().toList()

    /**
     * Split a typed resolver field into hosts. The engine already runs several at once; this is
     * what lets one profile write `1.1.1.1, 8.8.8.8` or one-per-line and mean it.
     */
    fun parseManualHosts(raw: String): List<String> =
        raw.split(',', ';', ' ', '\t', '\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun isLocalSentinel(entry: String): Boolean =
        entry.equals(LOCAL_SENTINEL, ignoreCase = true) ||
            entry.equals(LOCAL_SENTINEL_LEGACY, ignoreCase = true)

    fun normalize(raw: String): String =
        raw.lineSequence()
            .map { line ->
                val trimmed = line.trim()
                if (trimmed.equals(LOCAL_SENTINEL_LEGACY, ignoreCase = true)) LOCAL_SENTINEL else line
            }
            .joinToString("\n")
}

data class GlobalSettings(
    val listenPort: Int,
    val mode: Config.Mode,
    val fileLogging: Boolean,
    val trafficNotification: Boolean,
    val localSocksAuthEnabled: Boolean,
    val localSocksUsername: String,
    val localSocksPassword: String,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val dnsResolverPool: String = DnsResolverPool.DEFAULT_RAW,
    /**
     * Where the "Home" folder sits among the tabs, once it exists. Subscriptions carry their
     * own order in the subscription list, but Home is not one of them and still has to be
     * draggable, so its slot lives here. Home itself is omitted until the user has a local
     * profile (imported or created).
     */
    val homeFolderIndex: Int = 0,
    /**
     * Folder that was open when the app was last used — a subscription id, or blank for Home.
     * Stored by identity rather than by tab number so reordering the tabs cannot land the user
     * in a different folder than the one they left.
     */
    val lastFolderId: String = "",
    /**
     * Categories the user folded away, as `subscriptionId/categoryId`. Kept across restarts: a
     * group the user closed should stay closed, exactly like the folder they left open.
     */
    val collapsedCategories: Set<String> = emptySet(),
    /**
     * Advertise the tunnel's own HTTP proxy to apps (Android 10+ `VpnService.setHttpProxy`).
     *
     * What it buys: a proxy-aware app hands the tunnel a **host name** instead of an address it
     * resolved first. Names that have no DNS behind them — `.onion`, `.i2p` — only reach the
     * routing rules that know what to do with them this way; through the TUN alone the browser
     * never gets past "cannot resolve" and the rules never see the name.
     *
     * Off by default: a system proxy changes how every proxy-aware app on the device connects,
     * and only the Xray tunnel has an HTTP inbound to point them at.
     */
    val appHttpProxy: Boolean = false
)

/**
 * [GlobalSettings.collapsedCategories] as one stored string, and back. Ids are slugs and UUIDs, so
 * a newline can never appear inside one.
 */
object CollapsedCategories {
    fun encode(ids: Set<String>): String = ids.joinToString("\n")

    fun decode(raw: String?): Set<String> =
        raw.orEmpty().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}

enum class AppLanguage { SYSTEM, EN, RU }

/** Defaults used when creating a blank profile / CLI template. */
fun defaultConfig(
    listenPort: Int = 1080,
    mode: Config.Mode = Config.Mode.VPN
): Config = Config(
    domain = "",
    resolverHost = "",
    resolverPort = 53,
    resolverMode = Config.ResolverMode.MANUAL,
    resolverTransport = Config.ResolverTransport.TCP,
    resolverPathMode = Config.ResolverPathMode.AUTHORITATIVE,
    listenPort = listenPort,
    mode = mode,
    authMode = Config.AuthMode.NO_AUTH,
    username = "",
    password = ""
)
