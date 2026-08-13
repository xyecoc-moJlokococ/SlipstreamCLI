package app.smugly

import org.json.JSONObject

/**
 * Pure JSON (de)serialization for [Config] / [ConfigProfile] shared by Android
 * (SharedPreferences blobs) and desktop (file store). Uses org.json so it matches
 * the existing on-disk format byte-for-byte.
 */
object ConfigJson {
    fun configToString(config: Config, indent: Int = 2): String =
        configToJson(config).toString(indent)

    fun configFromString(text: String): Config =
        configFromJson(JSONObject(text))

    fun configToJson(config: Config): JSONObject =
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
            // The whole client config as text. Kept alongside the legacy per-field values
            // rather than replacing them, so a profile written by this build still opens
            // in an older one (it falls back to the fields) instead of losing its server.
            .put("s3fuToml", config.s3fuToml)
            .put("cdnfuToml", config.cdnfuToml)

    fun configFromJson(json: JSONObject): Config =
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

    fun profileToJson(profile: ConfigProfile): JSONObject =
        JSONObject()
            .put("id", profile.id)
            .put("name", profile.name)
            .put("config", configToJson(profile.config))
            // Omitted for hand-made profiles so exported links stay clean and importable elsewhere.
            .apply { profile.subscriptionId?.let { put("subscriptionId", it) } }
            .apply { profile.categoryId?.let { put("categoryId", it) } }

    fun profileFromJson(json: JSONObject): ConfigProfile =
        ConfigProfile(
            id = json.optString("id").ifBlank { "imported" },
            name = json.optString("name").ifBlank { "Imported" },
            config = configFromJson(json.optJSONObject("config") ?: JSONObject()),
            subscriptionId = json.optString("subscriptionId").ifBlank { null },
            categoryId = json.optString("categoryId").ifBlank { null }
        )

    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
        runCatching { enumValueOf<T>(value.orEmpty()) }.getOrDefault(fallback)
}
