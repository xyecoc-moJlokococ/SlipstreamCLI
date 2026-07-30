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
    val s3Login: String = "",
    val s3Psk: String = "",
    val xrayConfigJson: String = "",
    val cdnUrl: String = "",
    val cdnPsk: String = "",
    val cdnMimic: String = "mixed"
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
    val subscriptionId: String? = null
)

object DnsResolverPool {
    const val LOCAL_SENTINEL = "(local)"
    private const val LOCAL_SENTINEL_LEGACY = "(local dns-resolvers)"
    const val DEFAULT_RAW = "(local)\n82.151.127.188\n188.0.190.47\n185.22.235.137\n46.254.19.23"

    fun parse(raw: String): List<String> =
        raw.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.distinct().toList()

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
     * Where the "Home" folder sits among the tabs. Subscriptions carry their own order in the
     * subscription list, but Home is not one of them and still has to be draggable, so its slot
     * lives here.
     */
    val homeFolderIndex: Int = 0
)

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
