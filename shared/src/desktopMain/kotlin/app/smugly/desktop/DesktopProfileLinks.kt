package app.smugly.desktop

import app.smugly.Config
import app.smugly.ConfigJson
import app.smugly.ConfigProfile
import app.smugly.defaultConfig
import java.net.URLDecoder
import java.util.Base64
import org.json.JSONObject

/**
 * Desktop import of the panel's own `scheme://import?…` links.
 *
 * Android already understands both the exported `config=<base64 JSON>` form and the
 * query-param form the bot actually mints (`s3fu://…?endpoint=&psk=`, `cdnfu://…?url=&psk=`,
 * `slipstream://…?domain=`). Desktop used to accept only the blob, so a category that held
 * nothing but those links imported as empty and was then hidden.
 */
object DesktopProfileLinks {

    fun parse(uri: String, preferredName: String = "", base: Config = defaultConfig()): ConfigProfile? {
        val trimmed = uri.trim()
        val scheme = trimmed.substringBefore("://", "").lowercase()
        if (scheme !in setOf("slipstream", "s3fu", "cdnfu", "xray")) return null
        val params = queryParams(trimmed)
        if (params.isEmpty() && !trimmed.contains('?')) return null

        parseConfigBlob(params, scheme, preferredName, base)?.let { return it }

        val imported = when (scheme) {
            "s3fu" -> parseS3fu(params, base)
            "cdnfu" -> parseCdnfu(params, base)
            "slipstream" -> parseSlipstream(params, trimmed, base)
            else -> null
        } ?: return null
        val name = preferredName.ifBlank { imported.first }
        return ConfigProfile(id = "", name = name, config = imported.second)
    }

    private fun parseConfigBlob(
        params: Map<String, String>,
        scheme: String,
        preferredName: String,
        base: Config
    ): ConfigProfile? {
        val raw = params["config"] ?: params["profile"] ?: params["data"] ?: return null
        val decoded = decodePayload(raw) ?: return null
        if (!decoded.trimStart().startsWith("{")) return null
        return runCatching {
            val json = JSONObject(decoded)
            val configJson = json.optJSONObject("config")
            val config = when {
                configJson != null -> ConfigJson.configFromJson(configJson)
                json.has("domain") || json.has("resolverHost") ||
                    json.has("s3Endpoint") || json.has("protocol") ||
                    json.has("s3Bucket") || json.has("s3Psk") ||
                    json.has("cdnfuUrl") || json.has("cdnfuPsk") ||
                    json.has("xrayConfigJson") -> ConfigJson.configFromJson(json)
                else -> null
            } ?: return@runCatching null
            val forced = when (scheme) {
                "s3fu" -> config.copy(protocol = Config.TunnelProtocol.S3FU)
                "cdnfu" -> config.copy(protocol = Config.TunnelProtocol.CDNFU)
                "xray" -> config.copy(protocol = Config.TunnelProtocol.XRAY)
                else -> config.copy(protocol = Config.TunnelProtocol.SLIPSTREAM)
            }
            val name = sequenceOf(
                preferredName.takeIf { it.isNotBlank() },
                json.optString("name").takeIf { it.isNotBlank() },
                params["name"],
                params["profilename"]
            ).firstOrNull { !it.isNullOrBlank() }.orEmpty().ifBlank { fallbackName(forced) }
            ConfigProfile(id = "", name = name, config = forced)
        }.getOrNull()
    }

