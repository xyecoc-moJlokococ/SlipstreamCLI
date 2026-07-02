package app.vaydns

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Config(
    val domain: String,
    val resolverHost: String,
    val resolverPort: Int,
    val resolverMode: ResolverMode,
    val resolverTransport: ResolverTransport,
    val listenPort: Int,
    val mode: Mode,
    val authMode: AuthMode,
    val username: String,
    val password: String
) {
    enum class Mode { PROXY, VPN }
    enum class AuthMode { NO_AUTH, LOGIN_PASSWORD }
    enum class ResolverMode { MANUAL, AUTO }
    enum class ResolverTransport { UDP, TCP }
}

data class ConfigProfile(
    val id: String,
    val name: String,
    val config: Config
)

object ConfigStore {
    private const val PREFS = "config"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_ACTIVE_PROFILE_ID = "activeProfileId"

    fun load(context: Context): Config {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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
            listenPort = p.getInt("listenPort", 1080),
            mode = Config.Mode.valueOf(p.getString("mode", Config.Mode.PROXY.name) ?: Config.Mode.PROXY.name),
            authMode = Config.AuthMode.valueOf(p.getString("authMode", Config.AuthMode.NO_AUTH.name) ?: Config.AuthMode.NO_AUTH.name),
            username = p.getString("username", "") ?: "",
            password = p.getString("password", "") ?: ""
        )
    }

    fun save(context: Context, config: Config) {
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
            .putInt("listenPort", config.listenPort)
            .putString("mode", config.mode.name)
            .putString("authMode", config.authMode.name)
            .putString("username", config.username)
            .putString("password", config.password)
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
            name = json.optString("name").ifBlank { "Profile" },
            config = configFromJson(json.optJSONObject("config") ?: JSONObject())
        )

    private fun configToJson(config: Config): JSONObject =
        JSONObject()
            .put("domain", config.domain.trim())
            .put("resolverHost", config.resolverHost.trim())
            .put("resolverPort", config.resolverPort)
            .put("resolverMode", config.resolverMode.name)
            .put("resolverTransport", config.resolverTransport.name)
            .put("listenPort", config.listenPort)
            .put("mode", config.mode.name)
            .put("authMode", config.authMode.name)
            .put("username", config.username)
            .put("password", config.password)

    private fun configFromJson(json: JSONObject): Config =
        Config(
            domain = json.optString("domain", ""),
            resolverHost = json.optString("resolverHost", ""),
            resolverPort = json.optInt("resolverPort", 53),
            resolverMode = enumValue(json.optString("resolverMode"), Config.ResolverMode.MANUAL),
            resolverTransport = enumValue(json.optString("resolverTransport"), Config.ResolverTransport.TCP),
            listenPort = json.optInt("listenPort", 1080),
            mode = enumValue(json.optString("mode"), Config.Mode.PROXY),
            authMode = enumValue(json.optString("authMode"), Config.AuthMode.NO_AUTH),
            username = json.optString("username", ""),
            password = json.optString("password", "")
        )

    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
        runCatching { enumValueOf<T>(value.orEmpty()) }.getOrDefault(fallback)

    private fun newProfileId(): String = System.currentTimeMillis().toString(36)

    private fun defaultProfileName(config: Config): String =
        config.domain.ifBlank { "Slipstream profile" }

    private fun uniqueName(base: String, existing: Set<String>): String {
        if (base !in existing) return base
        var index = 2
        while ("$base $index" in existing) index++
        return "$base $index"
    }
}
