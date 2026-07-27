package app.vaydns.ui

import app.vaydns.Config
import app.vaydns.ConfigJson
import app.vaydns.ConfigProfile
import app.vaydns.GlobalSettings
import app.vaydns.S
import app.vaydns.currentHostPlatform
import app.vaydns.platform.PlatformLog
import app.vaydns.platform.LogLevel
import app.vaydns.supportsSystemVpn
import app.vaydns.t
import org.json.JSONObject
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * Desktop (Windows/Linux/macOS) implementation of [VaydnsPlatform].
 * Profile/settings UI is fully shared; tunnel connect is proxy-oriented (no system VPN).
 */
class DesktopPlatform(
    private val store: FileProfileStore = FileProfileStore()
) : VaydnsPlatform {
    private val listeners = CopyOnWriteArrayList<(ConnectUiState) -> Unit>()
    private val toastListeners = CopyOnWriteArrayList<(String) -> Unit>()
    @Volatile private var connectState = ConnectUiState.idle()

    override fun loadProfiles(): List<ConfigProfile> = store.loadProfiles()
    override fun loadActiveProfileId(): String? = store.loadActiveProfileId()
    override fun setActiveProfile(id: String) = store.setActiveProfile(id)

    override fun selectProfile(id: String) {
        if (id == store.loadActiveProfileId()) return
        val wasRunning = connectState.running
        store.setActiveProfile(id)
        if (wasRunning) {
            toast(t(S.TOAST_SWITCHING_PROFILE))
            // Dry-run reconnect: drop "connected" then re-assert for the new profile.
            connectState = ConnectUiState.idle()
            listeners.forEach { it(connectState) }
            connectState = ConnectUiState(
                statusText = t(S.STATUS_CONNECTED) + " (UI)",
                trafficText = "↓ 0 B (0 B/s)   ↑ 0 B (0 B/s)",
                running = true,
                diagnosticsText = "Desktop: switched active profile (UI dry-run)."
            )
            listeners.forEach { it(connectState) }
        }
    }

    override fun saveProfile(profile: ConfigProfile): ConfigProfile = store.saveProfile(profile)
    override fun addProfile(name: String, config: Config): ConfigProfile = store.addProfile(name, config)
    override fun deleteProfile(id: String): ConfigProfile = store.deleteProfile(id)
    override fun reorderProfiles(orderedIds: List<String>) = store.reorderProfiles(orderedIds)
    override fun loadGlobalSettings(): GlobalSettings = store.loadGlobalSettings()
    override fun saveGlobalSettings(settings: GlobalSettings) {
        store.saveGlobalSettings(settings)
        // When debug mode is on, ensure a log file exists so Diagnostics has something to show.
        if (settings.fileLogging) {
            val f = File(app.vaydns.platform.AppPaths.filesDir(), "vaydns-debug.log")
            if (!f.exists()) {
                f.writeText(
                    "desktop log started\n" +
                        "path=${f.absolutePath}\n" +
                        "platform=${currentHostPlatform()}\n"
                )
            }
        }
    }

    override fun toggleConnect() {
        // Full native tunnel not wired on desktop yet — surface clear status in the same UI.
        connectState = if (connectState.running) {
            ConnectUiState(
                statusText = t(S.STATUS_NOT_CONNECTED),
                trafficText = "↓ 0 B (0 B/s)   ↑ 0 B (0 B/s)",
                running = false,
                diagnosticsText = "Desktop: tunnel engine not started (SOCKS/native host build pending)."
            )
        } else {
            ConnectUiState(
                statusText = t(S.STATUS_CONNECTED) + " (UI)",
                trafficText = "↓ 0 B (0 B/s)   ↑ 0 B (0 B/s)",
                running = true,
                diagnosticsText = "Desktop connect is a UI dry-run. Shared UI + profile store are active.\n" +
                    "Native slipstream/xray host libs can be attached next."
            )
        }
        listeners.forEach { it(connectState) }
    }

    override fun observeConnect(onChange: (ConnectUiState) -> Unit): () -> Unit {
        listeners += onChange
        onChange(connectState)
        return { listeners -= onChange }
    }

    override fun readClipboard(): String = try {
        val clip = Toolkit.getDefaultToolkit().systemClipboard
        clip.getData(DataFlavor.stringFlavor) as? String ?: ""
    } catch (_: Exception) {
        ""
    }

    override fun writeClipboard(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    override fun importFromText(text: String): List<ConfigProfile> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        // Minimal JSON profile import for desktop (Android still has full ConfigStore import).
        if (trimmed.startsWith("{")) {
            return runCatching {
                val json = JSONObject(trimmed)
                val profile = when {
                    json.has("config") -> ConfigJson.profileFromJson(json)
                    else -> ConfigProfile(
                        id = System.currentTimeMillis().toString(36),
                        name = json.optString("name").ifBlank { "Imported" },
                        config = ConfigJson.configFromJson(json)
                    )
                }
                listOf(addProfile(profile.name, profile.config))
            }.getOrDefault(emptyList())
        }
        return emptyList()
    }

    override fun exportProfileLink(profile: ConfigProfile): String {
        val payload = ConfigJson.profileToJson(profile).toString()
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
        val scheme = when (profile.config.protocol) {
            Config.TunnelProtocol.S3FU -> "s3fu"
            Config.TunnelProtocol.XRAY -> "xray"
            else -> "slipstream"
        }
        return "$scheme://import?config=$b64"
    }

    override fun shareLog() {
        val f = File(app.vaydns.platform.AppPaths.filesDir(), "vaydns-debug.log")
        if (!f.exists() || f.length() == 0L) {
            toast(t(S.TOAST_LOG_EMPTY))
            return
        }
        val text = runCatching { f.readText() }.getOrDefault("")
        if (text.isBlank()) {
            toast(t(S.TOAST_LOG_EMPTY))
            return
        }
        // Desktop has no share-sheet: copy full log + optional Save As.
        writeClipboard(text)
        toast(t(S.TOAST_LOG_COPIED))
        SwingUtilities.invokeLater {
            showSystemFileChooser(
                save = true,
                title = t(S.SHARE_LOG_CHOOSER),
                suggestedName = "vaydns-debug.log"
            )?.let { dest ->
                runCatching { dest.writeText(text) }
            }
        }
    }

    override fun showCrashReport() {
        // Compose UI shows a dialog via [readCrashReportText]; keep a clipboard fallback.
        val text = readCrashReportText()
        if (text.isBlank()) {
            toast(t(S.NO_CRASH_REPORT))
            return
        }
        writeClipboard(text)
        toast(t(S.TOAST_CRASH_REPORT_COPIED))
    }

    override fun readCrashReportText(): String {
        val dir = File(app.vaydns.platform.AppPaths.filesDir())
        val candidates = listOf(
            File(dir, "desktop-crash.log"),
            File(dir, "vaydns-crash.log"),
            File(dir, "crash.log")
        )
        val f = candidates.firstOrNull { it.exists() && it.length() > 0 } ?: return ""
        val text = runCatching { f.readText() }.getOrDefault("").trim()
        if (text.isEmpty()) return ""
        val max = 80_000
        return if (text.length <= max) text else "…\n" + text.takeLast(max)
    }

    override fun readDebugLog(): String {
        val f = File(app.vaydns.platform.AppPaths.filesDir(), "vaydns-debug.log")
        if (!f.exists() || f.length() == 0L) {
            return if (app.vaydns.Strings.current == app.vaydns.AppLanguage.RU) {
                "Лог пуст (desktop). Файл: ${f.absolutePath}\nВключите «Режим отладки» в Настройках."
            } else {
                "Log empty (desktop). Path: ${f.absolutePath}\nEnable debug mode in Settings."
            }
        }
        val text = f.readText()
        val max = 48_000
        return if (text.length <= max) text else "…\n" + text.takeLast(max)
    }

    override fun pickImportFile(onResult: (String?) -> Unit) {
        SwingUtilities.invokeLater {
            val file = showSystemFileChooser(save = false, title = null, suggestedName = null)
            if (file != null) {
                onResult(runCatching { file.readText() }.getOrNull())
            } else {
                onResult(null)
            }
        }
    }

    /**
     * JFileChooser with **system** Look&Feel colors — avoids black file list when
     * the app window uses a dark AWT background / noerasebackground.
     */
    private fun showSystemFileChooser(
        save: Boolean,
        title: String?,
        suggestedName: String?
    ): File? {
        val previousLf = UIManager.getLookAndFeel()
        return try {
            // Force classic system L&F for this dialog only (Windows explorer colors).
            runCatching {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
            }
            val chooser = JFileChooser().apply {
                if (!title.isNullOrBlank()) dialogTitle = title
                if (!suggestedName.isNullOrBlank()) selectedFile = File(suggestedName)
                // Explicit light control colors in case L&F keys were overridden.
                background = java.awt.SystemColor.window
                isOpaque = true
            }
            // Refresh UI after L&F switch.
            SwingUtilities.updateComponentTreeUI(chooser)
            val result = if (save) chooser.showSaveDialog(null) else chooser.showOpenDialog(null)
            if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
        } catch (_: Exception) {
            null
        } finally {
            runCatching {
                if (previousLf != null) UIManager.setLookAndFeel(previousLf)
            }
        }
    }

    override fun supportsSystemVpn(): Boolean = currentHostPlatform().supportsSystemVpn()

    override fun toast(message: String) {
        PlatformLog.log(LogLevel.INFO, "UI", message)
        println(message)
        toastListeners.forEach { listener ->
            runCatching { listener(message) }
        }
    }

    override fun observeToast(onToast: (String) -> Unit): () -> Unit {
        toastListeners += onToast
        return { toastListeners -= onToast }
    }

    override fun validateXrayConfig(json: String): String? =
        runCatching {
            JSONObject(json)
            null
        }.getOrElse { it.message }

    override fun formatXrayJson(json: String): String? =
        runCatching { JSONObject(json).toString(2) }.getOrNull()

    override fun localDnsResolver(): String? = null
}
