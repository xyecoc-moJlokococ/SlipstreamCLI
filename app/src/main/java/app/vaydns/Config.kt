package app.vaydns

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

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
    // Anti-fingerprinting: DNS query type (RR type) sent in tunnel queries. 16 = TXT (default).
    // 65 = HTTPS/SVCB (less suspicious carrier). Requires a server that accepts the same type.
    val dnsQueryType: Int = 16,
    // Client-only knobs (no server-side counterpart -- safe to change unilaterally):
    // DNS label length (1-63, default 57) for the encoded subdomain; the server strips dots before
    // decoding, so this never needs to match anything server-side.
    val dnsLabelLength: Int = 57,
    // Cap on DNS poll queries/second (0 = unlimited, default). Purely a client-side pacing choice.
    val maxPollQps: Int = 0,
    // Cap on data-bearing DNS queries/sec from the QUIC send loop (0 = unlimited). Default 1000 keeps
    // the reverse path alive on Beeline UDP so multi-MB uploads can finish; raise for more speed,
    // lower if chat/"Connecting..." dies under load. Client-only.
    val maxDataQps: Int = 1000,
    // Max simultaneous SOCKS/tunnel connections the bridge admits (default 48). On operators that
    // hard rate-limit DNS queries per client (e.g. Megafon ~50 q/s), a much lower value (~4-6) keeps
    // the tiny query budget from fragmenting across many connections; pairs with maxPollQps.
    val maxActiveClients: Int = 48,
    // Encode the tunnel payload with base64u instead of base32 (default false). ~20% denser, but
    // case-sensitive -- only safe once the resolver path is confirmed to preserve label case end to
    // end. Purely a client choice, no server config needed.
    val base64uEncoding: Boolean = false
) {
    enum class Mode { PROXY, VPN }
    enum class AuthMode { NO_AUTH, LOGIN_PASSWORD }
    enum class ResolverMode { MANUAL, AUTO }
    enum class ResolverTransport { UDP, TCP }
    enum class ResolverPathMode { RECURSIVE, AUTHORITATIVE }
}

data class ConfigProfile(
    val id: String,
    val name: String,
    val config: Config
)

// AUTO-mode candidate resolvers, user-editable (Settings screen). One entry per line; LOCAL_SENTINEL
// expands to the current connection's own operator/DHCP DNS servers at probe time (ResolverSelector).
// Replaces the old hardcoded unshapedResolvers + AUTO_PUBLIC_RESOLVERS_ENABLED flag.
object DnsResolverPool {
    const val LOCAL_SENTINEL = "(local)"
    // Older builds wrote "(local dns-resolvers)" — still expand that so existing settings keep working.
    private const val LOCAL_SENTINEL_LEGACY = "(local dns-resolvers)"
    const val DEFAULT_RAW = "(local)\n82.151.127.188\n188.0.190.47\n185.22.235.137\n46.254.19.23"

    fun parse(raw: String): List<String> =
        raw.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.distinct().toList()

    fun isLocalSentinel(entry: String): Boolean =
        entry.equals(LOCAL_SENTINEL, ignoreCase = true) ||
            entry.equals(LOCAL_SENTINEL_LEGACY, ignoreCase = true)

    /** Rewrite legacy "(local dns-resolvers)" lines to "(local)" for display/storage. */
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
    val dnsResolverPool: String = DnsResolverPool.DEFAULT_RAW
)

object ConfigStore {
    private const val PREFS = "config"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_ACTIVE_PROFILE_ID = "activeProfileId"
    private const val KEY_GLOBAL_LISTEN_PORT = "globalListenPort"
    private const val KEY_GLOBAL_MODE = "globalMode"
    private const val KEY_GLOBAL_FILE_LOGGING = "globalFileLogging"
    private const val KEY_GLOBAL_TRAFFIC_NOTIFICATION = "globalTrafficNotification"
    private const val KEY_GLOBAL_LOCAL_SOCKS_AUTH = "globalLocalSocksAuth"
    private const val KEY_GLOBAL_LOCAL_SOCKS_USERNAME = "globalLocalSocksUsername"
    private const val KEY_GLOBAL_LOCAL_SOCKS_PASSWORD = "globalLocalSocksPassword"
    private const val KEY_GLOBAL_LANGUAGE = "globalLanguage"
    private const val KEY_GLOBAL_DNS_RESOLVER_POOL = "globalDnsResolverPool"

