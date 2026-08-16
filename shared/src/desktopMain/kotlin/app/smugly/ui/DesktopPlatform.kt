package app.smugly.ui

import app.smugly.Config
import app.smugly.ConfigJson
import app.smugly.ConfigProfile
import app.smugly.GlobalSettings
import app.smugly.S
import app.smugly.VlessLinkParser
import app.smugly.XrayConfigBuilder
import app.smugly.currentHostPlatform
import app.smugly.defaultConfig
import app.smugly.desktop.DesktopTunnel
import app.smugly.desktop.EngineBinaries
import app.smugly.platform.PlatformLog
import app.smugly.platform.LogLevel
import app.smugly.supportsSystemVpn
import app.smugly.t
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
 * Desktop (Windows/Linux/macOS) implementation of [SmuglyPlatform].
 * Profile/settings UI is fully shared; tunnel connect is proxy-oriented (no system VPN).
 */
class DesktopPlatform(
    private val store: FileProfileStore = FileProfileStore()
) : SmuglyPlatform {
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
                if (app.smugly.Strings.current == app.smugly.AppLanguage.RU) {
                    "Системный прокси восстановлен после некорректного завершения"
                } else {
                    "System proxy restored after an unclean shutdown"
                }
            )
        }
        // Make sure the tunnel dies with the UI even on a hard exit.
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { DesktopTunnel.stop() } })
        Thread({ statusLoop() }, "smugly-status").apply { isDaemon = true }.start()
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
        // Speed alongside the totals — see the same computation on Android.
        val now = System.currentTimeMillis()
        val elapsed = now - lastTrafficAtMs
        if (lastTrafficAtMs != 0L && elapsed >= 500) {
            rateDown = (rx - lastRxBytes).coerceAtLeast(0) * 1000 / elapsed
            rateUp = (tx - lastTxBytes).coerceAtLeast(0) * 1000 / elapsed
        }
        if (lastTrafficAtMs == 0L || elapsed >= 500) {
            lastRxBytes = rx
            lastTxBytes = tx
            lastTrafficAtMs = now
        }
        if (!running) {
            rateDown = 0
            rateUp = 0
        }
        // Engine binary paths only change if the install is rewritten; cache them so the 1 Hz
        // traffic tick does not re-stat the filesystem on every publish.
        if (engineReportCache == null) engineReportCache = EngineBinaries.report()
        return ConnectUiState(
            statusText = status,
            trafficText = "↓ ${formatBytes(rx)} (${formatBytes(rateDown)}/s)   ↑ ${formatBytes(tx)} (${formatBytes(rateUp)}/s)",
            running = running && !connecting,
            connecting = connecting,
            diagnosticsText = buildString {
                appendLine("running=$running connecting=$connecting")
                appendLine("connections=${proxy?.activeConnections() ?: 0} ok=${proxy?.connectOkCount() ?: 0} fail=${proxy?.connectFailCount() ?: 0}")
                if (lastError.isNotBlank()) appendLine("lastError=$lastError")
                appendLine(engineReportCache)
            }
        )
    }

    private var engineReportCache: String? = null

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
    override fun deleteProfile(id: String): ConfigProfile? = store.deleteProfile(id)
    override fun reorderProfiles(orderedIds: List<String>) = store.reorderProfiles(orderedIds)
    /**
     * Local proxy auth is forced off on desktop — the setting is not offered here (see
     * [app.smugly.supportsLocalProxyAuth]), and a value left over from an imported/older config
     * would otherwise silently make every system-proxied request fail with 407.
     */
    override fun loadGlobalSettings(): GlobalSettings =
        store.loadGlobalSettings().copy(localSocksAuthEnabled = false)
    override fun saveGlobalSettings(settings: GlobalSettings) {
        store.saveGlobalSettings(settings)
        PlatformLog.fileLoggingEnabled = settings.fileLogging
        // When debug mode is on, ensure a log file exists so Diagnostics has something to show.
        if (settings.fileLogging) {
            val f = File(app.smugly.platform.AppPaths.filesDir(), "smugly-debug.log")
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
                        if (app.smugly.Strings.current == app.smugly.AppLanguage.RU) {
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
        }, "smugly-connect").start()
    }

    private fun disconnect() {
        connecting = false
        Thread({
            DesktopTunnel.stop()
            publish()
        }, "smugly-disconnect").start()
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
        // Multi-link paste (subscription body or clipboard with several vless:// lines).
        if (VlessLinkParser.looksLikeLink(trimmed)) {
            val links = VlessLinkParser.findAll(trimmed)
            if (links.isNotEmpty()) {
                return links.mapNotNull { raw ->
                    parseProfileFromText(raw)?.let { addProfile(it.name, it.config) }
                }
            }
        }
        val parsed = parseProfileFromText(trimmed) ?: return emptyList()
        return listOf(addProfile(parsed.name, parsed.config))
    }

    /**
     * Parse without storing — the subscription repository writes its group itself and only needs
     * the parsed object. Supports `vless://…` (Xray) and JSON profile blobs.
     * The returned profile's id is a placeholder; callers assign one.
     */
    private fun parseProfileFromText(text: String, preferredName: String = ""): ConfigProfile? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        // vless://uuid@host:port?…#remarks  — primary subscription format (Happ / Marzban / …).
        if (trimmed.startsWith("vless://", ignoreCase = true)) {
            val link = VlessLinkParser.parse(trimmed) ?: return null
            val listen = runCatching { store.loadGlobalSettings().listenPort }.getOrDefault(1080)
            val base = defaultConfig(listenPort = listen, mode = Config.Mode.PROXY)
            return ConfigProfile(
                id = "",
                name = preferredName.ifBlank { link.remarks }.ifBlank { link.server },
                config = base.copy(
                    protocol = Config.TunnelProtocol.XRAY,
                    xrayConfigJson = XrayConfigBuilder.build(link, listen)
                )
            )
        }

        // slipstream / s3fu / cdnfu / xray — both the exported config= blob and the
        // query-param form the panel actually mints (url/psk/domain/…).
        if (trimmed.startsWith("xray://", ignoreCase = true) ||
            trimmed.startsWith("slipstream://", ignoreCase = true) ||
            trimmed.startsWith("s3fu://", ignoreCase = true) ||
            trimmed.startsWith("cdnfu://", ignoreCase = true)
        ) {
            return app.smugly.desktop.DesktopProfileLinks.parse(
                trimmed,
                preferredName = preferredName,
                base = defaultConfig(
                    listenPort = runCatching { store.loadGlobalSettings().listenPort }.getOrDefault(1080),
                    mode = Config.Mode.PROXY
                )
            )
        }

        if (!trimmed.startsWith("{")) return null
        return runCatching {
            val json = JSONObject(trimmed)
            if (json.has("config")) {
                ConfigJson.profileFromJson(json).let { p ->
                    if (preferredName.isNotBlank()) p.copy(name = preferredName) else p
                }
            } else if (json.has("outbounds")) {
                // Bare Xray config document from a panel.
                val listen = runCatching { store.loadGlobalSettings().listenPort }.getOrDefault(1080)
                val base = defaultConfig(listenPort = listen, mode = Config.Mode.PROXY)
                val name = preferredName
                    .ifBlank { json.optString("remarks") }
                    .ifBlank { json.optString("name") }
                    .ifBlank { XrayConfigBuilder.describeServer(trimmed) }
                    .orEmpty()
                    .ifBlank { "Xray" }
                ConfigProfile(
                    id = "",
                    name = name,
                    config = base.copy(
                        protocol = Config.TunnelProtocol.XRAY,
                        xrayConfigJson = XrayConfigBuilder.withSocksPort(trimmed, listen)
                    )
                )
            } else {
                ConfigProfile(
                    id = "",
                    name = preferredName.ifBlank { json.optString("name") }.ifBlank { "Imported" },
                    config = ConfigJson.configFromJson(json)
                )
            }
        }.getOrNull()
    }

    override fun exportProfileLink(profile: ConfigProfile): String {
        val payload = ConfigJson.profileToJson(profile).toString()
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
        val scheme = when (profile.config.protocol) {
            Config.TunnelProtocol.S3FU -> "s3fu"
            Config.TunnelProtocol.CDNFU -> "cdnfu"
            Config.TunnelProtocol.XRAY -> "xray"
            else -> "slipstream"
        }
        return "$scheme://import?config=$b64"
    }

    override fun shareLog() {
        val f = File(app.smugly.platform.AppPaths.filesDir(), "smugly-debug.log")
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
                suggestedName = "smugly-debug.log"
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
        val dir = File(app.smugly.platform.AppPaths.filesDir())
        val candidates = listOf(
            File(dir, "desktop-crash.log"),
            File(dir, "smugly-crash.log"),
            File(dir, "crash.log")
        )
        val f = candidates.firstOrNull { it.exists() && it.length() > 0 } ?: return ""
        val text = runCatching { f.readText() }.getOrDefault("").trim()
        if (text.isEmpty()) return ""
        val max = 80_000
        return if (text.length <= max) text else "…\n" + text.takeLast(max)
    }

    override fun readDebugLog(): String {
        val f = File(app.smugly.platform.AppPaths.filesDir(), "smugly-debug.log")
        if (!f.exists() || f.length() == 0L) {
            return if (app.smugly.Strings.current == app.smugly.AppLanguage.RU) {
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

    // ---- subscriptions ----

    private val subscriptions by lazy {
        app.smugly.subscription.SubscriptionRepository(
            object : app.smugly.subscription.SubscriptionRepository.Storage {
                override fun loadSubscriptions() = store.loadSubscriptions()
                override fun saveSubscriptions(subs: List<app.smugly.subscription.Subscription>) =
                    store.saveSubscriptions(subs)
                override fun loadProfiles() = store.loadProfiles()
                override fun writeProfiles(profiles: List<ConfigProfile>) = store.writeProfiles(profiles)
                override fun baseConfig(): Config {
                    val settings = store.loadGlobalSettings()
                    return app.smugly.defaultConfig(
                        listenPort = settings.listenPort,
                        mode = Config.Mode.PROXY
                    )
                }
                override fun newId(): String = java.util.UUID.randomUUID().toString()
                override fun nowMs(): Long = System.currentTimeMillis()
                // Parse only — importFromText persists what it parses, which would leave one
                // extra Home copy of every server behind on each refresh.
                override fun profileFromLink(uri: String, name: String): ConfigProfile? =
                    parseProfileFromText(uri, preferredName = name)

                override fun loadCollapsedCategories(): Set<String> =
                    store.loadGlobalSettings().collapsedCategories

                override fun saveCollapsedCategories(ids: Set<String>) {
                    val current = store.loadGlobalSettings()
                    if (current.collapsedCategories != ids) {
                        store.saveGlobalSettings(current.copy(collapsedCategories = ids))
                    }
                }

                override fun fetchRoutes(): List<app.smugly.subscription.SubscriptionFetcher.ProxySpec?> =
                    buildList {
                        // Both ways are tried: the panel may be blocked here (needs the tunnel)
                        // or blocked at the tunnel's exit (needs a direct connection).
                        if (DesktopTunnel.isRunning) {
                            add(
                                app.smugly.subscription.SubscriptionFetcher.ProxySpec(
                                    "127.0.0.1",
                                    store.loadGlobalSettings().listenPort
                                )
                            )
                        }
                        // Then whatever proxy the machine is already configured to use.
                        if (app.smugly.desktop.WindowsSystemProxy.isWindows) {
                            val snapshot = app.smugly.desktop.WindowsSystemProxy.snapshot()
                            if (snapshot.enabled) {
                                val hostPort = snapshot.server.substringAfterLast('=').trim()
                                val host = hostPort.substringBeforeLast(':', "")
                                val port = hostPort.substringAfterLast(':', "").toIntOrNull()
                                if (host.isNotBlank() && port != null) {
                                    add(app.smugly.subscription.SubscriptionFetcher.ProxySpec(host, port))
                                }
                            }
                        }
                        add(null) // direct, last
                    }
            }
        )
    }

    override fun loadSubscriptions() = subscriptions.list()

    override fun addSubscription(rawUrl: String): String? {
        val result = subscriptions.add(rawUrl)
        return result.error
    }

    override fun refreshSubscription(id: String): String? = subscriptions.refresh(id).error

    override fun refreshDueSubscriptions(): Int =
        subscriptions.refreshDue().count { it.isSuccess }

    override fun deleteSubscription(id: String) = subscriptions.delete(id)

    override fun renameSubscription(id: String, name: String) = subscriptions.rename(id, name)

    override fun saveSubscription(
        id: String?,
        name: String,
        url: String,
        enabled: Boolean,
        updateIntervalMinutes: Long,
        allowReorder: Boolean,
        showInfo: Boolean
    ): String? = subscriptions.save(
        id, name, url, enabled, updateIntervalMinutes, allowReorder, showInfo
    )

    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var lastTrafficAtMs = 0L
    private var rateDown = 0L
    private var rateUp = 0L

    override fun reorderSubscriptions(orderedIds: List<String>) = subscriptions.reorder(orderedIds)

    override fun exportTextFile(fileName: String, content: String) {
        runCatching {
            val file = java.io.File(app.smugly.platform.AppPaths.filesDir(), fileName)
            file.parentFile?.mkdirs()
            file.writeText(content)
            toast(file.absolutePath)
        }.onFailure { toast(it.message ?: "export failed") }
    }

    override fun measureLatency(
        profile: app.smugly.ConfigProfile,
        onResult: (Result<Int>) -> Unit
    ) {
        // Starts the profile's own engine on a throwaway port and times a request through it.
        // Falls back to a plain reachability check only when that engine binary is missing.
        app.smugly.net.E2ELatencyProbe.submit(profile.config, probeEngines, onResult)
    }

    private val probeEngines by lazy {
        app.smugly.desktop.DesktopProbeEngines { store.loadGlobalSettings().dnsResolverPool }
    }

    override fun looksLikeSubscription(text: String): Boolean =
        app.smugly.subscription.SubscriptionManager.looksLikeSubscription(text)
}
