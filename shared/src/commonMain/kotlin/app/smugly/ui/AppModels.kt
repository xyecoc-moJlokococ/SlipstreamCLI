package app.smugly.ui

import app.smugly.AppLanguage
import app.smugly.Config
import app.smugly.ConfigProfile
import app.smugly.DnsResolverPool
import app.smugly.GlobalSettings
import app.smugly.S
import app.smugly.defaultConfig
import app.smugly.t

/**
 * Main tabs reachable from the drawer. The profile editor is not a tab — it is a
 * layer drawn over the current tab (see `SmuglyApp`).
 */
enum class AppScreen {
    HOME,
    SETTINGS,
    DIAGNOSTICS
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
 * Actions the host window can trigger from outside the Compose tree — desktop keyboard shortcuts,
 * a future menu bar or tray menu.
 *
 * The UI fills these in while it is composed. Going through a holder rather than a key modifier
 * inside `SmuglyApp` is deliberate: window-level `onKeyEvent` fires only after the focused
 * component declined the key, so Ctrl+V still pastes text normally while a text field has focus.
 */
class AppShortcuts {
    var importFromClipboard: (() -> Unit)? = null
}

/**
 * Platform hooks the shared Compose UI calls for tunnel control, import/export, clipboard.
 * Android wires VpnService; desktop/iOS can implement proxy-only / no-ops.
 */
interface SmuglyPlatform {
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
    /** Null once the last profile is gone — an empty list is allowed. */
    fun deleteProfile(id: String): ConfigProfile?
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

    // ---- subscriptions (folders) ----
    // Defaults keep platforms that have not wired storage yet (iOS) compiling and inert.

    fun loadSubscriptions(): List<app.smugly.subscription.Subscription> = emptyList()

    /**
     * Import a subscription from anything the user pasted or opened: a plain URL, an
     * `install-sub` deep link, or `sub://…`. Runs the network, so call it off the UI thread.
     * Returns an error message, or null on success.
     */
    fun addSubscription(rawUrl: String): String? = "subscriptions are not supported on this platform"

    /** Re-fetch one subscription. Returns an error message, or null on success. */
    fun refreshSubscription(id: String): String? = "subscriptions are not supported on this platform"

    /**
     * Refresh every subscription whose interval has elapsed. Networked — call off the UI thread.
     * Returns how many were actually refreshed.
     */
    fun refreshDueSubscriptions(): Int = 0

    fun deleteSubscription(id: String) {}
    fun renameSubscription(id: String, name: String) {}

    /**
     * Create ([id] null) or update a folder from the editor. Networked when the URL is new or
     * changed — call off the UI thread. Returns an error message, or null on success.
     */
    fun saveSubscription(
        id: String?,
        name: String,
        url: String,
        enabled: Boolean,
        updateIntervalMinutes: Long,
        allowReorder: Boolean,
        showInfo: Boolean
    ): String? = "subscriptions are not supported on this platform"

    /** Persist a new folder-tab order (subscription ids only; Home's slot is a global setting). */
    fun reorderSubscriptions(orderedIds: List<String>) {}

    /** True when [text] should be treated as a subscription rather than a single config. */
    fun looksLikeSubscription(text: String): Boolean = false
}

fun emptyDraft(base: Config = defaultConfig(mode = Config.Mode.PROXY)): EditorDraft =
    EditorDraft(
        profileId = null,
        name = t(S.CD_NEW_PROFILE),
        config = base
    )

/**
 * The grey line under a profile's name: which tunnel it runs on.
 *
 * Deliberately not the domain / login any more — those are per-profile trivia, while the thing
 * worth scanning a list of profiles for is how each one connects. Xray says "Xray-core" rather
 * than plain "Xray" because that is the component actually carrying the traffic.
 */
fun profileSubtitle(profile: ConfigProfile): String = when (profile.config.protocol) {
    Config.TunnelProtocol.S3FU -> t(S.PROTOCOL_S3FU)
    Config.TunnelProtocol.XRAY -> t(S.PROTOCOL_XRAY_CORE)
    Config.TunnelProtocol.SLIPSTREAM -> t(S.PROTOCOL_SLIPSTREAM)
    Config.TunnelProtocol.CDNFU -> t(S.PROTOCOL_CDNFU)
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