    private fun parseS3fu(params: Map<String, String>, base: Config): Pair<String, Config>? {
        fun first(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { params[it]?.takeIf { v -> v.isNotBlank() } }
        val endpoint = first("endpoint", "s3endpoint", "s3_endpoint")
        val bucket = first("bucket", "s3bucket", "s3_bucket")
        val psk = first("psk", "s3psk", "s3_psk")
        if (endpoint.isNullOrBlank() && bucket.isNullOrBlank() && psk.isNullOrBlank()) return null
        val config = base.copy(
            protocol = Config.TunnelProtocol.S3FU,
            s3Endpoint = endpoint ?: base.s3Endpoint,
            s3Bucket = bucket ?: base.s3Bucket,
            s3AccessKey = first("accesskey", "s3accesskey", "access_key", "s3_access_key") ?: base.s3AccessKey,
            s3SecretKey = first("secretkey", "s3secretkey", "secret_key", "s3_secret_key") ?: base.s3SecretKey,
            s3Prefix = (first("prefix", "s3prefix", "s3_prefix") ?: base.s3Prefix).ifBlank { "s3fu" },
            s3Psk = psk ?: base.s3Psk,
            s3fuToml = decodeToml(params["toml"])
        )
        val name = first("name", "profilename")
            ?: first("login", "user")
            ?: config.s3Bucket.takeIf { it.isNotBlank() }
            ?: fallbackName(config)
        return name to config
    }

    private fun parseCdnfu(params: Map<String, String>, base: Config): Pair<String, Config>? {
        fun first(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { params[it]?.takeIf { v -> v.isNotBlank() } }
        val url = first("url", "cdnfuurl", "cdn_url", "endpoint", "host")
        val psk = first("psk", "cdnfupsk", "cdn_psk", "password", "pass")
        if (url.isNullOrBlank() && psk.isNullOrBlank()) return null
        val config = base.copy(
            protocol = Config.TunnelProtocol.CDNFU,
            cdnfuUrl = url ?: base.cdnfuUrl,
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
            cdnfuToml = decodeToml(params["toml"])
        )
        val name = first("name", "profilename")
            ?: runCatching {
                java.net.URI(
                    if (config.cdnfuUrl.contains("://")) config.cdnfuUrl else "https://${config.cdnfuUrl}"
                ).host
            }.getOrNull()?.takeIf { !it.isNullOrBlank() }
            ?: fallbackName(config)
        return name to config
    }

    private fun parseSlipstream(
        params: Map<String, String>,
        uri: String,
        base: Config
    ): Pair<String, Config>? {
        fun first(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { params[it]?.takeIf { v -> v.isNotBlank() } }
        val authority = uri.substringAfter("://", "").substringBefore("?", "").substringBefore("/")
        val host = authority.takeIf { it.isNotBlank() && it != "import" && it != "profile" }
        val domain = first("domain", "server", "sni", "host") ?: host ?: return null
        val resolverMode = when (first("resolvermode", "dnsmode")?.lowercase()) {
            "auto" -> Config.ResolverMode.AUTO
            "manual" -> Config.ResolverMode.MANUAL
            else -> base.resolverMode
        }
        val transport = when (first("transport", "resolvertransport")?.lowercase()) {
            "udp" -> Config.ResolverTransport.UDP
            "tcp" -> Config.ResolverTransport.TCP
            else -> base.resolverTransport
        }
        val pathMode = when (first("resolverpathmode", "pathmode", "authoritative")?.lowercase()) {
            "recursive", "resolver", "false", "off", "0" -> Config.ResolverPathMode.RECURSIVE
            "authoritative", "auth", "true", "on", "1" -> Config.ResolverPathMode.AUTHORITATIVE
            else -> base.resolverPathMode
        }
        val username = first("username", "user")
        val password = first("password", "pass")
        val authMode = if (!username.isNullOrBlank() || !password.isNullOrBlank()) {
            Config.AuthMode.LOGIN_PASSWORD
        } else {
            base.authMode
        }
        val config = base.copy(
            domain = domain,
            resolverHost = first("resolver", "resolverhost", "dns", "dnsserver") ?: base.resolverHost,
            resolverPort = first("resolverport", "dnsport", "port")?.toIntOrNull() ?: base.resolverPort,
            resolverMode = resolverMode,
            resolverTransport = transport,
            resolverPathMode = pathMode,
            authMode = authMode,
            username = username ?: base.username,
            password = password ?: base.password,
            protocol = Config.TunnelProtocol.SLIPSTREAM
        )
        return (first("name", "profilename") ?: domain) to config
    }

    private fun queryParams(uri: String): Map<String, String> {
        val q = uri.substringAfter('?', "")
        if (q.isEmpty()) return emptyMap()
        val out = linkedMapOf<String, String>()
        for (part in q.split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val key = urlDecode(part.substring(0, eq)).lowercase()
            val value = urlDecode(part.substring(eq + 1))
            if (key.isNotEmpty() && key !in out) out[key] = value
        }
        return out
    }

    private fun urlDecode(raw: String): String =
        runCatching { URLDecoder.decode(raw, Charsets.UTF_8.name()) }.getOrDefault(raw)

    private fun decodeToml(value: String?): String {
        val raw = value?.takeIf { it.isNotBlank() } ?: return ""
        val looksLikeConfig = raw.contains('=') && (raw.contains('\n') || raw.contains('"'))
        if (looksLikeConfig) return raw
        return decodePayload(raw) ?: raw
    }

    private fun decodePayload(value: String): String? = runCatching {
        val padded = value + "=".repeat((4 - value.length % 4) % 4)
        String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
    }.getOrElse {
        runCatching {
            val padded = value + "=".repeat((4 - value.length % 4) % 4)
            String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun fallbackName(c: Config): String = when (c.protocol) {
        Config.TunnelProtocol.S3FU -> c.s3Bucket.ifBlank { "S3" }
        Config.TunnelProtocol.CDNFU -> c.cdnfuHost.ifBlank { "CDN" }
        Config.TunnelProtocol.XRAY -> "Xray"
        Config.TunnelProtocol.SLIPSTREAM -> c.domain.ifBlank { "Slipstream" }
    }
}
