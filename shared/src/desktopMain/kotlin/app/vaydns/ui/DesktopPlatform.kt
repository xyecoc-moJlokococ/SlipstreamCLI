package app.vaydns.ui

import app.vaydns.Config
import app.vaydns.ConfigJson
import app.vaydns.ConfigProfile
import app.vaydns.GlobalSettings
import app.vaydns.S
import app.vaydns.currentHostPlatform
import app.vaydns.desktop.DesktopTunnel
import app.vaydns.desktop.EngineBinaries
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
    @Volatile private var connecting = false
    @Volatile private var lastError: String = ""

    init {
        PlatformLog.fileLoggingEnabled = runCatching { store.loadGlobalSettings().fileLogging }
            .getOrDefault(false)
        // A previous run may have died with the system proxy still pointing at our (now dead)
        // local port, which would leave the machine with no working connection at all.
        if (DesktopTunnel.recoverFromCrash()) {
            toast(
                if (app.vaydns.Strings.current == app.vaydns.AppLanguage.RU) {
                    "Системный прокси восстановлен после некорректного завершения"
                } else {
                    "System proxy restored after an unclean shutdown"
                }
            )
        }
        // Make sure the tunnel dies with the UI even on a hard exit.
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { DesktopTunnel.stop() } })
        Thread({ statusLoop() }, "vaydns-status").apply { isDaemon = true }.start()
    }

    /** Publishes connect state once a second so traffic counters and engine death are visible. */
    private fun statusLoop() {
        while (true) {
            runCatching { publishIfChanged() }
            Thread.sleep(1000)
        }
    }

    private fun snapshot(): ConnectUiState {
        val running = DesktopTunnel.isRunning
        val proxy = DesktopTunnel.proxyServer()
        val rx = proxy?.rxBytes() ?: 0L
        val tx = proxy?.txBytes() ?: 0L
        val status = when {
            connecting -> t(S.STATUS_CONNECTING)
            running -> t(S.STATUS_CONNECTED)
            else -> t(S.STATUS_NOT_CONNECTED)
        }
        return ConnectUiState(
            statusText = status,
            trafficText = "↓ ${formatBytes(rx)}   ↑ ${formatBytes(tx)}",
            running = running && !connecting,
            connecting = connecting,
            diagnosticsText = buildString {
                appendLine("running=$running connecting=$connecting")
                appendLine("connections=${proxy?.activeConnections() ?: 0} ok=${proxy?.connectOkCount() ?: 0} fail=${proxy?.connectFailCount() ?: 0}")
                if (lastError.isNotBlank()) appendLine("lastError=$lastError")
                appendLine(EngineBinaries.report())
            }
        )
    }

    private var lastPublished: ConnectUiState? = null

    private fun publishIfChanged() {
        val s = snapshot()
        if (s == lastPublished) return
        lastPublished = s
        listeners.forEach { runCatching { it(s) } }
    }

    private fun publish() {
        lastPublished = null
        publishIfChanged()
    }

    private fun formatBytes(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> "${n / 1024} KB"
        else -> String.format("%.1f MB", n / (1024.0 * 1024.0))
    }

    private fun activeConfig(): Config? {
        val profiles = store.loadProfiles()
        val id = store.loadActiveProfileId()
        return (profiles.firstOrNull { it.id == id } ?: profiles.firstOrNull())?.config
    }

    override fun loadProfiles(): List<ConfigProfile> = store.loadProfiles()
    override fun loadActiveProfileId(): String? = store.loadActiveProfileId()
    override fun setActiveProfile(id: String) = store.setActiveProfile(id)

    override fun selectProfile(id: String) {
        if (id == store.loadActiveProfileId()) return
        val wasRunning = DesktopTunnel.isRunning || connecting
        store.setActiveProfile(id)
        if (wasRunning) {
            toast(t(S.TOAST_SWITCHING_PROFILE))
            disconnect()
            startConnection()
        }
    }

    override fun saveProfile(profile: ConfigProfile): ConfigProfile = store.saveProfile(profile)
    override fun addProfile(name: String, config: Config): ConfigProfile = store.addProfile(name, config)
    override fun deleteProfile(id: String): ConfigProfile = store.deleteProfile(id)
    override fun reorderProfiles(orderedIds: List<String>) = store.reorderProfiles(orderedIds)
    /**
     * Local proxy auth is forced off on desktop — the setting is not offered here (see
     * [app.vaydns.supportsLocalProxyAuth]), and a value left over from an imported/older config
     * would otherwise silently make every system-proxied request fail with 407.
     */
    override fun loadGlobalSettings(): GlobalSettings =
        store.loadGlobalSettings().copy(localSocksAuthEnabled = false)
    override fun saveGlobalSettings(settings: GlobalSettings) {
        store.saveGlobalSettings(settings)
        PlatformLog.fileLoggingEnabled = settings.fileLogging
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
        if (DesktopTunnel.isRunning || connecting) {
            disconnect()
        } else {
            startConnection()
        }
    }

    /**
     * Connect off the UI thread: starting an engine involves process spawn plus a wait for its
     * listener, which is far too long to block a frame on.
     */
    private fun startConnection() {
        if (connecting) return
        val config = activeConfig()
        if (config == null) {
            toast(t(S.TOAST_START_FAILED))
            return
        }
        connecting = true
        lastError = ""
        publish()
        Thread({
            val result = DesktopTunnel.start(config, loadGlobalSettings())
            connecting = false
            result
                .onSuccess { outcome ->
                    outcome.warning?.let { toast(it) }
                    toast(
                        if (app.vaydns.Strings.current == app.vaydns.AppLanguage.RU) {
                            "Подключено (${outcome.engineName}) — 127.0.0.1:${outcome.listenPort}"
                        } else {
                            "Connected (${outcome.engineName}) — 127.0.0.1:${outcome.listenPort}"
                        }
                    )
                }
                .onFailure { e ->
                    lastError = e.message.orEmpty()
                    toast(e.message ?: t(S.TOAST_START_FAILED))
                }
            publish()
        }, "vaydns-connect").start()
    }

    private fun disconnect() {
        connecting = false
        Thread({
            DesktopTunnel.stop()
            publish()
        }, "vaydns-disconnect").start()
        publish()
    }

    override fun observeConnect(onChange: (ConnectUiState) -> Unit): () -> Unit {
        listeners += onChange
        onChange(snapshot())
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

    /** Reused across calls — see [showSystemFileChooser]. Swing objects are main-thread only. */
    private var cachedChooser: JFileChooser? = null

    /**
     * JFileChooser with **system** Look&Feel colors — avoids a black file list when the app window
     * uses a dark AWT background / noerasebackground.
     *
     * Both the L&F switch and the chooser itself are done once and kept. Rebuilding them per call
     * meant every "Import from file" paid Swing's full first-use cost — `setLookAndFeel` plus
     * `updateComponentTreeUI` on a fresh component tree is hundreds of milliseconds.
     */
    private fun showSystemFileChooser(
        save: Boolean,
        title: String?,
        suggestedName: String?
    ): File? = try {
        val chooser = cachedChooser ?: run {
            // Classic system L&F (Windows explorer colors) for AWT dialogs; Compose draws its own
            // UI with Skia and is unaffected, so this can stay set for the process lifetime.
            runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
            JFileChooser().apply {
                background = java.awt.SystemColor.window
                isOpaque = true
                SwingUtilities.updateComponentTreeUI(this)
            }.also { cachedChooser = it }
        }
        chooser.dialogTitle = title
        chooser.selectedFile = if (suggestedName.isNullOrBlank()) null else File(suggestedName)
        val result = if (save) chooser.showSaveDialog(null) else chooser.showOpenDialog(null)
        if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    } catch (_: Exception) {
        null
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
