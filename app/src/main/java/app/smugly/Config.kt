package app.smugly

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

// Config / ConfigProfile / DnsResolverPool / GlobalSettings / AppLanguage live in the
// multiplatform `:shared` module (commonMain) so desktop + iOS compile the same models.
// This file keeps the Android SharedPreferences persistence and deep-link import paths.

object ConfigStore {
    private const val PREFS = "config"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_SUBSCRIPTIONS = "subscriptions"
    private const val KEY_ACTIVE_PROFILE_ID = "activeProfileId"
    private const val KEY_GLOBAL_LISTEN_PORT = "globalListenPort"
    private const val KEY_GLOBAL_MODE = "globalMode"
    private const val KEY_GLOBAL_FILE_LOGGING = "globalFileLogging"
    private const val KEY_GLOBAL_TRAFFIC_NOTIFICATION = "globalTrafficNotification"
    private const val KEY_GLOBAL_LOCAL_SOCKS_AUTH = "globalLocalSocksAuth"
    private const val KEY_GLOBAL_LOCAL_SOCKS_USERNAME = "globalLocalSocksUsername"
    private const val KEY_GLOBAL_LOCAL_SOCKS_PASSWORD = "globalLocalSocksPassword"
    private const val KEY_GLOBAL_LANGUAGE = "globalLanguage"
    private const val KEY_GLOBAL_HOME_FOLDER_INDEX = "globalHomeFolderIndex"
    private const val KEY_GLOBAL_LAST_FOLDER_ID = "globalLastFolderId"
    private const val KEY_GLOBAL_COLLAPSED_CATEGORIES = "globalCollapsedCategories"
    private const val KEY_GLOBAL_APP_HTTP_PROXY = "globalAppHttpProxy"
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
            dnsLabelLengthJitter = p.getInt("dnsLabelLengthJitter", 4),
            maxPollQps = p.getInt("maxPollQps", 1400),
            maxDataQps = p.getInt("maxDataQps", 800),
            maxActiveClients = p.getInt("maxActiveClients", 40),
            base64uEncoding = p.getBoolean("base64uEncoding", false),
            protocol = enumValue(p.getString("protocol", Config.TunnelProtocol.SLIPSTREAM.name), Config.TunnelProtocol.SLIPSTREAM),
            s3Endpoint = p.getString("s3Endpoint", "") ?: "",
            s3Bucket = p.getString("s3Bucket", "") ?: "",
            s3AccessKey = p.getString("s3AccessKey", "") ?: "",
            s3SecretKey = p.getString("s3SecretKey", "") ?: "",
            s3Prefix = p.getString("s3Prefix", "")?.takeIf { it.isNotBlank() } ?: "s3fu",
            s3Psk = p.getString("s3Psk", "") ?: "",
            xrayConfigJson = p.getString("xrayConfigJson", "") ?: "",
            cdnfuUrl = p.getString("cdnfuUrl", "") ?: "",
            cdnfuHost = p.getString("cdnfuHost", "") ?: "",
            cdnfuPsk = p.getString("cdnfuPsk", "") ?: "",
            cdnfuMimic = p.getString("cdnfuMimic", "")?.ifBlank { "mixed" } ?: "mixed",
            cdnfuUplinkMethod = p.getString("cdnfuUplinkMethod", "")?.ifBlank { "POST" } ?: "POST",
            cdnfuUplinkPath = p.getString("cdnfuUplinkPath", "")?.ifBlank { "api" } ?: "api",
            cdnfuUplinkData = p.getString("cdnfuUplinkData", "")?.ifBlank { "body" } ?: "body",
            cdnfuXhttpPlacement = p.getString("cdnfuXhttpPlacement", "")?.ifBlank { "cookie" } ?: "cookie",
            cdnfuDownlinkMode = p.getString("cdnfuDownlinkMode", "")?.ifBlank { "stream" } ?: "stream",
            cdnfuMultipath = p.getInt("cdnfuMultipath", 4).coerceIn(0, 32),
            // The whole-config TOML has to survive here too. These prefs are the config the
            // service actually runs; a profile's text lives in the profile JSON, and if it
            // is not carried across, selecting that profile silently falls back to the
            // derived config and the operator's edits do nothing.
            s3fuToml = p.getString("s3fuToml", "") ?: "",
            cdnfuToml = p.getString("cdnfuToml", "") ?: "",
        )
    }

    fun save(context: Context, config: Config) {
        val global = loadGlobalSettings(context)
        // `copy`, never a fresh GlobalSettings: this runs on every connect, and rebuilding the
        // record from a hand-written argument list silently reset every field that list did not
        // mention — the open folder, the tab order, folded categories, the app proxy switch — back
        // to its default. Which is why a setting could be turned on, and be off again the moment
        // the user pressed connect.
        saveGlobalSettings(
            context,
            global.copy(listenPort = config.listenPort, mode = config.mode)
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
        // No auto-created placeholder: an empty list is a legitimate state, and the UI shows an
        // "import a configuration to get started" message for it.
        return profiles
    }

    fun loadSubscriptions(context: Context): List<app.smugly.subscription.Subscription> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SUBSCRIPTIONS, null).orEmpty()
        return app.smugly.subscription.SubscriptionJson.listFromString(raw)
    }

    fun saveSubscriptions(context: Context, subs: List<app.smugly.subscription.Subscription>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SUBSCRIPTIONS, app.smugly.subscription.SubscriptionJson.listToString(subs))
            .apply()
    }

    /** Replace the whole profile list, keeping the active id when it still exists. */
    fun replaceProfiles(context: Context, profiles: List<ConfigProfile>) {
        val active = activeProfileId(context)
        val keep = if (profiles.any { it.id == active }) active.orEmpty() else profiles.firstOrNull()?.id.orEmpty()
        writeProfiles(context, profiles, keep)
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
            ),
            homeFolderIndex = p.getInt(KEY_GLOBAL_HOME_FOLDER_INDEX, 0),
            lastFolderId = p.getString(KEY_GLOBAL_LAST_FOLDER_ID, "") ?: "",
            collapsedCategories = CollapsedCategories.decode(
                p.getString(KEY_GLOBAL_COLLAPSED_CATEGORIES, "")
            ),
            appHttpProxy = p.getBoolean(KEY_GLOBAL_APP_HTTP_PROXY, false)
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
            .putInt(KEY_GLOBAL_HOME_FOLDER_INDEX, settings.homeFolderIndex)
            .putString(KEY_GLOBAL_LAST_FOLDER_ID, settings.lastFolderId)
            .putString(
                KEY_GLOBAL_COLLAPSED_CATEGORIES,
                CollapsedCategories.encode(settings.collapsedCategories)
            )
            .putBoolean(KEY_GLOBAL_APP_HTTP_PROXY, settings.appHttpProxy)
            .putInt("listenPort", settings.listenPort)
            .putString("mode", settings.mode.name)
            .apply()
        app.smugly.util.AppLog.setFileLoggingEnabled(context, settings.fileLogging)
    }

    fun effectiveConfig(context: Context, profileConfig: Config = load(context)): Config {
        val global = loadGlobalSettings(context)
        return profileConfig.copy(
            listenPort = global.listenPort,
            mode = global.mode
        )
    }

    /**
     * Build a shareable deep link for [profile].
     * - Slipstream (DNS) profiles → `slipstream://import?...`
     * - S3FU profiles → `s3fu://import?...`
     * - CDNFU profiles → `cdnfu://import?...`
     *
     * The full config is carried as a URL-safe base64 JSON blob so every field round-trips
     * (client knobs, s3 credentials, etc.). A human-readable `name` query param is included.
     */
    fun exportProfileLink(profile: ConfigProfile): String {
        val scheme = when (profile.config.protocol) {
            Config.TunnelProtocol.S3FU -> "s3fu"
            Config.TunnelProtocol.CDNFU -> "cdnfu"
            Config.TunnelProtocol.XRAY -> "xray"
            else -> "slipstream"
        }
        val name = profile.name.trim().ifBlank { defaultProfileName(profile.config) }
        val encoded = Base64.encodeToString(
            configToJson(profile.config).toString().toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return Uri.Builder()
            .scheme(scheme)
            .authority("import")
            .appendQueryParameter("name", name)
            .appendQueryParameter("config", encoded)
            .build()
            .toString()
    }

    fun importProfile(context: Context, uri: Uri): ConfigProfile? {
        val imported = parseProfileLink(uri, effectiveConfig(context)) ?: return null
        return addProfile(context, imported.name, imported.config)
    }

    /**
     * Import every `vless://` link found in [text] as its own Xray profile.
     * Handles a whole subscription paste, not just a single link.
     */
    fun importVlessProfiles(context: Context, text: String): List<ConfigProfile> {
        val base = effectiveConfig(context)
        return VlessLinkParser.findAll(text).mapNotNull { raw ->
            val link = VlessLinkParser.parse(raw) ?: return@mapNotNull null
            val config = base.copy(
                protocol = Config.TunnelProtocol.XRAY,
                xrayConfigJson = XrayConfigBuilder.build(link, base.listenPort)
            )
            addProfile(context, link.remarks, config)
        }
    }

    /**
     * Parse one config link into a profile **without storing it**.
     *
     * Every other entry point here persists as it parses, which is right for a clipboard paste and
     * badly wrong for a subscription refresh: the repository only wants the parsed object and
     * writes the whole group itself. Going through the persisting path meant each refresh quietly
     * left one extra copy of every server behind in the Home folder — a 148-server list grew Home
     * by 148 profiles every single time it updated.
     *
     * The returned profile has a blank id; the caller assigns one.
     */
    fun parseProfileFromLink(context: Context, raw: String): ConfigProfile? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val base = effectiveConfig(context)
        VlessLinkParser.parse(trimmed)?.let { link ->
            return ConfigProfile(
                id = "",
                name = link.remarks,
                config = base.copy(
                    protocol = Config.TunnelProtocol.XRAY,
                    xrayConfigJson = XrayConfigBuilder.build(link, base.listenPort)
                )
            )
        }
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
        val imported = parseProfileLink(uri, base) ?: return null
        return ConfigProfile(id = "", name = imported.name, config = imported.config)
    }

    /**
     * Import everything [text] contains. A vless:// paste (one link or a whole
     * subscription) yields one profile per link; anything else falls back to the
     * single-profile path in [importProfileFromText].
     */
    fun importProfilesFromText(context: Context, text: String): List<ConfigProfile> {
        if (VlessLinkParser.looksLikeLink(text)) {
            val profiles = importVlessProfiles(context, text)
            if (profiles.isNotEmpty()) return profiles
        }
        return listOfNotNull(importProfileFromText(context, text))
    }

    /**
     * Import a profile from free-form text (clipboard paste or file contents). Accepts:
     * - one or more vless:// links (each becomes an Xray profile)
     * - a slipstream://, s3fu://, cdnfu:// or xray:// link (or one embedded in surrounding text)
     * - a JSON profile blob ({"name", "config": {...}}) or bare config JSON ({"domain": ...})
     * - a raw Xray config JSON ({"inbounds": ..., "outbounds": ...})
     * - a base64 / query-string payload understood by the link parser
     *
     * Returns the last profile created, so a multi-link paste still yields something
     * to select; callers wanting the full set use [importVlessProfiles].
     */
    fun importProfileFromText(context: Context, text: String): ConfigProfile? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val base = effectiveConfig(context)

        // 0) vless:// links -- the Xray import path.
        if (VlessLinkParser.looksLikeLink(trimmed)) {
            importVlessProfiles(context, trimmed).lastOrNull()?.let { return it }
        }

        // 1) Explicit or embedded slipstream:// / s3fu:// / cdnfu:// / xray:// URI.
        val uriText = PROFILE_URI_IN_TEXT.find(trimmed)?.value
            ?: trimmed.takeIf {
                it.startsWith("slipstream:", ignoreCase = true) ||
                    it.startsWith("s3fu:", ignoreCase = true) ||
                    it.startsWith("cdnfu:", ignoreCase = true) ||
                    it.startsWith("xray:", ignoreCase = true)
            }?.lineSequence()?.firstOrNull()
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
                    json.has("domain") || json.has("resolverHost") ||
                        json.has("s3Endpoint") || json.has("cdnfuUrl") ||
                        json.has("protocol") -> {
                        val config = configFromJson(json)
                        return addProfile(context, defaultProfileName(config), config)
                    }
                    // A bare Xray config pasted straight from a panel / another client.
                    json.has("outbounds") -> {
                        val config = base.copy(
                            protocol = Config.TunnelProtocol.XRAY,
                            xrayConfigJson = XrayConfigBuilder.withSocksPort(trimmed, base.listenPort)
                        )
                        return addProfile(context, defaultProfileName(config), config)
                    }
                }
            }
        }

        // 3) Raw payload (base64 config=... or key=value query) via the import endpoint.
        // Not for a link belonging to a protocol this app cannot run. Such a URI otherwise walks
        // into the legacy query-param branch below and comes back out as a *Slipstream* profile
        // named after the host — which is how a trojan:// link ended up in the list looking like a
        // working DNS-tunnel profile. Refusing it is the honest answer.
        if (UNSUPPORTED_LINK_SCHEME.containsMatchIn(trimmed)) return null
        val payloadUri = Uri.parse("slipstream://import").buildUpon()
            .appendQueryParameter("config", trimmed)
            .build()
        val fromPayload = parseProfileLink(payloadUri, base) ?: return null
        return addProfile(context, fromPayload.name, fromPayload.config)
    }

    private val PROFILE_URI_IN_TEXT =
        Regex("(?:slipstream|s3fu|cdnfu|xray):[^\\s\"'<>]+", RegexOption.IGNORE_CASE)

    /**
     * Schemes other clients use for protocols this app does not speak. `vless://` is absent on
     * purpose — it is handled earlier and reaching the fallback with one means it failed to parse,
     * which deserves the same refusal.
     */
    private val UNSUPPORTED_LINK_SCHEME = Regex(
        "^\\s*(trojan|vmess|vless|ss|ssr|hysteria2?|hy2|tuic|wireguard|snell|juicity|anytls|socks5?)://",
        RegexOption.IGNORE_CASE
    )

    /**
     * Parse a slipstream://, s3fu://, cdnfu:// or xray:// deep link into a name + config.
     * Prefers a full base64 JSON config payload (export format); falls back to legacy
     * query-param forms used by the bot / hand-written links.
     *
     * `vless://` links do not come through here -- they are not profile containers
     * but server definitions, and are translated by [importVlessProfiles].
     */
    private fun parseProfileLink(uri: Uri, base: Config): ImportedProfile? {
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "slipstream" && scheme != "s3fu" && scheme != "cdnfu" && scheme != "xray") {
            return null
        }

        val params = linkedMapOf<String, String>()
        uri.queryParameterNames.forEach { key ->
            uri.getQueryParameter(key)?.let { params[key.lowercase()] = it }
        }

        // Full config JSON (optionally base64) in config/profile/data — export format.
        val payloadRaw = params["config"] ?: params["profile"] ?: params["data"]
        val decoded = decodeLinkPayload(payloadRaw)
        if (decoded != null && decoded.trimStart().startsWith("{")) {
            runCatching {
                val json = JSONObject(decoded)
                val configJson = json.optJSONObject("config")
                val config = when {
                    configJson != null -> configFromJson(configJson)
                    json.has("domain") || json.has("resolverHost") ||
                        json.has("s3Endpoint") || json.has("protocol") ||
                        json.has("s3Bucket") || json.has("s3Psk") ||
                        json.has("cdnfuUrl") || json.has("cdnfuPsk") ||
                        json.has("xrayConfigJson") -> configFromJson(json)
                    else -> null
                }
                if (config != null) {
                    val forced = when (scheme) {
                        "s3fu" -> config.copy(protocol = Config.TunnelProtocol.S3FU)
                        "cdnfu" -> config.copy(protocol = Config.TunnelProtocol.CDNFU)
                        "xray" -> config.copy(protocol = Config.TunnelProtocol.XRAY)
                        else -> config
                    }
                    val name = sequenceOf(
                        json.optString("name").takeIf { it.isNotBlank() },
                        params["name"],
                        params["profilename"],
                        defaultProfileName(forced)
                    ).first { !it.isNullOrBlank() }!!
                    return ImportedProfile(name, forced)
                }
            }
        }

        // s3fu query-param form (hand-written / bot links without full JSON blob).
        if (scheme == "s3fu") {
            return parseS3fuQueryParams(params, base)
        }

        // cdnfu query-param form: url + psk (+ optional knobs).
        if (scheme == "cdnfu") {
            return parseCdnfuQueryParams(params, base)
        }

        // xray:// only ever carries the exported JSON blob handled above; there is
        // no query-param shorthand to fall back to (vless:// fills that role).
        if (scheme == "xray") return null

        // Legacy slipstream query-param form (bot deep links, etc.).
        // Merge decoded key=value payload into params the way SlipstreamLinkParser expects.
        if (decoded != null && !decoded.trimStart().startsWith("{")) {
            Uri.parse("slipstream://import?$decoded").queryParameterNames.forEach { key ->
                Uri.parse("slipstream://import?$decoded").getQueryParameter(key)?.let {
                    params[key.lowercase()] = it
                }
            }
        }
        return SlipstreamLinkParser.parseFromParams(params, uri, base)
    }

    private fun parseS3fuQueryParams(params: Map<String, String>, base: Config): ImportedProfile? {
        fun first(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { params[it.lowercase()]?.takeIf { v -> v.isNotBlank() } }

        val endpoint = first("endpoint", "s3endpoint", "s3_endpoint")
        val bucket = first("bucket", "s3bucket", "s3_bucket")
        val accessKey = first("accesskey", "s3accesskey", "access_key", "s3_access_key")
        val secretKey = first("secretkey", "s3secretkey", "secret_key", "s3_secret_key")
        // s3fu dropped the login: the PSK is the whole credential. Links minted by
        // older builds still carry one, so it is parsed and used as a profile-name
        // fallback, but it no longer takes part in the config.
        val legacyLogin = first("login", "s3login", "user", "userid", "s3userid", "s3_login")
        val psk = first("psk", "s3psk", "s3_psk")
        // Need at least endpoint + bucket, or a psk with an endpoint.
        if (endpoint.isNullOrBlank() && bucket.isNullOrBlank() && psk.isNullOrBlank()) {
            return null
        }
        val config = base.copy(
            protocol = Config.TunnelProtocol.S3FU,
            s3Endpoint = endpoint ?: base.s3Endpoint,
            s3Bucket = bucket ?: base.s3Bucket,
            s3AccessKey = accessKey ?: base.s3AccessKey,
            s3SecretKey = secretKey ?: base.s3SecretKey,
            s3Prefix = (first("prefix", "s3prefix", "s3_prefix") ?: base.s3Prefix).ifBlank { "s3fu" },
            s3Psk = psk ?: base.s3Psk,
            // A panel that has a client config template sends the whole config alongside
            // the parameters. When present it is what the engine runs; the parameters are
            // still parsed above so an older app build (and the profile name) still work.
            s3fuToml = decodeConfigParam(params["toml"])
        )
        val name = first("name", "profilename")
            ?: legacyLogin?.takeIf { it.isNotBlank() }
            ?: config.s3Bucket.takeIf { it.isNotBlank() }
            ?: defaultProfileName(config)
        return ImportedProfile(name, config)
    }

    private fun parseCdnfuQueryParams(params: Map<String, String>, base: Config): ImportedProfile? {
        fun first(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { params[it.lowercase()]?.takeIf { v -> v.isNotBlank() } }

        val url = first("url", "cdnfuurl", "cdn_url", "endpoint", "host")
        val psk = first("psk", "cdnfupsk", "cdn_psk", "password", "pass")
        if (url.isNullOrBlank() && psk.isNullOrBlank()) return null
        val config = base.copy(
            protocol = Config.TunnelProtocol.CDNFU,
            cdnfuUrl = url ?: base.cdnfuUrl,
            // NOT "host" — that is already a legacy alias for the URL just above, and
            // renaming it would silently break links already in the wild.
            cdnfuHost = first("hostheader", "host_header", "cdnfuhost", "sni") ?: base.cdnfuHost,
            cdnfuPsk = psk ?: base.cdnfuPsk,
            cdnfuMimic = first("mimic", "path_mimic") ?: base.cdnfuMimic.ifBlank { "mixed" },
            cdnfuUplinkMethod = first("method", "uplink_method") ?: base.cdnfuUplinkMethod.ifBlank { "POST" },
            cdnfuUplinkPath = first("uplink_path", "path") ?: base.cdnfuUplinkPath.ifBlank { "api" },
            cdnfuUplinkData = first("uplink_data", "data") ?: base.cdnfuUplinkData.ifBlank { "body" },
            cdnfuXhttpPlacement = first("xhttp", "placement", "xhttp_placement")
                ?: base.cdnfuXhttpPlacement.ifBlank { "cookie" },
            cdnfuDownlinkMode = first("downlink", "dl", "downlink_mode")
                ?: base.cdnfuDownlinkMode.ifBlank { "stream" },
            cdnfuMultipath = first("multipath", "mp", "paths")?.toIntOrNull()?.coerceIn(0, 32)
                ?: base.cdnfuMultipath,
            // A panel with a client config template sends the whole config alongside the
            // parameters. When present it is what the engine runs; the parameters above are
            // still parsed so an older app build (and the profile name) keep working.
            cdnfuToml = decodeConfigParam(params["toml"])
        )
        val name = first("name", "profilename")
            ?: runCatching {
                java.net.URI(if (config.cdnfuUrl.contains("://")) config.cdnfuUrl else "https://${config.cdnfuUrl}")
                    .host
            }.getOrNull()?.takeIf { !it.isNullOrBlank() }
            ?: defaultProfileName(config)
        return ImportedProfile(name, config)
    }

    /**
     * The `toml` link parameter: a whole client config the panel rendered for this user.
     *
     * Handled separately from [decodeLinkPayload] because that one tries base64 first,
     * and a short config with no punctuation could decode into binary garbage instead of
     * failing. A config is recognised by its shape and taken as-is; anything else is
     * assumed to be base64 so a panel may still send it wrapped.
     */
    private fun decodeConfigParam(value: String?): String {
        val raw = value?.takeIf { it.isNotBlank() } ?: return ""
        val looksLikeConfig = raw.contains('=') && (raw.contains('\n') || raw.contains('"'))
        if (looksLikeConfig) return raw
        return decodeLinkPayload(raw) ?: raw
    }

    private fun decodeLinkPayload(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            String(
                Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                Charsets.UTF_8
            )
        }.getOrElse { value }
    }

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

    /** Returns the profile that is active afterwards, or null once nothing is left. */
    fun deleteProfile(context: Context, id: String): ConfigProfile? {
        val profiles = loadProfiles(context)
        val remaining = profiles.filterNot { it.id == id }
        if (remaining.size == profiles.size) return profiles.firstOrNull()
        if (remaining.isEmpty()) {
            writeProfiles(context, emptyList(), "")
            return null
        }
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
            .putInt("dnsLabelLengthJitter", config.dnsLabelLengthJitter)
            .putInt("maxPollQps", config.maxPollQps)
            .putInt("maxDataQps", config.maxDataQps)
            .putInt("maxActiveClients", config.maxActiveClients)
            .putBoolean("base64uEncoding", config.base64uEncoding)
            .putString("protocol", config.protocol.name)
            .putString("s3Endpoint", config.s3Endpoint)
            .putString("s3Bucket", config.s3Bucket)
            .putString("s3AccessKey", config.s3AccessKey)
            .putString("s3SecretKey", config.s3SecretKey)
            .putString("s3Prefix", config.s3Prefix)
            .putString("s3Psk", config.s3Psk)
            .putString("xrayConfigJson", config.xrayConfigJson)
            .putString("cdnfuUrl", config.cdnfuUrl)
            .putString("cdnfuHost", config.cdnfuHost)
            .putString("cdnfuPsk", config.cdnfuPsk)
            .putString("cdnfuMimic", config.cdnfuMimic)
            .putString("cdnfuUplinkMethod", config.cdnfuUplinkMethod)
            .putString("cdnfuUplinkPath", config.cdnfuUplinkPath)
            .putString("cdnfuUplinkData", config.cdnfuUplinkData)
            .putString("cdnfuXhttpPlacement", config.cdnfuXhttpPlacement)
            .putString("cdnfuDownlinkMode", config.cdnfuDownlinkMode)
            .putInt("cdnfuMultipath", config.cdnfuMultipath)
            .putString("s3fuToml", config.s3fuToml)
            .putString("cdnfuToml", config.cdnfuToml)
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
            // Only for imported profiles, so exported links stay clean.
            .apply { profile.subscriptionId?.let { put("subscriptionId", it) } }
            .apply { profile.categoryId?.let { put("categoryId", it) } }

    private fun profileFromJson(json: JSONObject): ConfigProfile =
        ConfigProfile(
            id = json.optString("id").ifBlank { newProfileId() },
            name = json.optString("name").ifBlank { t(S.PROFILE_NAME_DEFAULT_IMPORTED) },
            config = configFromJson(json.optJSONObject("config") ?: JSONObject()),
            subscriptionId = json.optString("subscriptionId").ifBlank { null },
            categoryId = json.optString("categoryId").ifBlank { null }
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
            .put("dnsLabelLengthJitter", config.dnsLabelLengthJitter)
            .put("maxPollQps", config.maxPollQps)
            .put("maxDataQps", config.maxDataQps)
            .put("maxActiveClients", config.maxActiveClients)
            .put("base64uEncoding", config.base64uEncoding)
            .put("protocol", config.protocol.name)
            .put("s3Endpoint", config.s3Endpoint)
            .put("s3Bucket", config.s3Bucket)
            .put("s3AccessKey", config.s3AccessKey)
            .put("s3SecretKey", config.s3SecretKey)
            .put("s3Prefix", config.s3Prefix)
            .put("s3Psk", config.s3Psk)
            .put("xrayConfigJson", config.xrayConfigJson)
            .put("cdnfuUrl", config.cdnfuUrl)
            .put("cdnfuHost", config.cdnfuHost)
            .put("cdnfuPsk", config.cdnfuPsk)
            .put("cdnfuMimic", config.cdnfuMimic)
            .put("cdnfuUplinkMethod", config.cdnfuUplinkMethod)
            .put("cdnfuUplinkPath", config.cdnfuUplinkPath)
            .put("cdnfuUplinkData", config.cdnfuUplinkData)
            .put("cdnfuXhttpPlacement", config.cdnfuXhttpPlacement)
            .put("cdnfuDownlinkMode", config.cdnfuDownlinkMode)
            .put("cdnfuMultipath", config.cdnfuMultipath)
            // The whole-config TOML: a profile's own text, or the one the panel put in the
            // link. Missing here meant every profile was stored without it, so an import
            // parsed the config correctly and then threw it away on write.
            .put("s3fuToml", config.s3fuToml)
            .put("cdnfuToml", config.cdnfuToml)

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
            dnsLabelLengthJitter = json.optInt("dnsLabelLengthJitter", 4),
            maxPollQps = json.optInt("maxPollQps", 1400),
            maxDataQps = json.optInt("maxDataQps", 800),
            maxActiveClients = json.optInt("maxActiveClients", 40),
            base64uEncoding = json.optBoolean("base64uEncoding", false),
            protocol = enumValue(json.optString("protocol"), Config.TunnelProtocol.SLIPSTREAM),
            s3Endpoint = json.optString("s3Endpoint", ""),
            s3Bucket = json.optString("s3Bucket", ""),
            s3AccessKey = json.optString("s3AccessKey", ""),
            s3SecretKey = json.optString("s3SecretKey", ""),
            s3Prefix = json.optString("s3Prefix", "").ifBlank { "s3fu" },
            s3Psk = json.optString("s3Psk", ""),
            xrayConfigJson = json.optString("xrayConfigJson", ""),
            cdnfuUrl = json.optString("cdnfuUrl", ""),
            cdnfuHost = json.optString("cdnfuHost", ""),
            cdnfuPsk = json.optString("cdnfuPsk", ""),
            cdnfuMimic = json.optString("cdnfuMimic", "").ifBlank { "mixed" },
            cdnfuUplinkMethod = json.optString("cdnfuUplinkMethod", "").ifBlank { "POST" },
            cdnfuUplinkPath = json.optString("cdnfuUplinkPath", "").ifBlank { "api" },
            cdnfuUplinkData = json.optString("cdnfuUplinkData", "").ifBlank { "body" },
            cdnfuXhttpPlacement = json.optString("cdnfuXhttpPlacement", "").ifBlank { "cookie" },
            cdnfuDownlinkMode = json.optString("cdnfuDownlinkMode", "").ifBlank { "stream" },
            cdnfuMultipath = json.optInt("cdnfuMultipath", 4).coerceIn(0, 32),
            s3fuToml = json.optString("s3fuToml", ""),
            cdnfuToml = json.optString("cdnfuToml", ""),
        )

    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
        runCatching { enumValueOf<T>(value.orEmpty()) }.getOrDefault(fallback)

    private fun newProfileId(): String = System.currentTimeMillis().toString(36)

    private fun defaultProfileName(config: Config): String = when (config.protocol) {
        Config.TunnelProtocol.S3FU ->
            config.s3Bucket.ifBlank { t(S.PROFILE_NAME_DEFAULT_S3FU) }
        Config.TunnelProtocol.CDNFU ->
            runCatching {
                java.net.URI(
                    if (config.cdnfuUrl.contains("://")) config.cdnfuUrl else "https://${config.cdnfuUrl}"
                ).host
            }.getOrNull()?.takeIf { !it.isNullOrBlank() }
                ?: config.cdnfuUrl.ifBlank { t(S.PROFILE_NAME_DEFAULT_CDNFU) }
        Config.TunnelProtocol.XRAY ->
            XrayConfigBuilder.describeServer(config.xrayConfigJson) ?: t(S.PROFILE_NAME_DEFAULT_XRAY)
        else ->
            config.domain.ifBlank { t(S.PROFILE_NAME_DEFAULT) }
    }

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

/** Legacy slipstream query-param deep links (bot-generated, hand-written). */
private object SlipstreamLinkParser {
    fun parseFromParams(params: Map<String, String>, uri: Uri, base: Config): ImportedProfile? {
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
        val dnsLabelLengthJitter = first(params, "dnslabellengthjitter")?.toIntOrNull()?.coerceIn(0, 56) ?: base.dnsLabelLengthJitter
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
            dnsLabelLengthJitter = dnsLabelLengthJitter,
            maxPollQps = maxPollQps,
            maxDataQps = maxDataQps,
            maxActiveClients = maxActiveClients,
            dnsQueryType = dnsQueryType,
            base64uEncoding = base64uEncoding,
            protocol = Config.TunnelProtocol.SLIPSTREAM
        )
        return ImportedProfile(first(params, "name", "profilename") ?: domain, config)
    }

    private fun first(params: Map<String, String>, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { params[it.lowercase()]?.takeIf { value -> value.isNotBlank() } }
}
