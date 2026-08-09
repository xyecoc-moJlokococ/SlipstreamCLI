package app.smugly

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import app.smugly.tunnel.HevSocks5Tunnel
import app.smugly.tunnel.MiniSlipstreamSocksBridge
import app.smugly.tunnel.ResolverListConfig
import app.smugly.tunnel.CdnfuBridge
import app.smugly.tunnel.S3fuBridge
import app.smugly.tunnel.SlipstreamBridge
import app.smugly.tunnel.XrayBridge
import app.smugly.util.AppLog
import app.smugly.service.TinyVpnService
import app.smugly.ui.ConnectUiState
import app.smugly.ui.SmuglyPlatform
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject
// ConfigJson is in shared jvmAndAndroid source set.

/**
 * Android bridge for the shared Compose UI — ConfigStore + VPN/proxy start paths.
 * UI itself lives in `:shared` (Compose Multiplatform).
 */
private const val TAG_PLATFORM = "AndroidSmuglyPlatform"

/** How long a profile switch waits for the old tunnel to go away before starting the new one. */
private const val RECONNECT_STOP_TIMEOUT_MS = 12_000L

class AndroidSmuglyPlatform(
    private val activity: ComponentActivity
) : SmuglyPlatform {
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(ConnectUiState) -> Unit>()
    /** UI listeners for storage written from outside Compose — see [notifyDataChanged]. */
    private val dataListeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var proxyStarted = false
    @Volatile private var connecting = false
    @Volatile private var stopping = false
    private var importCallback: ((String?) -> Unit)? = null

    private val importLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val cb = importCallback
        importCallback = null
        if (uri == null) {
            cb?.invoke(null)
            return@registerForActivityResult
        }
        val text = runCatching {
            activity.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        cb?.invoke(text)
    }

    private val vpnLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            connecting = false
            publish()
            toast(t(S.TOAST_VPN_PERMISSION_REQUIRED))
        }
    }

    init {
        // Tick status for Compose — only notify listeners when the snapshot changes
        // (rebuilding the whole tree every second was a major FPS tax).
        handler.post(object : Runnable {
            override fun run() {
                publishIfChanged()
                handler.postDelayed(this, 1000)
            }
        })
    }

    private var lastPublished: ConnectUiState? = null

    private fun publishIfChanged() {
        val s = snapshot()
        if (s == lastPublished) return
        lastPublished = s
        listeners.forEach { it(s) }
    }

    override fun loadProfiles(): List<ConfigProfile> = ConfigStore.loadProfiles(activity)
    override fun loadActiveProfileId(): String? = ConfigStore.activeProfileId(activity)
    override fun setActiveProfile(id: String) = ConfigStore.setActiveProfile(activity, id)

    override fun selectProfile(id: String) {
        if (id == ConfigStore.activeProfileId(activity)) return
        val shouldReconnect = isRunning() || connecting || stopping
        ConfigStore.setActiveProfile(activity, id)
        if (shouldReconnect) {
            toast(t(S.TOAST_SWITCHING_PROFILE))
            stopAll(reconnect = true)
        }
    }

    override fun saveProfile(profile: ConfigProfile): ConfigProfile =
        ConfigStore.saveProfile(activity, profile)

    override fun addProfile(name: String, config: Config): ConfigProfile =
        ConfigStore.addProfile(activity, name, config)

    override fun deleteProfile(id: String): ConfigProfile? =
        ConfigStore.deleteProfile(activity, id)

    override fun reorderProfiles(orderedIds: List<String>) =
        ConfigStore.reorderProfiles(activity, orderedIds)

    override fun loadGlobalSettings(): GlobalSettings = ConfigStore.loadGlobalSettings(activity)
    override fun saveGlobalSettings(settings: GlobalSettings) {
        ConfigStore.saveGlobalSettings(activity, settings)
        AppLog.setFileLoggingEnabled(activity, settings.fileLogging)
        Strings.set(settings.language)
    }

    override fun toggleConnect() {
        if (isRunning() || connecting || stopping) {
            stopAll(reconnect = false)
            return
        }
        startConnection()
    }

    /** Start VPN/proxy using the currently active profile (after stop or cold start). */
    private fun startConnection() {
        if (isRunning() || connecting || stopping) return
        val c = ConfigStore.effectiveConfig(activity)
        ConfigStore.save(activity, c)
        connecting = true
        publish()
        if (c.mode == Config.Mode.VPN ||
            c.protocol == Config.TunnelProtocol.S3FU ||
            c.protocol == Config.TunnelProtocol.CDNFU ||
            c.protocol == Config.TunnelProtocol.XRAY
        ) {
            val prep = VpnService.prepare(activity)
            if (prep != null) {
                vpnLauncher.launch(prep)
            } else {
                startVpnService()
            }
        } else {
            startProxy(c)
        }
    }

    private fun startVpnService() {
        try {
            val intent = Intent(activity, TinyVpnService::class.java)
                .setAction(TinyVpnService.ACTION_START)
            if (ConfigStore.loadGlobalSettings(activity).trafficNotification) {
                ContextCompat.startForegroundService(activity, intent)
            } else {
                activity.startService(intent)
            }
        } catch (e: Throwable) {
            connecting = false
            toast(e.message ?: t(S.TOAST_VPN_START_FAILED))
        }
        publish()
    }

    private fun startProxy(c: Config) {
        Thread({
            try {
                SlipstreamBridge.setVpnService(null)
                SlipstreamBridge.proxyOnlyMode = true
                SlipstreamBridge.dnsQueryType = c.dnsQueryType
                SlipstreamBridge.dnsLabelLength = c.dnsLabelLength
                SlipstreamBridge.dnsLabelLengthJitter = c.dnsLabelLengthJitter
                SlipstreamBridge.maxPollQps = c.maxPollQps
                SlipstreamBridge.maxDataQps = c.maxDataQps
                SlipstreamBridge.base64uEncoding = c.base64uEncoding
                var choice = ResolverSelector.choose(activity, c, "proxy_start")
                if (c.resolverMode == Config.ResolverMode.AUTO &&
                    (choice.source == "auto-tcp-fallback" || choice.latencyMs < 0)
                ) {
                    choice = ResolverSelector.validateTransport(activity, c, choice, "proxy_start")
                }
                val bridgePort = c.listenPort
                val slipstreamPort = c.listenPort + 1
                val global = ConfigStore.loadGlobalSettings(activity)
                val localUser = if (global.localSocksAuthEnabled) global.localSocksUsername else null
                val localPass = if (global.localSocksAuthEnabled) global.localSocksPassword else null
                SlipstreamBridge.startClient(
                    c.domain,
                    ResolverListConfig(
                        choice.hosts,
                        choice.port,
                        c.resolverPathMode == Config.ResolverPathMode.AUTHORITATIVE
                    ),
                    slipstreamPort,
                    choice.qnameMtu,
                    choice.transport.name.lowercase()
                ).getOrThrow()
                MiniSlipstreamSocksBridge.start(
                    listenHost = "127.0.0.1",
                    listenPort = bridgePort,
                    slipstreamHost = "127.0.0.1",
                    slipstreamPort = slipstreamPort,
                    dnsHost = choice.selectedHost,
                    username = if (c.authMode == Config.AuthMode.LOGIN_PASSWORD) c.username else null,
                    password = if (c.authMode == Config.AuthMode.LOGIN_PASSWORD) c.password else null,
                    localUsername = localUser,
                    localPassword = localPass,
                    maxActiveClients = c.maxActiveClients
                ).getOrThrow()
                proxyStarted = true
            } catch (e: Throwable) {
                AppLog.e("ComposeUI", "proxy start failed", e)
                handler.post { toast(e.message ?: t(S.TOAST_START_FAILED)) }
            } finally {
                connecting = false
                publish()
            }
        }, "compose-proxy-start").start()
    }

    /**
     * @param reconnect if true, start the tunnel again with the (new) active profile
     * after teardown completes — profile-switch path from original MainActivity.
     */
    private fun stopAll(reconnect: Boolean = false) {
        if (stopping) return
        stopping = true
        connecting = false
        // Only the proxy path is ours to tear down; VPN mode and the S3fu/Xray protocols are owned
        // by TinyVpnService, which stops them in its own cleanup. Doing it from here as well meant
        // two threads closing the same native clients at once.
        val ownedByProxyPath = proxyStarted
        proxyStarted = false
        ResolverSelector.cancelActiveProbes(if (reconnect) "profile_switch" else "disconnect")
        activity.startService(
            Intent(activity, TinyVpnService::class.java).setAction(TinyVpnService.ACTION_STOP)
        )
        Thread({
            if (ownedByProxyPath) {
                runCatching { MiniSlipstreamSocksBridge.stop() }
                runCatching { SlipstreamBridge.stopClient() }
            }
            // Wait for the service to finish its teardown rather than guessing with a sleep: a
            // restart that overlaps the previous cleanup is what left the old engine running.
            if (reconnect) {
                awaitTunnelStopped(RECONNECT_STOP_TIMEOUT_MS)
            }
            stopping = false
            publish()
            if (reconnect) {
                handler.post { startConnection() }
            }
        }, if (reconnect) "compose-profile-switch" else "compose-disconnect").start()
        publish()
    }

    /**
     * Block until every engine really is down, so a profile switch never starts the new tunnel on
     * top of the previous one. Returns false on timeout, in which case we start anyway rather than
     * leaving the user with nothing.
     */
    private fun awaitTunnelStopped(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isRunning()) return true
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                return false
            }
        }
        AppLog.w(TAG_PLATFORM, "tunnel still up after ${timeoutMs}ms; reconnecting anyway")
        return false
    }

    /**
     * Every engine counts, not just Slipstream: this also decides whether picking another profile
     * reconnects, and when [awaitTunnelStopped] considers the teardown complete. Missing S3fu/Xray
     * here meant switching away from one of them looked like "nothing was running", so the active
     * profile changed while the old tunnel stayed up.
     */
    private fun isRunning(): Boolean =
        proxyStarted ||
            runCatching { SlipstreamBridge.isRunning() }.getOrDefault(false) ||
            runCatching { S3fuBridge.isRunning() }.getOrDefault(false) ||
            runCatching { CdnfuBridge.isRunning() }.getOrDefault(false) ||
            runCatching { XrayBridge.isRunning() }.getOrDefault(false) ||
            HevSocks5Tunnel.isRunning()

    override fun observeConnect(onChange: (ConnectUiState) -> Unit): () -> Unit {
        listeners += onChange
        onChange(snapshot())
        return { listeners -= onChange }
    }

    private fun publish() {
        lastPublished = null // force emit after connect/stop mutations
        publishIfChanged()
    }

    private fun snapshot(): ConnectUiState {
        val running = isRunning()
        if (running) connecting = false
        val progress = ResolverSelector.lastProgress
        val loading = connecting || progress.active || stopping
        val status = when {
            stopping -> t(S.STATUS_DISCONNECTING)
            loading && progress.active -> when {
                progress.phase.contains("speed", ignoreCase = true) ->
                    speedProbingText(progress.speedTested, progress.speedTotal.coerceAtLeast(1))
                else -> dnsProbingText(progress.tested, progress.total.coerceAtLeast(1))
            }
            loading -> t(S.STATUS_CONNECTING)
            running -> t(S.STATUS_CONNECTED)
            else -> t(S.STATUS_NOT_CONNECTED)
        }
        val hev = HevSocks5Tunnel.stats()
        val bridge = MiniSlipstreamSocksBridge.stats()
        val rx = if (HevSocks5Tunnel.isRunning()) hev.rxBytes else bridge.rxBytes
        val tx = if (HevSocks5Tunnel.isRunning()) hev.txBytes else bridge.txBytes
        // Current speed, not just the running totals: a number that is moving is the quickest
        // way to see the tunnel is alive. Sampled over at least half a second so it does not
        // jitter with the poll interval, and held between samples rather than dropping to zero.
        val now = android.os.SystemClock.elapsedRealtime()
        val elapsed = now - lastTrafficAtMs
        if (lastTrafficAtMs != 0L && elapsed >= 500) {
            val dr = (rx - lastRxBytes).coerceAtLeast(0)
            val dt = (tx - lastTxBytes).coerceAtLeast(0)
            rateDown = dr * 1000 / elapsed
            rateUp = dt * 1000 / elapsed
        }
        if (lastTrafficAtMs == 0L || elapsed >= 500) {
            lastRxBytes = rx
            lastTxBytes = tx
            lastTrafficAtMs = now
        }
        // A stopped tunnel has no speed; leaving the last value up reads as still running.
        if (!running) {
            rateDown = 0
            rateUp = 0
        }
        val traffic = "↓ ${formatBytes(rx)} (${formatBytes(rateDown)}/s)   ↑ ${formatBytes(tx)} (${formatBytes(rateUp)}/s)"
        val diag = buildString {
            appendLine("running=$running ready=${SlipstreamBridge.isReady()} port=${SlipstreamBridge.port()}")
            appendLine("proxyStarted=$proxyStarted connecting=$connecting")
            appendLine("resolver progress ${progress.tested}/${progress.total} phase=${progress.phase}")
            append(TinyVpnService.liveDiag.toString())
        }
        return ConnectUiState(
            statusText = status,
            trafficText = traffic,
            running = running && !loading,
            connecting = loading,
            diagnosticsText = diag
        )
    }

    private fun formatBytes(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> "${n / 1024} KB"
        else -> "${"%.1f".format(n / (1024.0 * 1024.0))} MB"
    }

    override fun readClipboard(): String {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return cm.primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString().orEmpty()
    }

    override fun writeClipboard(text: String) {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("profile", text))
    }

    override fun importFromText(text: String): List<ConfigProfile> =
        ConfigStore.importProfilesFromText(activity, text)

    override fun exportProfileLink(profile: ConfigProfile): String {
        val payload = org.json.JSONObject()
            .put("name", profile.name)
            .put("config", org.json.JSONObject(ConfigJson.configToString(profile.config, 0)))
            .toString()
        // ConfigJson returns indented; re-parse for compact blob when possible
        val compact = runCatching {
            val root = JSONObject()
                .put("name", profile.name)
                .put("id", profile.id)
                .put("config", ConfigJson.configToJson(profile.config))
            root.toString()
        }.getOrDefault(payload)
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(compact.toByteArray())
        val scheme = when (profile.config.protocol) {
            Config.TunnelProtocol.S3FU -> "s3fu"
            Config.TunnelProtocol.CDNFU -> "cdnfu"
            Config.TunnelProtocol.XRAY -> "xray"
            else -> "slipstream"
        }
        return "$scheme://import?config=$b64"
    }

    override fun shareLog() {
        val f = AppLog.file(activity)
        if (!f.exists() || !AppLog.isFileLoggingEnabled(activity)) {
            toast(t(S.TOAST_FILE_LOGGING_DISABLED))
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", f)
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(Intent.createChooser(intent, t(S.SHARE_LOG_CHOOSER)))
    }

    override fun showCrashReport() {
        val text = readCrashReportText()
        if (text.isBlank()) {
            toast(t(S.NO_CRASH_REPORT))
            return
        }
        writeClipboard(text)
        toast(t(S.TOAST_CRASH_REPORT_COPIED))
    }

    override fun readCrashReportText(): String {
        val f = AppLog.crashFile(activity)
        if (!f.exists() || f.length() == 0L) return ""
        val text = runCatching { f.readText() }.getOrDefault("").trim()
        if (text.isEmpty()) return ""
        val max = 80_000
        return if (text.length <= max) text else "…\n" + text.takeLast(max)
    }

    override fun readDebugLog(): String {
        if (!AppLog.isFileLoggingEnabled(activity)) {
            return if (Strings.current == AppLanguage.RU) {
                "Режим отладки выключен. Включите его в Настройках — тогда сюда пойдёт smugly-debug.log."
            } else {
                "Debug mode is off. Enable it in Settings to capture smugly-debug.log here."
            }
        }
        val f = AppLog.file(activity)
        if (!f.exists() || f.length() == 0L) {
            return if (Strings.current == AppLanguage.RU) {
                "Лог пока пуст. Подключитесь — записи появятся здесь."
            } else {
                "Log is still empty. Connect once — lines will show up here."
            }
        }
        // Cap hard — Compose monospace Text + 200k chars freezes the UI thread on open.
        val text = f.readText()
        val max = 48_000
        return if (text.length <= max) text else "…\n" + text.takeLast(max)
    }

    override fun pickImportFile(onResult: (String?) -> Unit) {
        importCallback = onResult
        importLauncher.launch(arrayOf("text/*", "application/json", "*/*"))
    }

    override fun supportsSystemVpn(): Boolean = true

    override fun toast(message: String) {
        handler.post { Toast.makeText(activity, message, Toast.LENGTH_SHORT).show() }
    }

    override fun observeDataChanged(onChange: () -> Unit): () -> Unit {
        dataListeners.add(onChange)
        return { dataListeners.remove(onChange) }
    }

    /**
     * Profiles / subscriptions were written from outside the Compose tree — the `install-sub`
     * deep link does its fetch on its own thread, and nothing else was telling the UI. The folder
     * showed up empty and stayed that way until the app was restarted.
     */
    fun notifyDataChanged() {
        handler.post { dataListeners.forEach { it() } }
    }

    override fun validateXrayConfig(json: String): String? = XrayBridge.validateConfig(json)

    override fun formatXrayJson(json: String): String? =
        runCatching { JSONObject(json).toString(2) }.getOrNull()

    override fun localDnsResolver(): String? =
        ResolverSelector.preferredLocalResolver(activity)

    // ---- subscriptions ----

    private val subscriptions by lazy {
        app.smugly.subscription.SubscriptionRepository(
            object : app.smugly.subscription.SubscriptionRepository.Storage {
                override fun loadSubscriptions() = ConfigStore.loadSubscriptions(activity)
                override fun saveSubscriptions(subs: List<app.smugly.subscription.Subscription>) =
                    ConfigStore.saveSubscriptions(activity, subs)
                override fun loadProfiles() = ConfigStore.loadProfiles(activity)
                override fun writeProfiles(profiles: List<ConfigProfile>) =
                    ConfigStore.replaceProfiles(activity, profiles)
                override fun baseConfig(): Config = ConfigStore.effectiveConfig(activity)
                override fun newId(): String = java.util.UUID.randomUUID().toString()
                override fun nowMs(): Long = System.currentTimeMillis()

                // Parse only. importProfilesFromText also *stores* what it parses, which turned
                // every refresh into "add one more copy of each server to the Home folder".
                override fun profileFromLink(uri: String, name: String): ConfigProfile? =
                    ConfigStore.parseProfileFromLink(activity, uri)

                /**
                 * Always try **both** ways round: the panel may be blocked in this country (so it
                 * needs the tunnel) or, just as often, may be reachable only outside it (so the
                 * tunnel's exit is what fails). Whichever answers first wins.
                 */
                override fun fetchRoutes(): List<app.smugly.subscription.SubscriptionFetcher.ProxySpec?> =
                    buildList {
                        val port = ConfigStore.loadGlobalSettings(activity).listenPort
                        val local = app.smugly.subscription.SubscriptionFetcher.ProxySpec(
                            "127.0.0.1", port, socks = true
                        )
                        if (isRunning()) {
                            // Tunnel up: it is the more likely winner, but a direct attempt still
                            // follows in case the panel is only served to local addresses.
                            add(local)
                            add(null)
                        } else {
                            add(null)
                            // The local proxy may still be listening even when the tunnel reports
                            // stopped (proxy-only mode), so it is worth a second attempt.
                            add(local)
                        }
                    }
            }
        )
    }

    override fun loadSubscriptions() = subscriptions.list()

    override fun addSubscription(rawUrl: String): String? = subscriptions.add(rawUrl).error

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

    // Rolling sample behind the speed shown in the traffic line.
    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var lastTrafficAtMs = 0L
    private var rateDown = 0L
    private var rateUp = 0L

    override fun reorderSubscriptions(orderedIds: List<String>) = subscriptions.reorder(orderedIds)

    override fun exportTextFile(fileName: String, content: String) {
        runCatching {
            // Same route as the log share: a file under filesDir handed out through the app's
            // FileProvider, so no storage permission is involved.
            val dir = java.io.File(activity.filesDir, "export").apply { mkdirs() }
            val file = java.io.File(dir, fileName)
            file.writeText(content)
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
            val intent = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.startActivity(Intent.createChooser(intent, fileName))
        }.onFailure { toast(it.message ?: "export failed") }
    }

    override fun measureLatency(
        profile: app.smugly.ConfigProfile,
        onResult: (Result<Int>) -> Unit
    ) {
        // Plain thread, not a coroutine scope: the probe is a blocking socket round trip and the
        // callback is expected to hop back to the UI itself.
        Thread({ onResult(app.smugly.net.LatencyProbe.measure(profile.config)) }, "latency-probe")
            .apply { isDaemon = true }
            .start()
    }

    override fun looksLikeSubscription(text: String): Boolean =
        app.smugly.subscription.SubscriptionManager.looksLikeSubscription(text)
}