    fun load(context: Context): Config {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val global = loadGlobalSettings(context)
        return Config(
            domain = p.getString("domain", "") ?: "",
            resolverHost = p.getString("resolverHost", "") ?: "",
            resolverPort = p.getInt("resolverPort", 53),
            resolverMode = Config.ResolverMode.valueOf(
                p.getString("resolverMode", Config.ResolverMode.MANUAL.name)
                    ?: Config.ResolverMode.MANUAL.name
            ),
            resolverTransport = enumValue(
                p.getString("resolverTransport", Config.ResolverTransport.TCP.name),
                Config.ResolverTransport.TCP
            ),
            resolverPathMode = enumValue(
                p.getString("resolverPathMode", Config.ResolverPathMode.AUTHORITATIVE.name),
                Config.ResolverPathMode.AUTHORITATIVE
            ),
            listenPort = global.listenPort,
            mode = global.mode,
            authMode = Config.AuthMode.valueOf(p.getString("authMode", Config.AuthMode.NO_AUTH.name) ?: Config.AuthMode.NO_AUTH.name),
            username = p.getString("username", "") ?: "",
            password = p.getString("password", "") ?: "",
            dnsQueryType = p.getInt("dnsQueryType", 16),
            dnsLabelLength = p.getInt("dnsLabelLength", 57),
            maxPollQps = p.getInt("maxPollQps", 0),
            maxDataQps = p.getInt("maxDataQps", 1000),
            maxActiveClients = p.getInt("maxActiveClients", 48),
            base64uEncoding = p.getBoolean("base64uEncoding", false)
        )
    }

    fun save(context: Context, config: Config) {
        val global = loadGlobalSettings(context)
        saveGlobalSettings(
            context,
            GlobalSettings(
                config.listenPort,
                config.mode,
                global.fileLogging,
                global.trafficNotification,
                global.localSocksAuthEnabled,
                global.localSocksUsername,
                global.localSocksPassword,
                global.language,
                global.dnsResolverPool
            )
        )
        saveLegacy(context, config)
        val profiles = loadProfiles(context)
        if (profiles.isEmpty()) {
            val profile = ConfigProfile(newProfileId(), defaultProfileName(config), config)
            writeProfiles(context, listOf(profile), profile.id)
            return
        }
        val activeId = activeProfileId(context) ?: profiles.first().id
        writeProfiles(
            context,
            profiles.map { if (it.id == activeId) it.copy(config = config) else it },
            activeId
        )
    }

