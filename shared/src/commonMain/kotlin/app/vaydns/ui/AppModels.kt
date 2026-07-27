package app.vaydns.ui

import app.vaydns.AppLanguage
import app.vaydns.Config
import app.vaydns.ConfigProfile
import app.vaydns.DnsResolverPool
import app.vaydns.GlobalSettings
import app.vaydns.S
import app.vaydns.defaultConfig
import app.vaydns.t

enum class AppScreen {
    HOME,
    SETTINGS,
    DIAGNOSTICS,
    PROFILE_EDITOR
}

data class ConnectUiState(
    val statusText: String = "",
    val trafficText: String = "↓ 0 B (0 B/s)   ↑ 0 B (0 B/s)",
    val running: Boolean = false,
    val connecting: Boolean = false,
    val diagnosticsText: String = ""
) {
    companion object {
        fun idle() = ConnectUiState(statusText = t(S.STATUS_NOT_CONNECTED))
    }
}

data class EditorDraft(
    val profileId: String?,
    val name: String,
    val config: Config
)

/**
 * Platform hooks the shared Compose UI calls for tunnel control, import/export, clipboard.
 * Android wires VpnService; desktop/iOS can implement proxy-only / no-ops.
 */
interface VaydnsPlatform {
    fun loadProfiles(): List<ConfigProfile>
    fun loadActiveProfileId(): String?
    fun setActiveProfile(id: String)
    /**
     * User tapped a profile card. Sets it active and, if the tunnel is up, reconnects
     * (same as original Android [selectProfile]).
     */
    fun selectProfile(id: String) {
        setActiveProfile(id)
    }
    fun saveProfile(profile: ConfigProfile): ConfigProfile
    fun addProfile(name: String, config: Config): ConfigProfile
    fun deleteProfile(id: String): ConfigProfile
    fun reorderProfiles(orderedIds: List<String>)
    fun loadGlobalSettings(): GlobalSettings
    fun saveGlobalSettings(settings: GlobalSettings)
    fun toggleConnect()
    fun observeConnect(onChange: (ConnectUiState) -> Unit): () -> Unit
    fun readClipboard(): String
    fun writeClipboard(text: String)
    fun importFromText(text: String): List<ConfigProfile>
    fun exportProfileLink(profile: ConfigProfile): String
    fun shareLog()
    fun showCrashReport()
    /**
     * Raw crash-report body for an in-app dialog (empty string if nothing saved yet).
     * Prefer this + UI dialog over [showCrashReport] on multiplatform Compose.
     */
    fun readCrashReportText(): String
    /** Full text of the on-disk debug log (or empty / hint when logging is off). */
    fun readDebugLog(): String
    fun pickImportFile(onResult: (String?) -> Unit)
    fun supportsSystemVpn(): Boolean
    fun toast(message: String)
    /**
     * Subscribe to toast messages for in-app snackbars (desktop).
     * Android may leave the default no-op and keep system Toast in [toast].
     */
    fun observeToast(onToast: (String) -> Unit): () -> Unit = { }
    fun validateXrayConfig(json: String): String?
    fun formatXrayJson(json: String): String?
    fun localDnsResolver(): String?
}

fun emptyDraft(base: Config = defaultConfig(mode = Config.Mode.PROXY)): EditorDraft =
    EditorDraft(
        profileId = null,
        name = t(S.CD_NEW_PROFILE),
        config = base
    )

fun profileSubtitle(profile: ConfigProfile): String = when (profile.config.protocol) {
    Config.TunnelProtocol.S3FU ->
        profile.config.s3Login.ifBlank { profile.config.s3Bucket }.ifBlank { "s3fu" }
    // Xray JSON is not a readable one-liner — show nothing under the name.
    Config.TunnelProtocol.XRAY -> ""
    else ->
        profile.config.domain.ifBlank { profile.config.resolverHost }.ifBlank { "—" }
}

fun maskDomain(value: String): String {
    if (value.length <= 18) return value
    return value.take(8) + "…" + value.takeLast(6)
}

fun defaultGlobalSettings(mode: Config.Mode = Config.Mode.PROXY): GlobalSettings =
    GlobalSettings(
        listenPort = 1080,
        mode = mode,
        fileLogging = false,
        trafficNotification = false,
        localSocksAuthEnabled = true,
        localSocksUsername = "slipstream",
        localSocksPassword = "changeme",
        language = AppLanguage.SYSTEM,
        dnsResolverPool = DnsResolverPool.DEFAULT_RAW
    )
