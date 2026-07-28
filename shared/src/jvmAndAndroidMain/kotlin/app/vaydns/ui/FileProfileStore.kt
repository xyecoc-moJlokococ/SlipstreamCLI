package app.vaydns.ui

import app.vaydns.Config
import app.vaydns.ConfigJson
import app.vaydns.ConfigProfile
import app.vaydns.DnsResolverPool
import app.vaydns.GlobalSettings
import app.vaydns.defaultConfig
import app.vaydns.platform.AppPaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * File-backed profiles for desktop (and any JVM host without SharedPreferences).
 * Format matches Android export JSON so profiles can move between platforms.
 */
class FileProfileStore(
    private val dir: File = File(AppPaths.filesDir())
) {
    private val profilesFile get() = File(dir, "profiles.json")
    private val settingsFile get() = File(dir, "settings.json")
    private val activeFile get() = File(dir, "active_profile_id.txt")
    private val subscriptionsFile get() = File(dir, "subscriptions.json")

    fun loadSubscriptions(): List<app.vaydns.subscription.Subscription> =
        runCatching {
            subscriptionsFile.takeIf { it.exists() }?.readText().orEmpty()
        }.getOrDefault("").let { app.vaydns.subscription.SubscriptionJson.listFromString(it) }

    fun saveSubscriptions(subs: List<app.vaydns.subscription.Subscription>) {
        runCatching {
            subscriptionsFile.writeText(app.vaydns.subscription.SubscriptionJson.listToString(subs))
        }
    }

    init {
        dir.mkdirs()
        if (!profilesFile.exists()) {
            val starter = ConfigProfile(
                id = UUID.randomUUID().toString(),
                name = "Default",
                config = defaultConfig(mode = Config.Mode.PROXY)
            )
            writeProfiles(listOf(starter))
            activeFile.writeText(starter.id)
        }
        if (!settingsFile.exists()) {
            saveGlobalSettings(defaultGlobalSettings())
        }
    }

    fun loadProfiles(): List<ConfigProfile> {
        val arr = runCatching { JSONArray(profilesFile.readText()) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                add(ConfigJson.profileFromJson(arr.getJSONObject(i)))
            }
        }
    }

    fun loadActiveProfileId(): String? =
        activeFile.takeIf { it.exists() }?.readText()?.trim()?.ifBlank { null }

    fun setActiveProfile(id: String) {
        activeFile.writeText(id)
    }

    fun writeProfiles(profiles: List<ConfigProfile>) {
        val arr = JSONArray()
        profiles.forEach { arr.put(ConfigJson.profileToJson(it)) }
        profilesFile.writeText(arr.toString(2))
    }

    fun saveProfile(profile: ConfigProfile): ConfigProfile {
        val list = loadProfiles().toMutableList()
        val idx = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) list[idx] = profile else list.add(profile)
        writeProfiles(list)
        return profile
    }

    fun addProfile(name: String, config: Config): ConfigProfile {
        val profile = ConfigProfile(UUID.randomUUID().toString(), name.ifBlank { "Profile" }, config)
        writeProfiles(loadProfiles() + profile)
        setActiveProfile(profile.id)
        return profile
    }

    /** Returns the profile that is active afterwards, or null once nothing is left. */
    fun deleteProfile(id: String): ConfigProfile? {
        val list = loadProfiles()
        val remaining = list.filterNot { it.id == id }
        writeProfiles(remaining)
        val first = remaining.firstOrNull()
        if (first == null) {
            setActiveProfile("")
            return null
        }
        val active = loadActiveProfileId()
        if (active == id || remaining.none { it.id == active }) {
            setActiveProfile(first.id)
        }
        return first
    }

    fun reorderProfiles(orderedIds: List<String>) {
        val byId = loadProfiles().associateBy { it.id }
        val reordered = orderedIds.mapNotNull { byId[it] }
        val rest = loadProfiles().filter { it.id !in orderedIds.toSet() }
        writeProfiles(reordered + rest)
    }

    fun loadGlobalSettings(): GlobalSettings {
        val json = runCatching { JSONObject(settingsFile.readText()) }.getOrNull()
            ?: return defaultGlobalSettings()
        return GlobalSettings(
            listenPort = json.optInt("listenPort", 1080),
            mode = runCatching {
                Config.Mode.valueOf(json.optString("mode", Config.Mode.PROXY.name))
            }.getOrDefault(Config.Mode.PROXY),
            fileLogging = json.optBoolean("fileLogging", false),
            trafficNotification = json.optBoolean("trafficNotification", false),
            localSocksAuthEnabled = json.optBoolean("localSocksAuthEnabled", true),
            localSocksUsername = json.optString("localSocksUsername", "slipstream"),
            localSocksPassword = json.optString("localSocksPassword", "changeme"),
            language = runCatching {
                app.vaydns.AppLanguage.valueOf(json.optString("language", "SYSTEM"))
            }.getOrDefault(app.vaydns.AppLanguage.SYSTEM),
            dnsResolverPool = DnsResolverPool.normalize(
                json.optString("dnsResolverPool", DnsResolverPool.DEFAULT_RAW)
            )
        )
    }

    fun saveGlobalSettings(settings: GlobalSettings) {
        val json = JSONObject()
            .put("listenPort", settings.listenPort)
            .put("mode", settings.mode.name)
            .put("fileLogging", settings.fileLogging)
            .put("trafficNotification", settings.trafficNotification)
            .put("localSocksAuthEnabled", settings.localSocksAuthEnabled)
            .put("localSocksUsername", settings.localSocksUsername)
            .put("localSocksPassword", settings.localSocksPassword)
            .put("language", settings.language.name)
            .put("dnsResolverPool", settings.dnsResolverPool)
        settingsFile.writeText(json.toString(2))
    }
}