    fun loadProfiles(context: Context): List<ConfigProfile> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = p.getString(KEY_PROFILES, null).orEmpty()
        val profiles = runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    add(profileFromJson(arr.getJSONObject(i)))
                }
            }
        }.getOrDefault(emptyList())
        if (profiles.isNotEmpty()) return profiles
        val profile = ConfigProfile(newProfileId(), defaultProfileName(load(context)), load(context))
        writeProfiles(context, listOf(profile), profile.id)
        return listOf(profile)
    }

    fun activeProfileId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ACTIVE_PROFILE_ID, null)

    fun setActiveProfile(context: Context, id: String) {
        val profile = loadProfiles(context).firstOrNull { it.id == id } ?: return
        saveLegacy(context, profile.config)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ACTIVE_PROFILE_ID, id)
            .apply()
    }

    fun saveActiveProfileName(context: Context, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val profiles = loadProfiles(context)
        val activeId = activeProfileId(context) ?: profiles.firstOrNull()?.id ?: return
        writeProfiles(
            context,
            profiles.map { if (it.id == activeId) it.copy(name = trimmed) else it },
            activeId
        )
    }

    fun addProfile(context: Context, name: String, config: Config): ConfigProfile {
        val profiles = loadProfiles(context)
        val existingNames = profiles.map { it.name }.toSet()
        val profile = ConfigProfile(
            newProfileId(),
            uniqueName(name.trim().ifBlank { defaultProfileName(config) }, existingNames),
            config
        )
        saveLegacy(context, config)
        writeProfiles(context, profiles + profile, profile.id)
        return profile
    }

    /**
     * Persist a new display order for profiles. [orderedIds] is the full list of profile ids
     * top-to-bottom; any id missing from the current store is ignored, and any profile not
     * mentioned is appended at the end (safety net).
     */
    fun reorderProfiles(context: Context, orderedIds: List<String>) {
        val profiles = loadProfiles(context)
        if (profiles.size <= 1) return
        val byId = profiles.associateBy { it.id }
        val seen = LinkedHashSet<String>()
        val reordered = ArrayList<ConfigProfile>(profiles.size)
        for (id in orderedIds) {
            val p = byId[id] ?: continue
            if (seen.add(id)) reordered.add(p)
        }
        for (p in profiles) {
            if (seen.add(p.id)) reordered.add(p)
        }
        if (reordered.map { it.id } == profiles.map { it.id }) return
        val activeId = activeProfileId(context) ?: reordered.first().id
        writeProfiles(
            context,
            reordered,
            if (reordered.any { it.id == activeId }) activeId else reordered.first().id
        )
    }

    fun loadGlobalSettings(context: Context): GlobalSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val username = p.getString(KEY_GLOBAL_LOCAL_SOCKS_USERNAME, null)
            ?.takeIf { it.isNotBlank() }
            ?: "slipstream"
        val password = p.getString(KEY_GLOBAL_LOCAL_SOCKS_PASSWORD, null)
            ?.takeIf { it.isNotBlank() }
            ?: randomPassword().also {
                p.edit()
                    .putString(KEY_GLOBAL_LOCAL_SOCKS_USERNAME, username)
                    .putString(KEY_GLOBAL_LOCAL_SOCKS_PASSWORD, it)
                    .apply()
            }
        return GlobalSettings(
            listenPort = p.getInt(KEY_GLOBAL_LISTEN_PORT, p.getInt("listenPort", 1080)),
            mode = enumValue(
                p.getString(KEY_GLOBAL_MODE, p.getString("mode", Config.Mode.VPN.name)),
                Config.Mode.VPN
            ),
            // Default off: debug/file logging is opt-in. Fall back to AppLog only when the global
            // key was never written (older installs may have the AppLog flag alone).
            fileLogging = if (p.contains(KEY_GLOBAL_FILE_LOGGING)) {
                p.getBoolean(KEY_GLOBAL_FILE_LOGGING, false)
            } else {
                false
            },
            trafficNotification = p.getBoolean(KEY_GLOBAL_TRAFFIC_NOTIFICATION, false),
            localSocksAuthEnabled = p.getBoolean(KEY_GLOBAL_LOCAL_SOCKS_AUTH, true),
            localSocksUsername = username,
            localSocksPassword = password,
            language = enumValue(p.getString(KEY_GLOBAL_LANGUAGE, AppLanguage.SYSTEM.name), AppLanguage.SYSTEM),
            dnsResolverPool = DnsResolverPool.normalize(
                p.getString(KEY_GLOBAL_DNS_RESOLVER_POOL, DnsResolverPool.DEFAULT_RAW)
                    ?: DnsResolverPool.DEFAULT_RAW
            )
        )
    }

    fun saveGlobalSettings(context: Context, settings: GlobalSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_GLOBAL_LISTEN_PORT, settings.listenPort)
            .putString(KEY_GLOBAL_MODE, settings.mode.name)
            .putBoolean(KEY_GLOBAL_FILE_LOGGING, settings.fileLogging)
            .putBoolean(KEY_GLOBAL_TRAFFIC_NOTIFICATION, settings.trafficNotification)
            .putBoolean(KEY_GLOBAL_LOCAL_SOCKS_AUTH, settings.localSocksAuthEnabled)
            .putString(KEY_GLOBAL_LOCAL_SOCKS_USERNAME, settings.localSocksUsername.ifBlank { "slipstream" })
            .putString(KEY_GLOBAL_LOCAL_SOCKS_PASSWORD, settings.localSocksPassword.ifBlank { randomPassword() })
            .putString(KEY_GLOBAL_LANGUAGE, settings.language.name)
            .putString(KEY_GLOBAL_DNS_RESOLVER_POOL, DnsResolverPool.normalize(settings.dnsResolverPool))
            .putInt("listenPort", settings.listenPort)
            .putString("mode", settings.mode.name)
            .apply()
        app.slipnet.util.AppLog.setFileLoggingEnabled(context, settings.fileLogging)
    }

    fun effectiveConfig(context: Context, profileConfig: Config = load(context)): Config {
        val global = loadGlobalSettings(context)
        return profileConfig.copy(
            listenPort = global.listenPort,
            mode = global.mode
        )
    }

    fun importProfile(context: Context, uri: Uri): ConfigProfile? {
        val imported = SlipstreamLinkParser.parse(uri, effectiveConfig(context)) ?: return null
        return addProfile(context, imported.name, imported.config)
    }

    /**
     * Import a profile from free-form text (clipboard paste or file contents). Accepts:
     * - a slipstream:// link (or one embedded in surrounding text)
     * - a JSON profile blob ({"name", "config": {...}}) or bare config JSON ({"domain": ...})
     * - a base64 / query-string payload understood by SlipstreamLinkParser
     */
    fun importProfileFromText(context: Context, text: String): ConfigProfile? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val base = effectiveConfig(context)

        // 1) Explicit or embedded slipstream URI.
        val uriText = SLIPSTREAM_URI_IN_TEXT.find(trimmed)?.value
            ?: trimmed.takeIf { it.startsWith("slipstream:", ignoreCase = true) }?.lineSequence()?.firstOrNull()
        if (uriText != null) {
            importProfile(context, Uri.parse(uriText.trim()))?.let { return it }
        }

        // 2) JSON profile or config object exported from this app / hand-written.
        if (trimmed.startsWith("{")) {
            runCatching {
                val json = JSONObject(trimmed)
                when {
                    json.has("config") -> {
                        val parsed = profileFromJson(json)
                        return addProfile(context, parsed.name, parsed.config)
                    }
                    json.has("domain") || json.has("resolverHost") -> {
                        val config = configFromJson(json)
                        return addProfile(context, defaultProfileName(config), config)
                    }
                }
            }
        }

        // 3) Raw payload (base64 config=... or key=value query) via the import endpoint.
        val payloadUri = Uri.parse("slipstream://import").buildUpon()
            .appendQueryParameter("config", trimmed)
            .build()
        val fromPayload = SlipstreamLinkParser.parse(payloadUri, base) ?: return null
        return addProfile(context, fromPayload.name, fromPayload.config)
    }

    private val SLIPSTREAM_URI_IN_TEXT =
        Regex("slipstream:[^\\s\"'<>]+", RegexOption.IGNORE_CASE)

    fun saveProfile(context: Context, profile: ConfigProfile): ConfigProfile {
        val profiles = loadProfiles(context)
        val activeId = activeProfileId(context) ?: profiles.firstOrNull()?.id ?: profile.id
        val existingNames = profiles
            .filterNot { it.id == profile.id }
            .map { it.name }
            .toSet()
        val cleanProfile = profile.copy(
            name = uniqueName(profile.name.trim().ifBlank { defaultProfileName(profile.config) }, existingNames),
            config = profile.config.copy(
                domain = profile.config.domain.trim(),
                resolverHost = profile.config.resolverHost.trim()
            )
        )
        val updated = if (profiles.any { it.id == cleanProfile.id }) {
            profiles.map { if (it.id == cleanProfile.id) cleanProfile else it }
        } else {
            profiles + cleanProfile
        }
        if (cleanProfile.id == activeId) saveLegacy(context, cleanProfile.config)
        writeProfiles(context, updated, if (updated.any { it.id == activeId }) activeId else updated.first().id)
        return cleanProfile
    }

    fun deleteActiveProfile(context: Context): ConfigProfile {
        val profiles = loadProfiles(context)
        if (profiles.size <= 1) return profiles.first()
        val activeId = activeProfileId(context) ?: profiles.first().id
        val remaining = profiles.filterNot { it.id == activeId }
        val next = remaining.first()
        saveLegacy(context, next.config)
        writeProfiles(context, remaining, next.id)
        return next
    }

    fun deleteProfile(context: Context, id: String): ConfigProfile {
        val profiles = loadProfiles(context)
        if (profiles.size <= 1) return profiles.first()
        val remaining = profiles.filterNot { it.id == id }
        if (remaining.size == profiles.size) return profiles.first()
        val activeId = activeProfileId(context) ?: profiles.first().id
        val next = if (activeId == id) {
            remaining.first()
        } else {
            remaining.firstOrNull { it.id == activeId } ?: remaining.first()
        }
        if (next.id == activeId || activeId == id) saveLegacy(context, next.config)
        writeProfiles(context, remaining, next.id)
        return next
    }

    private fun saveLegacy(context: Context, config: Config) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("domain", config.domain.trim())
            .putString("resolverHost", config.resolverHost.trim())
            .putInt("resolverPort", config.resolverPort)
            .putString("resolverMode", config.resolverMode.name)
            .putString("resolverTransport", config.resolverTransport.name)
            .putString("resolverPathMode", config.resolverPathMode.name)
            .putInt("listenPort", config.listenPort)
            .putString("mode", config.mode.name)
            .putString("authMode", config.authMode.name)
            .putString("username", config.username)
            .putString("password", config.password)
            .putInt("dnsQueryType", config.dnsQueryType)
            .putInt("dnsLabelLength", config.dnsLabelLength)
            .putInt("maxPollQps", config.maxPollQps)
            .putInt("maxDataQps", config.maxDataQps)
            .putInt("maxActiveClients", config.maxActiveClients)
            .putBoolean("base64uEncoding", config.base64uEncoding)
            .apply()
    }

    private fun writeProfiles(context: Context, profiles: List<ConfigProfile>, activeId: String) {
        val arr = JSONArray()
        profiles.forEach { arr.put(profileToJson(it)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PROFILES, arr.toString())
            .putString(KEY_ACTIVE_PROFILE_ID, activeId)
            .apply()
    }

    private fun profileToJson(profile: ConfigProfile): JSONObject =
        JSONObject()
            .put("id", profile.id)
            .put("name", profile.name)
            .put("config", configToJson(profile.config))

    private fun profileFromJson(json: JSONObject): ConfigProfile =
        ConfigProfile(
            id = json.optString("id").ifBlank { newProfileId() },
            name = json.optString("name").ifBlank { t(S.PROFILE_NAME_DEFAULT_IMPORTED) },
            config = configFromJson(json.optJSONObject("config") ?: JSONObject())
        )

    private fun configToJson(config: Config): JSONObject =
        JSONObject()
            .put("domain", config.domain.trim())
            .put("resolverHost", config.resolverHost.trim())
            .put("resolverPort", config.resolverPort)
            .put("resolverMode", config.resolverMode.name)
            .put("resolverTransport", config.resolverTransport.name)
            .put("resolverPathMode", config.resolverPathMode.name)
            .put("listenPort", config.listenPort)
            .put("mode", config.mode.name)
            .put("authMode", config.authMode.name)
            .put("username", config.username)
            .put("password", config.password)
            .put("dnsQueryType", config.dnsQueryType)
            .put("dnsLabelLength", config.dnsLabelLength)
            .put("maxPollQps", config.maxPollQps)
            .put("maxDataQps", config.maxDataQps)
            .put("maxActiveClients", config.maxActiveClients)
            .put("base64uEncoding", config.base64uEncoding)

    private fun configFromJson(json: JSONObject): Config =
        Config(
            domain = json.optString("domain", ""),
            resolverHost = json.optString("resolverHost", ""),
            resolverPort = json.optInt("resolverPort", 53),
            resolverMode = enumValue(json.optString("resolverMode"), Config.ResolverMode.MANUAL),
            resolverTransport = enumValue(json.optString("resolverTransport"), Config.ResolverTransport.TCP),
            resolverPathMode = enumValue(json.optString("resolverPathMode"), Config.ResolverPathMode.AUTHORITATIVE),
            listenPort = json.optInt("listenPort", 1080),
            mode = enumValue(json.optString("mode"), Config.Mode.VPN),
            authMode = enumValue(json.optString("authMode"), Config.AuthMode.NO_AUTH),
            username = json.optString("username", ""),
            password = json.optString("password", ""),
            dnsQueryType = json.optInt("dnsQueryType", 16),
            dnsLabelLength = json.optInt("dnsLabelLength", 57),
            maxPollQps = json.optInt("maxPollQps", 0),
            maxDataQps = json.optInt("maxDataQps", 1000),
            maxActiveClients = json.optInt("maxActiveClients", 48),
            base64uEncoding = json.optBoolean("base64uEncoding", false)
        )

    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
        runCatching { enumValueOf<T>(value.orEmpty()) }.getOrDefault(fallback)

    private fun newProfileId(): String = System.currentTimeMillis().toString(36)

    private fun defaultProfileName(config: Config): String =
        config.domain.ifBlank { t(S.PROFILE_NAME_DEFAULT) }

    private fun uniqueName(base: String, existing: Set<String>): String {
        if (base !in existing) return base
        var index = 2
        while ("$base $index" in existing) index++
        return "$base $index"
    }

    private fun randomPassword(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val random = SecureRandom()
        return buildString {
            repeat(8) {
                append(alphabet[random.nextInt(alphabet.length)])
            }
        }
    }
}

