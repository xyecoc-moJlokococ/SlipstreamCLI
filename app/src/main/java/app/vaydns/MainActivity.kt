package app.vaydns

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.TrafficStats
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import app.slipnet.tunnel.HevSocks5Tunnel
import app.slipnet.tunnel.MiniSlipstreamSocksBridge
import app.slipnet.tunnel.ResolverListConfig
import app.slipnet.tunnel.SlipstreamBridge
import app.slipnet.util.AppLog
import app.vaydns.service.TinyVpnService

class MainActivity : android.app.Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var domain: EditText
    private lateinit var resolverHost: EditText
    private lateinit var resolverPort: EditText
    private lateinit var resolverMode: Spinner
    private lateinit var useLocalDns: Button
    private lateinit var listenPort: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var mode: Spinner
    private lateinit var auth: Spinner
    private lateinit var connectButton: Button
    private lateinit var connectProgress: ProgressBar
    private lateinit var status: TextView
    private var proxyStarted = false
    @Volatile private var stopping = false
    @Volatile private var connecting = false
    private var rxBase = 0L
    private var txBase = 0L
    private var lastLogAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        SlipstreamBridge.setLogFilePath(AppLog.file(this).absolutePath)
        maybeAskNotifications()
        setContentView(buildUi())
        loadConfig()
        handler.post(tick)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN && resultCode == RESULT_OK) startVpn()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
        }
        status = TextView(this).apply { textSize = 14f }
        domain = edit("domain")
        resolverHost = edit("resolver host")
        useLocalDns = Button(this).apply {
            text = "Use local DNS"
            setOnClickListener { fillLocalDns() }
        }
        val resolverRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(resolverHost, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(useLocalDns, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        resolverPort = edit("resolver port", InputType.TYPE_CLASS_NUMBER)
        resolverMode = spinner(listOf("manual dns", "auto dns")).apply {
            setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    updateResolverUi()
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            })
        }
        listenPort = edit("local port", InputType.TYPE_CLASS_NUMBER)
        mode = spinner(listOf("proxy", "vpn"))
        auth = spinner(listOf("no-auth", "login/password"))
        username = edit("username")
        password = edit("password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        connectProgress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        connectButton = Button(this).apply {
            text = "Connect"
            setOnClickListener { toggle() }
        }
        val saveButton = Button(this).apply {
            text = "Save config"
            setOnClickListener {
                ConfigStore.save(this@MainActivity, readConfig())
                toast("saved")
            }
        }
        val shareLog = Button(this).apply {
            text = "Share log"
            setOnClickListener { shareLogFile() }
        }
        listOf(status, domain, resolverMode, resolverRow, resolverPort, listenPort, mode, auth, username, password, connectProgress, connectButton, saveButton, shareLog)
            .forEach { root.addView(it, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }
        return root
    }

    private fun edit(hint: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText =
        EditText(this).apply {
            this.hint = hint
            inputType = type
            setSingleLine(true)
        }

    private fun spinner(items: List<String>): Spinner =
        Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        }

    private fun loadConfig() {
        val c = ConfigStore.load(this)
        domain.setText(c.domain)
        resolverHost.setText(c.resolverHost)
        resolverPort.setText(c.resolverPort.toString())
        resolverMode.setSelection(if (c.resolverMode == Config.ResolverMode.AUTO) 1 else 0)
        listenPort.setText(c.listenPort.toString())
        mode.setSelection(if (c.mode == Config.Mode.VPN) 1 else 0)
        auth.setSelection(if (c.authMode == Config.AuthMode.LOGIN_PASSWORD) 1 else 0)
        username.setText(c.username)
        password.setText(c.password)
        updateResolverUi()
    }

    private fun readConfig(): Config {
        val resolverModeValue = if (resolverMode.selectedItemPosition == 1) {
            Config.ResolverMode.AUTO
        } else {
            Config.ResolverMode.MANUAL
        }
        val host = if (resolverModeValue == Config.ResolverMode.AUTO) {
            currentAutoResolverHost().ifBlank { resolverHost.text.toString().trim() }
        } else {
            resolverHost.text.toString().trim()
        }
        return Config(
            domain = domain.text.toString().trim(),
            resolverHost = host,
            resolverPort = resolverPort.text.toString().toIntOrNull() ?: 53,
            resolverMode = resolverModeValue,
            listenPort = listenPort.text.toString().toIntOrNull() ?: 1080,
            mode = if (mode.selectedItemPosition == 1) Config.Mode.VPN else Config.Mode.PROXY,
            authMode = if (auth.selectedItemPosition == 1) Config.AuthMode.LOGIN_PASSWORD else Config.AuthMode.NO_AUTH,
            username = username.text.toString(),
            password = password.text.toString()
        )
    }

    private fun toggle() {
        if (proxyStarted || SlipstreamBridge.isRunning() || HevSocks5Tunnel.isRunning()) {
            stopAll()
            return
        }
        val c = readConfig()
        connecting = true
        updateStatus()
        ConfigStore.save(this, c)
        if (c.mode == Config.Mode.VPN) {
            val intent = VpnService.prepare(this)
            if (intent != null) startActivityForResult(intent, REQ_VPN) else startVpn()
        } else {
            startProxy(c)
        }
    }

    private fun startProxy(c: Config) {
        connectButton.isEnabled = false
        Thread({
            try {
                resetTrafficBase()
                SlipstreamBridge.setVpnService(null)
                SlipstreamBridge.proxyOnlyMode = true
                val choice = ResolverSelector.choose(this, c, "proxy_start")
                val bridgePort = c.listenPort
                val slipstreamPort = c.listenPort + 1
                SlipstreamBridge.startClient(
                    c.domain,
                    ResolverListConfig(choice.hosts, choice.port, true),
                    slipstreamPort,
                    choice.qnameMtu
                ).getOrThrow()
                MiniSlipstreamSocksBridge.start(
                    listenHost = "127.0.0.1",
                    listenPort = bridgePort,
                    slipstreamHost = "127.0.0.1",
                    slipstreamPort = slipstreamPort,
                    dnsHost = choice.selectedHost,
                    username = if (c.authMode == Config.AuthMode.LOGIN_PASSWORD) c.username else null,
                    password = if (c.authMode == Config.AuthMode.LOGIN_PASSWORD) c.password else null
                ).getOrThrow()
                proxyStarted = true
                AppLog.i(
                    TAG,
                    "proxy connected resolver=${choice.selectedHost}:${choice.port} source=${choice.source} " +
                        "qnameMtu=${if (choice.qnameMtu > 0) choice.qnameMtu else "max"} " +
                        "tested=${choice.testedCount} alive=${choice.aliveCount} skipped=${choice.skippedCount}"
                )
            } catch (e: Throwable) {
                AppLog.e(TAG, "proxy start failed", e)
                handler.post { toast(e.message ?: "start failed") }
            } finally {
                handler.post {
                    connecting = false
                    connectButton.isEnabled = true
                    updateStatus()
                }
            }
        }, "proxy-start").start()
    }

    private fun startVpn() {
        try {
            resetTrafficBase()
            AppLog.i(TAG, "start vpn requested")
            ContextCompat.startForegroundService(
                this,
                Intent(this, TinyVpnService::class.java).setAction(TinyVpnService.ACTION_START)
            )
        } catch (e: Throwable) {
            connecting = false
            AppLog.e(TAG, "failed to start vpn service", e)
            toast(e.message ?: "vpn start failed")
        }
    }

    private fun stopAll() {
        if (stopping) return
        stopping = true
        connectButton.isEnabled = false
        proxyStarted = false
        AppLog.i(TAG, "disconnect requested")
        startService(Intent(this, TinyVpnService::class.java).setAction(TinyVpnService.ACTION_STOP))
        Thread({
            try {
                runCatching { MiniSlipstreamSocksBridge.stop() }
                runCatching { SlipstreamBridge.stopClient() }
                runCatching { HevSocks5Tunnel.stop() }
                AppLog.i(TAG, "disconnect cleanup finished")
            } finally {
                handler.post {
                    stopping = false
                    connectButton.isEnabled = true
                    updateStatus()
                }
            }
        }, "disconnect-cleanup").start()
    }

    private fun updateStatus() {
        val uid = applicationInfo.uid
        val rx = (TrafficStats.getUidRxBytes(uid).takeIf { it >= 0 } ?: 0) - rxBase
        val tx = (TrafficStats.getUidTxBytes(uid).takeIf { it >= 0 } ?: 0) - txBase
        val hev = HevSocks5Tunnel.stats()
        val bridge = MiniSlipstreamSocksBridge.stats()
        val running = SlipstreamBridge.isRunning() || HevSocks5Tunnel.isRunning() || proxyStarted
        if (running) connecting = false
        val resolverProgress = ResolverSelector.lastProgress
        connectProgress.visibility = if (connecting || resolverProgress.active) View.VISIBLE else View.GONE
        connectButton.text = when {
            stopping -> "Disconnecting"
            connecting || resolverProgress.active -> "Connecting..."
            running -> "Disconnect"
            else -> "Connect"
        }
        status.text = buildString {
            appendLine("running=$running ready=${SlipstreamBridge.isReady()} port=${SlipstreamBridge.port()}")
            appendLine("transport=tcp resolver=authoritative cc=authoritative-fast")
            appendLine("upstream=qname")
            if (resolverMode.selectedItemPosition == 1) {
                val localDns = currentAutoResolverHost()
                appendLine("resolver mode=auto local=${localDns.ifBlank { "-" }} host=${resolverHost.text}")
                appendLine(
                    "dns probe ${resolverProgress.tested}/${resolverProgress.total} phase=${resolverProgress.phase.ifBlank { "-" }} " +
                        "alive=${resolverProgress.alive} current=${resolverProgress.currentHost.ifBlank { "-" }} " +
                        "selected=${resolverProgress.selected.ifBlank { "-" }}"
                )
                appendLine(
                    "speed probe ${resolverProgress.speedTested}/${resolverProgress.speedTotal} " +
                    "ok=${resolverProgress.speedOk}"
                )
            } else {
                appendLine("resolver mode=manual host=${resolverHost.text}")
            }
            appendLine("app rx=${formatBytes(rx)} tx=${formatBytes(tx)}")
            appendLine("vpn rx=${formatBytes(hev.rxBytes)} tx=${formatBytes(hev.txBytes)}")
            appendLine("bridge rx=${formatBytes(bridge.rxBytes)} tx=${formatBytes(bridge.txBytes)} ok=${bridge.connectOk}/${bridge.dnsOk} fail=${bridge.connectFail}/${bridge.dnsFail}")
            appendLine("bridge active=${bridge.activeClients} clients=${bridge.clientSockets} remotes=${bridge.remoteSockets} threads=${bridge.threads}")
            SlipstreamBridge.lastError()?.let { appendLine("lastError=$it") }
        }
        val now = System.currentTimeMillis()
        if (running && now - lastLogAt > 5000) {
            lastLogAt = now
            AppLog.i(
                TAG,
                "diag running=$running ready=${SlipstreamBridge.isReady()} port=${SlipstreamBridge.port()} " +
                    "appRx=$rx appTx=$tx vpnRx=${hev.rxBytes} vpnTx=${hev.txBytes} " +
                    "bridgeRx=${bridge.rxBytes} bridgeTx=${bridge.txBytes} " +
                    "connectOk=${bridge.connectOk} connectFail=${bridge.connectFail} dnsOk=${bridge.dnsOk} dnsFail=${bridge.dnsFail} " +
                    "bridgeActive=${bridge.activeClients} bridgeClients=${bridge.clientSockets} bridgeRemotes=${bridge.remoteSockets} bridgeThreads=${bridge.threads} " +
                    "dnsProbe=${resolverProgress.tested}/${resolverProgress.total} dnsPhase=${resolverProgress.phase} dnsAlive=${resolverProgress.alive} dnsCurrent=${resolverProgress.currentHost} dnsSelected=${resolverProgress.selected} " +
                    "speedProbe=${resolverProgress.speedTested}/${resolverProgress.speedTotal} speedOk=${resolverProgress.speedOk} " +
                    "lastError=${SlipstreamBridge.lastError() ?: "none"}"
            )
        }
    }

    private fun resetTrafficBase() {
        val uid = applicationInfo.uid
        rxBase = TrafficStats.getUidRxBytes(uid).takeIf { it >= 0 } ?: 0
        txBase = TrafficStats.getUidTxBytes(uid).takeIf { it >= 0 } ?: 0
    }

    private fun fillLocalDns() {
        val hosts = ResolverSelector.localMobileResolvers(this)
            .ifEmpty { ResolverSelector.localDefaultResolvers(this) }
        val host = hosts.firstOrNull()
        if (host == null) {
            toast("no local DNS")
            AppLog.w(TAG, "local DNS fill failed: no local DNS")
            return
        }
        resolverHost.setText(host)
        AppLog.i(TAG, "local DNS filled host=$host all=${hosts.joinToString()}")
    }

    private fun updateResolverUi() {
        val manual = resolverMode.selectedItemPosition == 0
        resolverHost.isEnabled = manual
        useLocalDns.visibility = if (manual) View.VISIBLE else View.GONE
        if (!manual) {
            val host = currentAutoResolverHost()
            if (host.isNotBlank() && resolverHost.text.toString() != host) {
                resolverHost.setText(host)
                AppLog.i(TAG, "auto DNS UI updated local=$host")
            }
        }
    }

    private fun currentAutoResolverHost(): String =
        ResolverSelector.preferredLocalResolver(this).orEmpty()

    private fun shareLogFile() {
        val file = AppLog.file(this)
        if (!file.exists()) file.writeText("empty log\n")
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share log"))
    }

    private fun maybeAskNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 12)
        }
    }

    private fun formatBytes(value: Long): String {
        val v = value.coerceAtLeast(0)
        return when {
            v >= 1024L * 1024L -> "${v / 1024L / 1024L} MiB"
            v >= 1024L -> "${v / 1024L} KiB"
            else -> "$v B"
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "MainActivity"
        private const val REQ_VPN = 100
    }
}
