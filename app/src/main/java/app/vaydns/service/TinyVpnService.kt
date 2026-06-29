package app.vaydns.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import app.slipnet.tunnel.HevSocks5Tunnel
import app.slipnet.tunnel.MiniSlipstreamSocksBridge
import app.slipnet.tunnel.ResolverListConfig
import app.slipnet.tunnel.SlipstreamBridge
import app.slipnet.util.AppLog
import app.vaydns.Config
import app.vaydns.ConfigStore
import app.vaydns.MainActivity
import app.vaydns.R
import app.vaydns.ResolverChoice
import app.vaydns.ResolverSelector

class TinyVpnService : VpnService() {
    private var tunFd: ParcelFileDescriptor? = null
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var currentConfig: Config? = null
    @Volatile private var currentResolver: ResolverChoice? = null
    private val failedAutoResolvers = linkedSetOf<String>()
    @Volatile private var tunnelActive = false
    @Volatile private var recovering = false
    @Volatile private var stopping = false
    @Volatile private var starting = false
    @Volatile private var resolverOptimizerRunning = false
    private var rxBase = 0L
    private var txBase = 0L
    private var lastRx = 0L
    private var lastTx = 0L
    private var lastTunRx = 0L
    private var lastTunTx = 0L
    private var lastBridgeRx = 0L
    private var lastBridgeTx = 0L
    private var lastBridgeFailures = 0L
    private var bridgeFailureWatchStartAt = 0L
    private var bridgeFailureWatchBase = 0L
    private var lastProgressAt = 0L
    private var lastHeavyUploadAt = 0L
    private var slowResponseSince = 0L
    private var lowBandwidthSince = 0L
    private var readyFalseSince = 0L
    private var lastRecoveryAt = 0L
    private var recoveryCount = 0
    @Volatile private var resolverHealthCheckRunning = false
    private var lastResolverHealthCheckAt = 0L
    private var resolverHealthFailures = 0
    private val diagnostics = object : Runnable {
        override fun run() {
            writeDiagnostics()
            handler.postDelayed(this, DIAGNOSTICS_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        configureNativeLogging()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTunnel()
            else -> startTunnel()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    private fun startTunnel() {
        configureNativeLogging()
        if (starting || tunnelActive) {
            AppLog.w(TAG, "VPN start ignored starting=$starting active=$tunnelActive")
            return
        }
        starting = true
        startForeground(1, notification("Starting"))
        Thread({
            try {
                startTunnelWorker()
            } finally {
                starting = false
            }
        }, "vpn-start").start()
    }

    private fun startTunnelWorker() {
        val config = normalizeAutoConfig(ConfigStore.load(this))
        currentConfig = config
        tunnelActive = true
        AppLog.i(TAG, "VPN start config=${config.copy(password = if (config.password.isBlank()) "" else "***")}")
        try {
            require(config.domain.isNotBlank()) { "domain is empty" }
            if (config.resolverMode == Config.ResolverMode.MANUAL) {
                require(config.resolverHost.isNotBlank()) { "resolver is empty" }
            }
            SlipstreamBridge.setVpnService(this)
            SlipstreamBridge.proxyOnlyMode = false
            resetTrafficBase()
            failedAutoResolvers.clear()
            val choice = ResolverSelector.chooseFast(this, config, "vpn_start")
            currentResolver = choice
            val bridgePort = config.listenPort
            val slipstreamPort = config.listenPort + 1
            SlipstreamBridge.startClient(
                config.domain,
                ResolverListConfig(choice.hosts, choice.port, true),
                slipstreamPort,
                choice.qnameMtu
            ).getOrThrow()
            require(waitForSlipstreamReady(START_READY_TIMEOUT_MS)) {
                "slipstream not ready after ${START_READY_TIMEOUT_MS}ms resolver=${choice.selectedHost}:${choice.port}"
            }
            MiniSlipstreamSocksBridge.start(
                listenHost = "127.0.0.1",
                listenPort = bridgePort,
                slipstreamHost = "127.0.0.1",
                slipstreamPort = slipstreamPort,
                dnsHost = choice.selectedHost,
                username = if (config.authMode == Config.AuthMode.LOGIN_PASSWORD) config.username else null,
                password = if (config.authMode == Config.AuthMode.LOGIN_PASSWORD) config.password else null
            ).getOrThrow()

            val builder = Builder()
                .setSession("Slipstream CLI")
                .setMtu(1280)
                .addAddress("10.255.0.2", 32)
                .addAddress("fd00::2", 128)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer(choice.selectedHost)
            runCatching { builder.addDisallowedApplication(packageName) }
                .onFailure { AppLog.w(TAG, "addDisallowedApplication failed: ${it.message}") }
            tunFd = builder.establish() ?: error("VpnService.Builder.establish returned null")

            HevSocks5Tunnel.start(
                tunFd = tunFd ?: error("TUN fd is null"),
                socksAddress = "127.0.0.1",
                socksPort = bridgePort,
                username = null,
                password = null
            ).getOrThrow()
            startForeground(1, notification("Connected"))
            AppLog.i(
                TAG,
                "VPN connected resolver=${choice.selectedHost}:${choice.port} source=${choice.source} " +
                    "qnameMtu=${if (choice.qnameMtu > 0) choice.qnameMtu else "max"} " +
                    "tested=${choice.testedCount} alive=${choice.aliveCount} skipped=${choice.skippedCount}"
            )
            handler.removeCallbacks(diagnostics)
            handler.post(diagnostics)
            startBackgroundResolverOptimization(config, choice)
        } catch (e: Throwable) {
            AppLog.e(TAG, "VPN start failed", e)
            runCatching { HevSocks5Tunnel.stop() }
            runCatching { MiniSlipstreamSocksBridge.stop() }
            runCatching { SlipstreamBridge.stopClient() }
            runCatching { tunFd?.close() }
            tunFd = null
            SlipstreamBridge.setVpnService(null)
            tunnelActive = false
            stopTunnel()
        }
    }

    private fun normalizeAutoConfig(config: Config): Config {
        if (config.resolverMode != Config.ResolverMode.AUTO) return config
        val local = ResolverSelector.preferredLocalResolver(this).orEmpty()
        if (local.isBlank() || local == config.resolverHost) return config
        AppLog.i(TAG, "AUTO resolverHost refreshed from active network old=${config.resolverHost.ifBlank { "-" }} new=$local")
        return config.copy(resolverHost = local)
    }

    private fun stopTunnel() {
        if (
            !tunnelActive &&
            tunFd == null &&
            !HevSocks5Tunnel.isRunning() &&
            !MiniSlipstreamSocksBridge.isRunning() &&
            !SlipstreamBridge.isRunning()
        ) {
            AppLog.i(TAG, "VPN already stopped")
            return
        }
        if (stopping) {
            AppLog.w(TAG, "VPN stop already running")
            return
        }
        stopping = true
        AppLog.i(TAG, "VPN stop")
        tunnelActive = false
        currentConfig = null
        currentResolver = null
        resolverOptimizerRunning = false
        failedAutoResolvers.clear()
        readyFalseSince = 0
        handler.removeCallbacks(diagnostics)
        Thread({
            try {
                runCatching { HevSocks5Tunnel.stop() }
                    .onFailure { AppLog.w(TAG, "hev stop failed: ${it.message}") }
                runCatching { MiniSlipstreamSocksBridge.stop() }
                    .onFailure { AppLog.w(TAG, "bridge stop failed: ${it.message}") }
                runCatching { tunFd?.close() }
                    .onFailure { AppLog.w(TAG, "tun close failed: ${it.message}") }
                tunFd = null
                runCatching { SlipstreamBridge.stopClient() }
                    .onFailure { AppLog.w(TAG, "native stop failed: ${it.message}") }
                SlipstreamBridge.setVpnService(null)
                AppLog.i(TAG, "VPN stop cleanup finished")
            } finally {
                handler.post {
                    stopping = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }, "vpn-stop-cleanup").start()
    }

    private fun resetTrafficBase() {
        val uid = applicationInfo.uid
        rxBase = TrafficStats.getUidRxBytes(uid).takeIf { it >= 0 } ?: 0
        txBase = TrafficStats.getUidTxBytes(uid).takeIf { it >= 0 } ?: 0
        lastRx = 0
        lastTx = 0
        lastTunRx = 0
        lastTunTx = 0
        lastBridgeRx = 0
        lastBridgeTx = 0
        lastBridgeFailures = 0
        bridgeFailureWatchStartAt = 0
        bridgeFailureWatchBase = 0
        lastProgressAt = System.currentTimeMillis()
        lastHeavyUploadAt = 0
        slowResponseSince = 0
        lowBandwidthSince = 0
        lastResolverHealthCheckAt = 0
        resolverHealthFailures = 0
    }

    private fun writeDiagnostics() {
        val uid = applicationInfo.uid
        val rx = (TrafficStats.getUidRxBytes(uid).takeIf { it >= 0 } ?: 0) - rxBase
        val tx = (TrafficStats.getUidTxBytes(uid).takeIf { it >= 0 } ?: 0) - txBase
        val hev = HevSocks5Tunnel.stats()
        val bridge = MiniSlipstreamSocksBridge.stats()
        val running = SlipstreamBridge.isRunning()
        val ready = SlipstreamBridge.isReady()
        val now = System.currentTimeMillis()
        if (running && !ready) {
            if (readyFalseSince == 0L) readyFalseSince = now
        } else {
            readyFalseSince = 0
        }
        val appRxDelta = (rx - lastRx).coerceAtLeast(0)
        val appTxDelta = (tx - lastTx).coerceAtLeast(0)
        val appProgressed = appRxDelta > 0 || appTxDelta > 0
        val requestBytesDelta = (hev.txBytes - lastTunTx).coerceAtLeast(0)
        val responseBytesDelta = (hev.rxBytes - lastTunRx).coerceAtLeast(0) + (bridge.rxBytes - lastBridgeRx).coerceAtLeast(0)
        val bridgeTxDelta = (bridge.txBytes - lastBridgeTx).coerceAtLeast(0)
        val failureTotal = bridge.connectFail + bridge.dnsFail
        val failureDelta = (failureTotal - lastBridgeFailures).coerceAtLeast(0)
        if (bridgeTxDelta >= HEAVY_UPLOAD_DELTA_BYTES) {
            lastHeavyUploadAt = now
        }
        val uploadGraceActive = lastHeavyUploadAt != 0L && now - lastHeavyUploadAt <= HEAVY_UPLOAD_GRACE_MS
        val usefulProgressed = responseBytesDelta > 0
        if (usefulProgressed) {
            lastProgressAt = now
        }
        val activeBridge = bridge.activeClients > 0 || bridge.clientSockets > 0 || bridge.remoteSockets > 0
        val outboundPressure = requestBytesDelta >= SLOW_RESPONSE_REQUEST_DELTA_BYTES ||
            bridgeTxDelta >= SLOW_RESPONSE_BRIDGE_TX_DELTA_BYTES ||
            appTxDelta >= SLOW_RESPONSE_APP_TX_DELTA_BYTES
        val slowResponse = running && ready && activeBridge && outboundPressure &&
            responseBytesDelta in 1 until SLOW_RESPONSE_MAX_DELTA_BYTES
        val lowBandwidth = running && ready && activeBridge &&
            (outboundPressure || bridge.activeClients >= LOW_BANDWIDTH_ACTIVE_CLIENTS) &&
            responseBytesDelta in SLOW_RESPONSE_MAX_DELTA_BYTES until LOW_BANDWIDTH_MAX_DELTA_BYTES
        if (slowResponse) {
            if (slowResponseSince == 0L) slowResponseSince = now
        } else if (!activeBridge || responseBytesDelta >= SLOW_RESPONSE_CLEAR_DELTA_BYTES || responseBytesDelta == 0L) {
            slowResponseSince = 0
        }
        if (lowBandwidth) {
            if (lowBandwidthSince == 0L) lowBandwidthSince = now
        } else if (!activeBridge || responseBytesDelta >= LOW_BANDWIDTH_CLEAR_DELTA_BYTES || responseBytesDelta == 0L) {
            lowBandwidthSince = 0
        }
        if (appProgressed || requestBytesDelta > 0 || bridgeTxDelta > 0 || usefulProgressed || failureDelta > 0) {
            lastRx = rx
            lastTx = tx
            lastTunRx = hev.rxBytes
            lastTunTx = hev.txBytes
            lastBridgeRx = bridge.rxBytes
            lastBridgeTx = bridge.txBytes
            lastBridgeFailures = failureTotal
        }
        updateBridgeFailureWatch(now, running, ready, failureTotal)
        AppLog.i(
            TAG,
            "diag running=$running ready=$ready hevRunning=${HevSocks5Tunnel.isRunning()} " +
                "port=${SlipstreamBridge.port()} appRx=$rx appTx=$tx " +
                "tunRx=${hev.rxBytes} tunTx=${hev.txBytes} " +
                "bridgeRx=${bridge.rxBytes} bridgeTx=${bridge.txBytes} " +
                "connectOk=${bridge.connectOk} connectFail=${bridge.connectFail} dnsOk=${bridge.dnsOk} dnsFail=${bridge.dnsFail} " +
                "bridgeActive=${bridge.activeClients} bridgeClients=${bridge.clientSockets} bridgeRemotes=${bridge.remoteSockets} bridgeThreads=${bridge.threads} " +
                "resolver=${currentResolver?.selectedHost ?: "none"}:${currentResolver?.port ?: 0} " +
                "qnameMtu=${currentResolver?.qnameMtu?.takeIf { it > 0 } ?: "max"} " +
                "dnsTested=${currentResolver?.testedCount ?: 0} dnsAlive=${currentResolver?.aliveCount ?: 0} " +
                "dnsSkipped=${currentResolver?.skippedCount ?: 0} " +
                "deltaReq=$requestBytesDelta deltaResp=$responseBytesDelta deltaBridgeTx=$bridgeTxDelta deltaFail=$failureDelta " +
                "lastProgressMs=${now - lastProgressAt} readyFalseMs=${if (readyFalseSince == 0L) 0 else now - readyFalseSince} " +
                "slowResponseMs=${if (slowResponseSince == 0L) 0 else now - slowResponseSince} " +
                "lowBandwidthMs=${if (lowBandwidthSince == 0L) 0 else now - lowBandwidthSince} " +
                "heavyUploadAgoMs=${if (lastHeavyUploadAt == 0L) -1 else now - lastHeavyUploadAt} " +
                "recovering=$recovering recoveries=$recoveryCount lastError=${SlipstreamBridge.lastError() ?: "none"}"
        )
        maybeCheckResolverHealth(now, running, ready)
        if (slowResponse && now - slowResponseSince > SLOW_RESPONSE_RECOVERY_MS) {
            AppLog.w(
                TAG,
                "traffic slow-response for ${now - slowResponseSince}ms while native ready " +
                    "requestDelta=$requestBytesDelta responseDelta=$responseBytesDelta bridgeTxDelta=$bridgeTxDelta " +
                    "appTxDelta=$appTxDelta uploadGrace=$uploadGraceActive"
            )
        }
        if (slowResponse && lowBandwidthSince != 0L && now - lowBandwidthSince > LOW_BANDWIDTH_RECOVERY_MS) {
            AppLog.w(
                TAG,
                "traffic low-bandwidth degraded into slow-response for ${now - lowBandwidthSince}ms " +
                    "requestDelta=$requestBytesDelta responseDelta=$responseBytesDelta bridgeTxDelta=$bridgeTxDelta " +
                    "active=${bridge.activeClients} uploadGrace=$uploadGraceActive"
            )
        }
        if (lowBandwidth && now - lowBandwidthSince > LOW_BANDWIDTH_RECOVERY_MS) {
            AppLog.w(
                TAG,
                "traffic low-bandwidth for ${now - lowBandwidthSince}ms while native ready " +
                    "requestDelta=$requestBytesDelta responseDelta=$responseBytesDelta bridgeTxDelta=$bridgeTxDelta " +
                    "active=${bridge.activeClients} uploadGrace=$uploadGraceActive"
            )
        }
        if (running && ready && failureDelta >= FAILURE_STORM_RECOVERY_DELTA) {
            AppLog.w(
                TAG,
                "bridge failure storm delta=$failureDelta active=${bridge.activeClients} " +
                    "requestDelta=$requestBytesDelta responseDelta=$responseBytesDelta uploadGrace=$uploadGraceActive"
            )
            if (now - lastRecoveryAt > FAILURE_STORM_RECOVERY_COOLDOWN_MS) {
                restartSlipstreamPath("bridge_failure_storm_${failureDelta}")
            }
        }
        if (running && ready && !uploadGraceActive && requestBytesDelta > 8192 && responseBytesDelta == 0L && now - lastProgressAt > TRAFFIC_RECOVERY_MS) {
            AppLog.w(TAG, "traffic stalled for ${now - lastProgressAt}ms while native ready requestDelta=$requestBytesDelta responseDelta=$responseBytesDelta")
            if (now - lastRecoveryAt > RECOVERY_COOLDOWN_MS) {
                restartSlipstreamPath("traffic_no_response_${now - lastProgressAt}ms")
            }
        }
        if (
            running && ready && appProgressed &&
            requestBytesDelta == 0L && responseBytesDelta == 0L && bridgeTxDelta == 0L &&
            now - lastProgressAt > TRANSPORT_SILENCE_RECOVERY_MS
        ) {
            AppLog.w(
                TAG,
                "transport silent for ${now - lastProgressAt}ms while app traffic changed " +
                    "appRxDelta=$appRxDelta appTxDelta=$appTxDelta"
            )
        }
        if (running && ready && bridge.connectOk == 0L && failureTotal >= STARTUP_FAILURE_RECOVERY_TOTAL) {
            AppLog.w(
                TAG,
                "startup bridge failures before first successful CONNECT: total=$failureTotal delta=$failureDelta " +
                    "requestDelta=$requestBytesDelta responseDelta=$responseBytesDelta"
            )
            if (now - lastRecoveryAt > STARTUP_RECOVERY_COOLDOWN_MS) {
                restartSlipstreamPath("bridge_failures_startup_${failureTotal}")
            }
        }
        if (running && ready && failureDelta >= FAILURE_RECOVERY_DELTA && now - lastProgressAt > TRAFFIC_RECOVERY_MS) {
            AppLog.w(TAG, "bridge failures increased by $failureDelta without useful response for ${now - lastProgressAt}ms")
            if (now - lastRecoveryAt > RECOVERY_COOLDOWN_MS) {
                restartSlipstreamPath("bridge_failures_${failureDelta}_no_response")
            }
        }
        if (running && !ready && readyFalseSince != 0L) {
            val downFor = now - readyFalseSince
            val afterHeavyUpload = lastHeavyUploadAt != 0L && now - lastHeavyUploadAt < HEAVY_UPLOAD_GRACE_MS
            val threshold = if (afterHeavyUpload) READY_RECOVERY_AFTER_UPLOAD_MS else READY_RECOVERY_MS
            AppLog.w(TAG, "native client not ready for ${downFor}ms threshold=${threshold}ms afterHeavyUpload=$afterHeavyUpload")
            if (downFor > threshold && now - lastRecoveryAt > RECOVERY_COOLDOWN_MS) {
                restartSlipstreamPath("native_not_ready_${downFor}ms")
            }
        }
        if (!running && HevSocks5Tunnel.isRunning()) {
            AppLog.e(TAG, "tun2socks is running but Slipstream native client is down")
            runCatching { MiniSlipstreamSocksBridge.stop() }
                .onFailure { AppLog.w(TAG, "bridge emergency stop failed after native down: ${it.message}") }
            if (now - lastRecoveryAt > RECOVERY_COOLDOWN_MS) {
                restartSlipstreamPath("native_not_running")
            }
        }
    }

    private fun updateBridgeFailureWatch(now: Long, running: Boolean, ready: Boolean, failureTotal: Long) {
        if (!running || !ready || recovering) {
            bridgeFailureWatchStartAt = 0
            bridgeFailureWatchBase = failureTotal
            return
        }
        if (failureTotal <= bridgeFailureWatchBase) {
            bridgeFailureWatchStartAt = 0
            bridgeFailureWatchBase = failureTotal
            return
        }
        if (bridgeFailureWatchStartAt == 0L) {
            bridgeFailureWatchStartAt = now
            bridgeFailureWatchBase = (failureTotal - 1).coerceAtLeast(0)
            return
        }
        val accumulated = failureTotal - bridgeFailureWatchBase
        if (
            accumulated >= ACCUMULATED_FAILURE_RECOVERY_TOTAL &&
            now - bridgeFailureWatchStartAt >= ACCUMULATED_FAILURE_RECOVERY_WINDOW_MS &&
            now - lastRecoveryAt > RECOVERY_COOLDOWN_MS
        ) {
            AppLog.w(
                TAG,
                "bridge accumulated failures total=$failureTotal accumulated=$accumulated " +
                    "windowMs=${now - bridgeFailureWatchStartAt}; restarting path"
            )
            bridgeFailureWatchStartAt = 0
            bridgeFailureWatchBase = failureTotal
            restartSlipstreamPath("bridge_failures_accumulated_${accumulated}")
        }
    }

    private fun maybeCheckResolverHealth(now: Long, running: Boolean, ready: Boolean) {
        val config = currentConfig ?: return
        val resolver = currentResolver ?: return
        if (config.resolverMode != Config.ResolverMode.AUTO) return
        if (!tunnelActive || !running || !ready || recovering || resolverHealthCheckRunning) return
        if (now - lastResolverHealthCheckAt < RESOLVER_HEALTH_CHECK_INTERVAL_MS) return
        val host = resolver.selectedHost.takeIf { it.isNotBlank() } ?: return
        val port = resolver.port
        resolverHealthCheckRunning = true
        lastResolverHealthCheckAt = now
        Thread({
            val ok = ResolverSelector.isResolverReachable(host, port)
            if (ok) {
                if (resolverHealthFailures != 0) {
                    AppLog.i(TAG, "resolver health restored host=$host:$port failures=$resolverHealthFailures")
                }
                resolverHealthFailures = 0
            } else {
                resolverHealthFailures += 1
                AppLog.w(
                    TAG,
                    "resolver health failed host=$host:$port failures=$resolverHealthFailures/$RESOLVER_HEALTH_FAILURES_BEFORE_ROTATE"
                )
                if (
                    resolverHealthFailures >= RESOLVER_HEALTH_FAILURES_BEFORE_ROTATE &&
                    tunnelActive &&
                    !recovering &&
                    System.currentTimeMillis() - lastRecoveryAt > RESOLVER_HEALTH_RECOVERY_COOLDOWN_MS
                ) {
                    restartSlipstreamPath("resolver_unreachable_${host}_${resolverHealthFailures}")
                }
            }
            resolverHealthCheckRunning = false
        }, "resolver-health").start()
    }

    private fun restartSlipstreamPath(reason: String) {
        restartSlipstreamPath(reason, forcedChoice = null)
    }

    private fun configureNativeLogging() {
        val path = if (AppLog.isFileLoggingEnabled(this)) AppLog.file(this).absolutePath else ""
        SlipstreamBridge.setLogFilePath(path)
    }

    private fun restartSlipstreamPath(reason: String, forcedChoice: ResolverChoice?) {
        val config = currentConfig ?: run {
            AppLog.e(TAG, "recovery skipped: config is missing reason=$reason")
            return
        }
        if (!tunnelActive) {
            AppLog.w(TAG, "recovery skipped: tunnel is not active reason=$reason")
            return
        }
        if (recovering) {
            AppLog.w(TAG, "recovery already running reason=$reason")
            return
        }
        recovering = true
        lastRecoveryAt = System.currentTimeMillis()
        recoveryCount += 1
        val recoveryId = recoveryCount
        AppLog.w(TAG, "recovery#$recoveryId start reason=$reason")
        Thread({
            val previousChoice = currentResolver
            try {
                val bridgePort = config.listenPort
                val slipstreamPort = config.listenPort + 1
                val lastNativeError = SlipstreamBridge.lastError().orEmpty()
                val isNativeNoProgress = reason == "native_not_running" &&
                    lastNativeError.startsWith("native no-progress")
                val fastPathRecovery = reason.startsWith("traffic_no_response") ||
                    reason.startsWith("traffic_slow_response") ||
                    reason.startsWith("traffic_low_bandwidth") ||
                    reason.startsWith("resolver_speed_upgrade") ||
                    reason.startsWith("bridge_failures")
                val failureStormRecovery = reason.startsWith("bridge_failure_storm")
                val resolverUnreachableRecovery = reason.startsWith("resolver_unreachable")
                val trafficRecovery = reason.startsWith("traffic_no_response") ||
                    resolverUnreachableRecovery
                val bridgeFailureRecovery = reason.startsWith("bridge_failures")
                    || failureStormRecovery
                val nativeNotReadyRecovery = reason.startsWith("native_not_ready")
                val nativeDownFastRecovery = reason == "native_not_running" &&
                    config.resolverMode == Config.ResolverMode.AUTO &&
                    currentResolver != null
                val autoFastRecovery = config.resolverMode == Config.ResolverMode.AUTO &&
                    currentResolver != null &&
                    !failureStormRecovery &&
                    (nativeDownFastRecovery || nativeNotReadyRecovery)
                val reuseCurrentResolver = isNativeNoProgress ||
                    (fastPathRecovery && !trafficRecovery && !bridgeFailureRecovery)
                val rotateResolver = (reason == "native_not_running" && !isNativeNoProgress) ||
                    trafficRecovery ||
                    bridgeFailureRecovery ||
                    nativeNotReadyRecovery ||
                    resolverUnreachableRecovery
                if (isNativeNoProgress) {
                    AppLog.w(TAG, "recovery#$recoveryId native no-progress; reusing current resolver without auto DNS probe: $lastNativeError")
                }
                runCatching { MiniSlipstreamSocksBridge.stop() }
                    .onFailure { AppLog.w(TAG, "recovery#$recoveryId bridge early stop failed: ${it.message}") }
                runCatching { SlipstreamBridge.stopClient() }
                    .onFailure { AppLog.w(TAG, "recovery#$recoveryId native early stop failed: ${it.message}") }
                currentResolver?.selectedHost
                    ?.takeIf { rotateResolver && config.resolverMode == Config.ResolverMode.AUTO && it.isNotBlank() }
                    ?.let {
                        failedAutoResolvers += it
                        AppLog.w(TAG, "recovery#$recoveryId mark resolver bad for this session: $it reason=$reason")
                    }
                if (!rotateResolver && failedAutoResolvers.isNotEmpty()) {
                    AppLog.w(TAG, "recovery#$recoveryId keep resolver sticky for reason=$reason; clearing failed auto resolvers=${failedAutoResolvers.joinToString()}")
                    failedAutoResolvers.clear()
                }
                val quickChoice = if (forcedChoice == null && autoFastRecovery) quickAutoRecoveryChoice(config, recoveryId, reason) else null
                val choice = forcedChoice ?: quickChoice ?: if (reuseCurrentResolver && currentResolver != null) {
                    AppLog.w(TAG, "recovery#$recoveryId reusing resolver ${currentResolver!!.selectedHost}:${currentResolver!!.port} reason=$reason")
                    currentResolver!!
                } else {
                    ResolverSelector.choose(
                        this,
                        config,
                        "recovery#$recoveryId:$reason",
                        failedAutoResolvers
                    )
                }
                currentResolver = choice
                Thread.sleep(if (fastPathRecovery || nativeDownFastRecovery) 150 else 750)
                if (!tunnelActive) {
                    AppLog.w(TAG, "recovery#$recoveryId cancelled: tunnel stopped")
                    return@Thread
                }
                SlipstreamBridge.setVpnService(this)
                SlipstreamBridge.proxyOnlyMode = false
                SlipstreamBridge.startClient(
                    config.domain,
                    ResolverListConfig(choice.hosts, choice.port, true),
                    slipstreamPort,
                    choice.qnameMtu
                ).getOrThrow()
                if (!waitForSlipstreamReady(RECOVERY_READY_TIMEOUT_MS)) {
                    runCatching { SlipstreamBridge.stopClient() }
                    if (config.resolverMode == Config.ResolverMode.AUTO && choice.selectedHost.isNotBlank()) {
                        failedAutoResolvers += choice.selectedHost
                        AppLog.w(
                            TAG,
                            "recovery#$recoveryId resolver not ready; marked bad: " +
                                "${choice.selectedHost}:${choice.port}"
                        )
                    }
                    throw IllegalStateException(
                        "slipstream not ready after ${RECOVERY_READY_TIMEOUT_MS}ms " +
                            "resolver=${choice.selectedHost}:${choice.port}"
                    )
                }
                MiniSlipstreamSocksBridge.start(
                    listenHost = "127.0.0.1",
                    listenPort = bridgePort,
                    slipstreamHost = "127.0.0.1",
                    slipstreamPort = slipstreamPort,
                    dnsHost = choice.selectedHost,
                    username = if (config.authMode == Config.AuthMode.LOGIN_PASSWORD) config.username else null,
                    password = if (config.authMode == Config.AuthMode.LOGIN_PASSWORD) config.password else null
                ).getOrThrow()
                readyFalseSince = 0
                slowResponseSince = 0
                lowBandwidthSince = 0
                resolverHealthFailures = 0
                lastResolverHealthCheckAt = System.currentTimeMillis()
                lastProgressAt = System.currentTimeMillis()
                AppLog.w(
                    TAG,
                    "recovery#$recoveryId done ready=${SlipstreamBridge.isReady()} port=${SlipstreamBridge.port()} " +
                        "resolver=${choice.selectedHost}:${choice.port} tested=${choice.testedCount} " +
                        "qnameMtu=${if (choice.qnameMtu > 0) choice.qnameMtu else "max"} " +
                        "alive=${choice.aliveCount} skipped=${choice.skippedCount}"
                )
            } catch (e: Throwable) {
                AppLog.e(TAG, "recovery#$recoveryId failed", e)
                if (forcedChoice != null && previousChoice != null && tunnelActive) {
                    restoreResolverAfterFailedUpgrade(config, previousChoice, recoveryId)
                }
            } finally {
                recovering = false
            }
        }, "slipstream-recovery").start()
    }

    private fun restoreResolverAfterFailedUpgrade(config: Config, choice: ResolverChoice, recoveryId: Int) {
        runCatching {
            val bridgePort = config.listenPort
            val slipstreamPort = config.listenPort + 1
            AppLog.w(
                TAG,
                "recovery#$recoveryId restoring previous resolver " +
                    "${choice.selectedHost}:${choice.port} after failed speed upgrade"
            )
            runCatching { MiniSlipstreamSocksBridge.stop() }
            runCatching { SlipstreamBridge.stopClient() }
            SlipstreamBridge.setVpnService(this)
            SlipstreamBridge.proxyOnlyMode = false
            SlipstreamBridge.startClient(
                config.domain,
                ResolverListConfig(choice.hosts, choice.port, true),
                slipstreamPort,
                choice.qnameMtu
            ).getOrThrow()
            if (!waitForSlipstreamReady(RECOVERY_READY_TIMEOUT_MS)) {
                throw IllegalStateException(
                    "previous resolver not ready after failed speed upgrade: " +
                        "${choice.selectedHost}:${choice.port}"
                )
            }
            MiniSlipstreamSocksBridge.start(
                listenHost = "127.0.0.1",
                listenPort = bridgePort,
                slipstreamHost = "127.0.0.1",
                slipstreamPort = slipstreamPort,
                dnsHost = choice.selectedHost,
                username = if (config.authMode == Config.AuthMode.LOGIN_PASSWORD) config.username else null,
                password = if (config.authMode == Config.AuthMode.LOGIN_PASSWORD) config.password else null
            ).getOrThrow()
            currentResolver = choice
            readyFalseSince = 0
            slowResponseSince = 0
            lowBandwidthSince = 0
            resolverHealthFailures = 0
            lastResolverHealthCheckAt = System.currentTimeMillis()
            lastProgressAt = System.currentTimeMillis()
            AppLog.w(
                TAG,
                "recovery#$recoveryId restored previous resolver ${choice.selectedHost}:${choice.port} " +
                    "ready=${SlipstreamBridge.isReady()}"
            )
        }.onFailure {
            AppLog.e(TAG, "recovery#$recoveryId failed to restore previous resolver", it)
        }
    }

    private fun startBackgroundResolverOptimization(config: Config, initialChoice: ResolverChoice) {
        if (config.resolverMode != Config.ResolverMode.AUTO) return
        if (resolverOptimizerRunning) return
        resolverOptimizerRunning = true
        Thread({
            try {
                Thread.sleep(BACKGROUND_RESOLVER_PROBE_DELAY_MS)
                if (!tunnelActive || !SlipstreamBridge.isRunning() || recovering) return@Thread
                AppLog.i(TAG, "background resolver download probe start current=${initialChoice.selectedHost}:${initialChoice.port}")
                val best = ResolverSelector.chooseFastestByDownload(
                    this,
                    config,
                    "background_speed",
                    failedAutoResolvers
                ) ?: run {
                    AppLog.w(TAG, "background resolver speed probe found no usable resolver")
                    return@Thread
                }
                val current = currentResolver
                if (!tunnelActive || current == null) return@Thread
                if (best.selectedHost == current.selectedHost && best.port == current.port) {
                    AppLog.i(
                        TAG,
                        "background resolver download probe kept current=${current.selectedHost}:${current.port} " +
                            "totalMs=${best.latencyMs}"
                    )
                    return@Thread
                }
                if (recovering || System.currentTimeMillis() - lastRecoveryAt <= BACKGROUND_RESOLVER_SWITCH_COOLDOWN_MS) {
                    AppLog.w(TAG, "background resolver switch skipped: recovery busy/cooling down best=${best.selectedHost}:${best.port}")
                    return@Thread
                }
                AppLog.w(
                    TAG,
                    "background resolver download upgrade ${current.selectedHost}:${current.port} -> " +
                        "${best.selectedHost}:${best.port} totalMs=${best.latencyMs}"
                )
                restartSlipstreamPath("resolver_speed_upgrade_${best.selectedHost}_${best.latencyMs}ms", best)
            } catch (e: Throwable) {
                AppLog.e(TAG, "background resolver download probe failed", e)
            } finally {
                resolverOptimizerRunning = false
            }
        }, "resolver-speed-optimizer").start()
    }

    private fun quickAutoRecoveryChoice(config: Config, recoveryId: Int, reason: String): ResolverChoice? {
        if (config.resolverMode != Config.ResolverMode.AUTO) return null
        val current = currentResolver
        val currentHost = current?.selectedHost.orEmpty()
        val local = ResolverSelector.localDefaultResolvers(this)
        val preferred = buildList {
            local.forEach { host ->
                if (host != currentHost && host !in failedAutoResolvers) add(host)
            }
            current?.hosts.orEmpty().forEach { host ->
                if (host !in failedAutoResolvers) add(host)
            }
            if (currentHost.isNotBlank() && currentHost !in failedAutoResolvers) add(currentHost)
        }.map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        if (preferred.isEmpty() && failedAutoResolvers.isNotEmpty()) {
            AppLog.w(
                TAG,
                "recovery#$recoveryId auto-fast exhausted resolver candidates; clearing failed set: " +
                    failedAutoResolvers.joinToString()
            )
            failedAutoResolvers.clear()
        }

        val ordered = buildList {
            preferred.forEach { add(it) }
            local.forEach { host ->
                add(host)
            }
            current?.hosts.orEmpty().forEach { add(it) }
            if (currentHost.isNotBlank()) add(currentHost)
        }.map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(8)
        val selected = ordered.firstOrNull() ?: return null
        AppLog.w(
            TAG,
            "recovery#$recoveryId auto-fast resolver selected=$selected:${config.resolverPort} " +
                "hosts=${ordered.joinToString()} local=${local.joinToString()} current=${currentHost.ifBlank { "-" }} " +
                "failed=${failedAutoResolvers.joinToString()} reason=$reason"
        )
        return ResolverChoice(
            hosts = ordered,
            port = config.resolverPort,
            selectedHost = selected,
            source = "auto-fast",
            qnameMtu = current?.qnameMtu ?: 0,
            testedCount = 0,
            aliveCount = ordered.size,
            skippedCount = failedAutoResolvers.size
        )
    }

    private fun waitForSlipstreamReady(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!tunnelActive || !SlipstreamBridge.isRunning()) return false
            if (SlipstreamBridge.isReady()) return true
            Thread.sleep(100)
        }
        return SlipstreamBridge.isReady()
    }

    private fun notification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Slipstream CLI")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_vaydns)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(NotificationChannel(CHANNEL, "Slipstream CLI", NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object {
        const val ACTION_START = "app.vaydns.START"
        const val ACTION_STOP = "app.vaydns.STOP"
        private const val CHANNEL = "vaydns"
        private const val TAG = "TinyVpnService"
        private const val DIAGNOSTICS_INTERVAL_MS = 5_000L
        private const val READY_RECOVERY_MS = 3_000L
        private const val READY_RECOVERY_AFTER_UPLOAD_MS = 3_000L
        private const val TRAFFIC_RECOVERY_MS = 15_000L
        private const val TRANSPORT_SILENCE_RECOVERY_MS = 8_000L
        private const val SLOW_RESPONSE_RECOVERY_MS = 12_000L
        private const val SLOW_RESPONSE_MAX_DELTA_BYTES = 2L * 1024L
        private const val SLOW_RESPONSE_CLEAR_DELTA_BYTES = 16L * 1024L
        private const val SLOW_RESPONSE_REQUEST_DELTA_BYTES = 1024L
        private const val SLOW_RESPONSE_BRIDGE_TX_DELTA_BYTES = 1024L
        private const val SLOW_RESPONSE_APP_TX_DELTA_BYTES = 4L * 1024L
        private const val LOW_BANDWIDTH_RECOVERY_MS = 45_000L
        private const val LOW_BANDWIDTH_MAX_DELTA_BYTES = 8L * 1024L
        private const val LOW_BANDWIDTH_CLEAR_DELTA_BYTES = 32L * 1024L
        private const val LOW_BANDWIDTH_ACTIVE_CLIENTS = 10
        private const val RECOVERY_COOLDOWN_MS = 5_000L
        private const val FAILURE_RECOVERY_DELTA = 8L
        private const val FAILURE_STORM_RECOVERY_DELTA = 12L
        private const val FAILURE_STORM_RECOVERY_COOLDOWN_MS = 3_000L
        private const val STARTUP_FAILURE_RECOVERY_TOTAL = 6L
        private const val STARTUP_RECOVERY_COOLDOWN_MS = 2_000L
        private const val RESOLVER_HEALTH_CHECK_INTERVAL_MS = 15_000L
        private const val RESOLVER_HEALTH_FAILURES_BEFORE_ROTATE = 2
        private const val RESOLVER_HEALTH_RECOVERY_COOLDOWN_MS = 5_000L
        private const val ACCUMULATED_FAILURE_RECOVERY_TOTAL = 24L
        private const val ACCUMULATED_FAILURE_RECOVERY_WINDOW_MS = 20_000L
        private const val HEAVY_UPLOAD_DELTA_BYTES = 256L * 1024L
        private const val HEAVY_UPLOAD_GRACE_MS = 120_000L
        private const val BACKGROUND_RESOLVER_PROBE_DELAY_MS = 1_500L
        private const val BACKGROUND_RESOLVER_SWITCH_COOLDOWN_MS = 2_000L
        private const val START_READY_TIMEOUT_MS = 8_000L
        private const val RECOVERY_READY_TIMEOUT_MS = 5_000L
    }
}