private data class ImportedProfile(val name: String, val config: Config)

private object SlipstreamLinkParser {
    fun parse(uri: Uri, base: Config): ImportedProfile? {
        if (uri.scheme?.lowercase() != "slipstream") return null
        val params = linkedMapOf<String, String>()
        uri.queryParameterNames.forEach { key ->
            uri.getQueryParameter(key)?.let { params[key.lowercase()] = it }
        }
        parsePayload(params["config"] ?: params["profile"] ?: params["data"])?.let { params.putAll(it) }

        val host = uri.host.orEmpty().takeIf { it.isNotBlank() && it != "import" && it != "profile" }
        val domain = first(params, "domain", "server", "sni", "host")
            ?: host
            ?: uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: return null
        val resolver = first(params, "resolver", "resolverhost", "dns", "dnsserver")
        val resolverMode = when (first(params, "resolvermode", "dnsmode")?.lowercase()) {
            "auto" -> Config.ResolverMode.AUTO
            "manual" -> Config.ResolverMode.MANUAL
            else -> base.resolverMode
        }
        val transport = when (first(params, "transport", "resolvertransport")?.lowercase()) {
            "udp" -> Config.ResolverTransport.UDP
            "tcp" -> Config.ResolverTransport.TCP
            else -> base.resolverTransport
        }
        val pathMode = when (first(params, "resolverpathmode", "pathmode", "authoritative")?.lowercase()) {
            "recursive", "resolver", "false", "off", "0" -> Config.ResolverPathMode.RECURSIVE
            "authoritative", "auth", "true", "on", "1" -> Config.ResolverPathMode.AUTHORITATIVE
            else -> base.resolverPathMode
        }
        val authMode = if (first(params, "username", "user").orEmpty().isNotBlank() || first(params, "password", "pass").orEmpty().isNotBlank()) {
            Config.AuthMode.LOGIN_PASSWORD
        } else {
            base.authMode
        }
        val dnsLabelLength = first(params, "dnslabellength")?.toIntOrNull()?.coerceIn(1, 63) ?: base.dnsLabelLength
        val maxPollQps = first(params, "maxpollqps")?.toIntOrNull()?.coerceAtLeast(0) ?: base.maxPollQps
        val maxDataQps = first(params, "maxdataqps")?.toIntOrNull()?.coerceAtLeast(0) ?: base.maxDataQps
        val maxActiveClients = first(params, "maxactiveclients")?.toIntOrNull()?.coerceAtLeast(1) ?: base.maxActiveClients
        val dnsQueryType = first(params, "dnsquerytype")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: base.dnsQueryType
        val base64uEncoding = first(params, "base64uencoding", "base64u")?.let {
            it == "true" || it == "1" || it == "on"
        } ?: base.base64uEncoding
        val config = base.copy(
            domain = domain,
            resolverHost = resolver ?: base.resolverHost,
            resolverPort = first(params, "resolverport", "dnsport", "port")?.toIntOrNull() ?: base.resolverPort,
            resolverMode = resolverMode,
            resolverTransport = transport,
            resolverPathMode = pathMode,
            authMode = authMode,
            username = first(params, "username", "user") ?: base.username,
            password = first(params, "password", "pass") ?: base.password,
            dnsLabelLength = dnsLabelLength,
            maxPollQps = maxPollQps,
            maxDataQps = maxDataQps,
            maxActiveClients = maxActiveClients,
            dnsQueryType = dnsQueryType,
            base64uEncoding = base64uEncoding
        )
        return ImportedProfile(first(params, "name", "profileName") ?: domain, config)
    }

    private fun parsePayload(value: String?): Map<String, String>? {
        if (value.isNullOrBlank()) return null
        val decoded = runCatching {
            String(Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
        }.getOrElse { value }
        return if (decoded.trimStart().startsWith("{")) {
            runCatching {
                val json = JSONObject(decoded)
                json.keys().asSequence().associate { it.lowercase() to json.optString(it) }
            }.getOrNull()
        } else {
            Uri.parse("slipstream://import?$decoded").queryParameterNames.associateWith {
                Uri.parse("slipstream://import?$decoded").getQueryParameter(it).orEmpty()
            }.mapKeys { it.key.lowercase() }
        }
    }

    private fun first(params: Map<String, String>, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { params[it.lowercase()]?.takeIf { value -> value.isNotBlank() } }
}
