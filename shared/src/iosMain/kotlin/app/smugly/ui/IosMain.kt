package app.smugly.ui

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * Entry for an iOS host (SwiftUI/UIKit) embedding the shared Compose UI.
 * Wire from Xcode: `MainViewController()`.
 *
 * Full Packet Tunnel Provider is not included — this ships the same profile UI.
 */
fun MainViewController(): UIViewController =
    ComposeUIViewController {
        SmuglyApp(IosPlatform())
    }

/**
 * Minimal iOS platform bridge (in-memory store). Replace with App Group UserDefaults
 * when packaging a real app.
 */
class IosPlatform : SmuglyPlatform {
    private val memory = mutableListOf(
        app.smugly.ConfigProfile(
            id = "1",
            name = "Default",
            config = app.smugly.defaultConfig(mode = app.smugly.Config.Mode.VPN)
        )
    )
    private var activeId: String? = "1"
    private var settings = defaultGlobalSettings(mode = app.smugly.Config.Mode.VPN)
    private var connect = ConnectUiState.idle()
    private val listeners = mutableListOf<(ConnectUiState) -> Unit>()

    override fun loadProfiles() = memory.toList()
    override fun loadActiveProfileId() = activeId
    override fun setActiveProfile(id: String) {
        activeId = id
    }

    override fun saveProfile(profile: app.smugly.ConfigProfile): app.smugly.ConfigProfile {
        val i = memory.indexOfFirst { it.id == profile.id }
        if (i >= 0) memory[i] = profile else memory.add(profile)
        return profile
    }

    override fun addProfile(name: String, config: app.smugly.Config): app.smugly.ConfigProfile {
        val p = app.smugly.ConfigProfile(
            id = memory.size.toString(),
            name = name,
            config = config
        )
        memory.add(p)
        activeId = p.id
        return p
    }

    override fun deleteProfile(id: String): app.smugly.ConfigProfile {
        if (memory.size <= 1) return memory.first()
        memory.removeAll { it.id == id }
        if (activeId == id) activeId = memory.first().id
        return memory.first()
    }

    override fun reorderProfiles(orderedIds: List<String>) {
        val byId = memory.associateBy { it.id }
        memory.clear()
        orderedIds.mapNotNullTo(memory) { byId[it] }
    }

    override fun loadGlobalSettings() = settings
    override fun saveGlobalSettings(settings: app.smugly.GlobalSettings) {
        this.settings = settings
    }

    override fun toggleConnect() {
        connect = connect.copy(
            running = !connect.running,
            statusText = if (!connect.running) app.smugly.t(app.smugly.S.STATUS_CONNECTED) else app.smugly.t(app.smugly.S.STATUS_NOT_CONNECTED)
        )
        listeners.forEach { it(connect) }
    }

    override fun observeConnect(onChange: (ConnectUiState) -> Unit): () -> Unit {
        listeners += onChange
        onChange(connect)
        return { listeners.remove(onChange) }
    }

    override fun readClipboard(): String = ""
    override fun writeClipboard(text: String) {}
    override fun importFromText(text: String) = emptyList<app.smugly.ConfigProfile>()
    override fun exportProfileLink(profile: app.smugly.ConfigProfile) = profile.name
    override fun shareLog() {}
    override fun showCrashReport() {}
    override fun readCrashReportText(): String = ""
    override fun readDebugLog(): String = "iOS: debug log not wired yet."
    override fun pickImportFile(onResult: (String?) -> Unit) = onResult(null)
    override fun supportsSystemVpn() = true
    override fun toast(message: String) {}
    override fun validateXrayConfig(json: String): String? = null
    override fun formatXrayJson(json: String): String? = json
    override fun localDnsResolver(): String? = null
}
