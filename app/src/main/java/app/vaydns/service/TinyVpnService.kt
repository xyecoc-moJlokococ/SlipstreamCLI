package app.vaydns.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
    @Volatile private var lifecycleGeneration = 0
    @Volatile private var resolverOptimizerRunning = false
    private var rxBase = 0L
    private var txBase = 0L
    private var notificationRxLast = 0L
    private var notificationTxLast = 0L
    private var notificationSampleAt = 0L
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
    private var lastTransportSwitchAt = 0L
    private var transportSwitchesThisEpisode = 0
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastNetworkSignature = ""
    private var pendingNetworkRecheck: Runnable? = null
    private var readyFalseSince = 0L
    private var lastRecoveryAt = 0L
    private var recoveryCount = 0
    private var recoveryRetryPending: Runnable? = null
    private var recoveryRetryBackoffMs = RECOVERY_RETRY_BASE_MS
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
        val generation = ++lifecycleGeneration
        stopping = false
        starting = true
        if (ConfigStore.loadGlobalSettings(this).trafficNotification) {
            startForeground(1, notification("Starting", "↓ 0 B (0 B/s)   ↑ 0 B (0 B/s)"))
        }
        Thread({
            try {
                startTunnelWorker(generation)
            } finally {
                if (lifecycleGeneration == generation) {
                    starting = false
                }
            }
        }, "vpn-start").start()
    }

    private fun startTunnelWorker(generation: Int) {
        val config = normalizeAutoConfig(ConfigStore.effectiveConfig(this))
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
            SlipstreamBridge.dnsQueryType = config.dnsQueryType
            SlipstreamBridge.dnsLabelLength = config.dnsLabelLength
            SlipstreamBridge.maxPollQps = config.maxPollQps
            resetTrafficBase()
            failedAutoResolvers.clear()
            var choice = ResolverSelector.chooseFast(this, config, "vpn_start")
            if (!tunnelActive || lifecycleGeneration != generation) error("VPN start cancelled")
            if (ResolverSelector.shouldSkipTransportValidation(config.resolverMode, choice)) {
                // Fresh cache hit with a previously-validated transport+qtype: skip the full real-data
                // probe matrix (up to ~4 sequential 5 KB downloads, the "10-15s on every start" cost)
                // and reuse the known-good combo directly. If the network quietly changed underneath
                // it, startSlipstreamWithTransportFallback below still catches a hard not-ready within
                // START_READY_TIMEOUT_MS and retries the other transport, and the (now much faster)
                // resolver_silent/no-progress detector catches a stalled resolver within seconds --
                // so skipping the defensive re-probe here no longer trades away correctness the way it
                // would have before those fixes.
                SlipstreamBridge.dnsQueryType = choice.qtype
                AppLog.i(
                    TAG,
                    "transport/qtype validation skipped: fresh cache hit host=${choice.selectedHost} " +
                        "transport=${choice.transport.name.lowercase()} qtype=${choice.qtype} reason=vpn_start"
                )
            } else {
                // Validate the DNS carrier transport before committing (light-dns "auto" style): probe the
                // chosen resolver UDP-first with a real 5 KB download and keep UDP only if data actually
                // flows, else fall back to TCP. This catches "UDP handshakes but is throttled" (e.g. Tele2),
                // which the ready check and the cached/guessed transport cannot.
                choice = ResolverSelector.validateTransport(this, config, choice, "vpn_start")
            }
            if (!tunnelActive || lifecycleGeneration != generation) error("VPN start cancelled")
            currentResolver = choice
            val bridgePort = config.listenPort
            var slipstreamPort = config.listenPort + 1
            val localSocks = localSocksCredentials()
            val startResult = startSlipstreamWithTransportFallback(config, choice, slipstreamPort, generation)
            choice = startResult.first
            slipstreamPort = startResult.second
            currentResolver = choice
            ResolverSelector.lastConnectedTransport = choice.transport
            if (!tunnelActive || lifecycleGeneration != generation) error("VPN start cancelled")
            MiniSlipstreamSocksBridge.start(
                listenHost = "127.0.0.1",
                listenPort = bridgePort,
                slipstreamHost = "127.0.0.1",
                slipstreamPort = slipstreamPort,
                dnsHost = choice.selectedHost,
                username = if (config.authMode == Config.AuthMode.LOGIN_PASSWORD) config.username else null,
                password = if (config.authMode == Config.AuthMode.LOGIN_PASSWORD) config.password else null,
                localUsername = localSocks.first,
                localPassword = localSocks.second
            ).getOrThrow()
            if (!tunnelActive || lifecycleGeneration != generation) error("VPN start cancelled")

            val builder = Builder()
                .setSession("Slipstream CLI")
                .setMtu(1500)
                .addAddress("10.255.0.2", 32)
                .addAddress("fd00::2", 128)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer(VPN_DNS_PRIMARY)
                .addDnsServer(VPN_DNS_SECONDARY)
            runCatching { builder.addDisallowedApplication(packageName) }
                .onFailure { AppLog.w(TAG, "addDisallowedApplication failed: ${it.message}") }
            tunFd = builder.establish() ?: error("VpnService.Builder.establish returned null")

            HevSocks5Tunnel.start(
                tunFd = tunFd ?: error("TUN fd is null"),
                socksAddress = "127.0.0.1",
                socksPort = bridgePort,
                username = localSocks.first,
                password = localSocks.second
            ).getOrThrow()
            if (!tunnelActive || lifecycleGeneration != generation) error("VPN start cancelled")
            maybeUpdateTrafficNotification(0, 0, force = true)
            AppLog.i(
                TAG,
                "VPN connected resolver=${choice.selectedHost}:${choice.port} source=${choice.source} " +
                    "transport=${choice.transport.name.lowercase()} " +
                    "pathMode=${config.resolverPathMode.name.lowercase()} " +
                    "qnameMtu=${if (choice.qnameMtu > 0) choice.qnameMtu else "max"} " +
                    "tested=${choice.testedCount} alive=${choice.aliveCount} skipped=${choice.skippedCount}"
            )
            handler.removeCallbacks(diagnostics)
            handler.post(diagnostics)
            startBackgroundResolverOptimization(config, choice)
            registerNetworkChangeWatch()
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

    private fun startSlipstreamWithTransportFallback(
        config: Config,
        choice: ResolverChoice,
        slipstreamPort: Int,
        generation: Int
    ): Pair<ResolverChoice, Int> {
        var port = startSlipstreamClientEscapingWedge(config, choice, slipstreamPort, "vpn_start")
        if (!tunnelActive || lifecycleGeneration != generation) error("VPN start cancelled")
        if (waitForSlipstreamReady(START_READY_TIMEOUT_MS)) {
            if (config.resolverMode == Config.ResolverMode.AUTO && choice.selectedHost.isNotBlank()) {
                ResolverSelector.rememberTransport(this, config, choice.selectedHost, choice.transport)
            }
            return choice to port
        }
        if (config.resolverMode != Config.ResolverMode.AUTO) {
            error("slipstream not ready after ${START_READY_TIMEOUT_MS}ms resolver=${choice.selectedHost}:${choice.port}")
        }
        val altTransport = if (choice.transport == Config.ResolverTransport.UDP) {
            Config.ResolverTransport.TCP
        } else {
            Config.ResolverTransport.UDP
        }
        AppLog.w(
            TAG,
            "slipstream not ready with transport=${choice.transport.name.lowercase()}; retrying with " +
                "transport=${altTransport.name.lowercase()} resolver=${choice.selectedHost}:${choice.port}"
        )
        runCatching { SlipstreamBridge.stopClient() }
        if (!tunnelActive || lifecycleGeneration != generation) error("VPN start cancelled")
        val altChoice = choice.copy(transport = altTransport)
        port = startSlipstreamClientEscapingWedge(config, altChoice, port, "vpn_start:fallback")
        if (!tunnelActive || lifecycleGeneration != generation) error("VPN start cancelled")
        require(waitForSlipstreamReady(START_READY_TIMEOUT_MS)) {
            "slipstream not ready after transport fallback resolver=${altChoice.selectedHost}:${altChoice.port} " +
                "triedTransports=${choice.transport.name.lowercase()},${altTransport.name.lowercase()}"
        }
        ResolverSelector.rememberTransport(this, config, altChoice.selectedHost, altTransport)
        return altChoice to port
    }

    private fun normalizeAutoConfig(config: Config): Config {
        if (config.resolverMode != Config.ResolverMode.AUTO) return config
        val local = ResolverSelector.preferredLocalResolver(this).orEmpty()
        if (local.isBlank() || local == config.resolverHost) return config
        AppLog.i(TAG, "AUTO resolverHost refreshed from active network old=${config.resolverHost.ifBlank { "-" }} new=$local")
        return config.copy(resolverHost = local)
    }

    private fun localSocksCredentials(): Pair<String?, String?> {
        val global = ConfigStore.loadGlobalSettings(this)
        return if (global.localSocksAuthEnabled) {
            global.localSocksUsername to global.localSocksPassword
        } else {
            null to null
        }
    }

    private fun isAuthoritativeResolverPath(config: Config): Boolean =
        config.resolverPathMode == Config.ResolverPathMode.AUTHORITATIVE

    private fun stopTunnel() {
        ResolverSelector.cancelActiveProbes("vpn_stop")
        unregisterNetworkChangeWatch()
        val generation = ++lifecycleGeneration
        if (
            !starting &&
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
        starting = false
        AppLog.i(TAG, "VPN stop")
        tunnelActive = false
        currentConfig = null
        currentResolver = null
        ResolverSelector.lastConnectedTransport = null
        resolverOptimizerRunning = false
        failedAutoResolvers.clear()
        lastTransportSwitchAt = 0
        transportSwitchesThisEpisode = 0
        readyFalseSince = 0
        handler.removeCallbacks(diagnostics)
        Thread({
            try {
                if (lifecycleGeneration == generation) {
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
                } else {
                    AppLog.i(TAG, "VPN stop cleanup skipped: newer lifecycle generation active")
                }
            } finally {
                handler.post {
                    if (lifecycleGeneration == generation) {
                        stopping = false
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }, "vpn-stop-cleanup").start()
    }

    private fun resetTrafficBase() {
        val uid = applicationInfo.uid
        rxBase = TrafficStats.getUidRxBytes(uid).takeIf { it >= 0 } ?: 0
        txBase = TrafficStats.getUidTxBytes(uid).takeIf { it >= 0 } ?: 0
        notificationRxLast = 0
        notificationTxLast = 0
        notificationSampleAt = 0
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
        val notificationRx = if (HevSocks5Tunnel.isRunning()) hev.rxBytes else bridge.rxBytes
        val notificationTx = if (HevSocks5Tunnel.isRunning()) hev.txBytes else bridge.txBytes
        maybeUpdateTrafficNotification(notificationRx, notificationTx)
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
        // Real throughput returned -> the current transport is delivering, so end the transport-switch
        // "episode" (let a future degradation start fresh with its full switch budget).
        if (responseBytesDelta >= LOW_BANDWIDTH_CLEAR_DELTA_BYTES) {
            transportSwitchesThisEpisode = 0
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
        liveDiag = LiveDiag(
            resolverHost = currentResolver?.selectedHost.orEmpty(),
            resolverPort = currentResolver?.port ?: 0,
            qnameMtu = currentResolver?.qnameMtu ?: 0,
            dnsTested = currentResolver?.testedCount ?: 0,
            dnsAlive = currentResolver?.aliveCount ?: 0,
            dnsSkipped = currentResolver?.skippedCount ?: 0,
            recovering = recovering,
            recoveries = recoveryCount,
            lastProgressMs = now - lastProgressAt,
            readyFalseMs = if (readyFalseSince == 0L) 0 else now - readyFalseSince,
            slowResponseMs = if (slowResponseSince == 0L) 0 else now - slowResponseSince,
            lowBandwidthMs = if (lowBandwidthSince == 0L) 0 else now - lowBandwidthSince,
            networkSignature = lastNetworkSignature
        )
        AppLog.i(
            TAG,
            "diag running=$running ready=$ready hevRunning=${HevSocks5Tunnel.isRunning()} " +
                "port=${SlipstreamBridge.port()} appRx=$rx appTx=$tx " +
                "tunRx=${hev.rxBytes} tunTx=${hev.txBytes} " +
                "bridgeRx=${bridge.rxBytes} bridgeTx=${bridge.txBytes} " +
                "connectOk=${bridge.connectOk} connectFail=${bridge.connectFail} dnsOk=${bridge.dnsOk} dnsFail=${bridge.dnsFail} " +
                "bridgeActive=${bridge.activeClients} bridgeClients=${bridge.clientSockets} bridgeRemotes=${bridge.remoteSockets} bridgeThreads=${bridge.threads} " +
                "transport=${currentResolver?.transport?.name?.lowercase() ?: currentConfig?.resolverTransport?.name?.lowercase() ?: "unknown"} " +
                "qtype=${SlipstreamBridge.dnsQueryType} " +
                "pathMode=${currentConfig?.resolverPathMode?.name?.lowercase() ?: "unknown"} " +
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
        // NOTE: the runtime low-bandwidth "transport self-correction" was REMOVED here. It fired on any
        // sustained low-throughput stretch (common during normal browsing and file downloads over this
        // inherently slow DNS carrier) and, after being changed to re-validate rather than blind-flip,
        // each firing became a ~15-20s DNS-probing teardown of a working tunnel -- so on a slow network
        // (e.g. Tele2) it looped: dip -> re-probe -> pick the same transport -> dip -> re-probe, leaving
        // the app "stuck in DNS probing" repeatedly. Transport selection is now handled correctly at
        // connect time (validateTransport, with a throughput gate) and on network change, which covers
        // the "ready-but-throttled transport" case without tearing down a live session. A genuine total
        // stall is still caught by the traffic_no_response / bridge-failure recovery paths below.
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
        // Don't count a health failure (which rotates + condemns the resolver) when the phone has no
        // usable network — the resolver is fine, the network is gone. Wait for it to come back.
        if (!ResolverSelector.hasUsableNetwork(this)) return
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

    private fun isAddressInUse(e: Throwable): Boolean =
        e.message?.contains("Address already in use", ignoreCase = true) == true ||
            e.message?.contains("os error 98") == true

    /// Grab a currently-free localhost TCP port by binding a throwaway socket to :0. Used to dodge
    /// the EADDRINUSE wedge where a detached native accept thread is still holding the fixed
    /// slipstream listen port after stopClient() gave up joining it. Returns [fallback] if the probe
    /// itself fails (nothing lost — the caller was going to use that port anyway).
    private fun findFreeLocalPort(fallback: Int): Int =
        runCatching {
            java.net.ServerSocket().use { sock ->
                sock.bind(java.net.InetSocketAddress("127.0.0.1", 0))
                sock.localPort
            }
        }.getOrDefault(fallback)

    /// Start the native client on [preferredPort]; if the bind fails with EADDRINUSE (a previous
    /// client's detached thread is still holding the port past the native STOP_JOIN_TIMEOUT), retry
    /// once on a fresh free port instead of looping on the wedged one. Returns the port actually used
    /// so the caller can point the SOCKS bridge at it. Throws the start error if it isn't EADDRINUSE,
    /// or if the fresh-port retry also fails. Used by both the initial start and recovery paths.
    private fun startSlipstreamClientEscapingWedge(
        config: Config,
        choice: ResolverChoice,
        preferredPort: Int,
        tag: String
    ): Int {
        var port = preferredPort
        var err = SlipstreamBridge.startClient(
            config.domain,
            ResolverListConfig(choice.hosts, choice.port, isAuthoritativeResolverPath(config)),
            port,
            choice.qnameMtu,
            choice.transport.name.lowercase()
        ).exceptionOrNull()
        if (err != null && isAddressInUse(err)) {
            runCatching { SlipstreamBridge.stopClient() }
            val fresh = findFreeLocalPort(port)
            if (fresh != port) {
                AppLog.w(TAG, "$tag slipstream port $port wedged (EADDRINUSE); retrying on fresh port $fresh")
                port = fresh
                Thread.sleep(150)
                err = SlipstreamBridge.startClient(
                    config.domain,
                    ResolverListConfig(choice.hosts, choice.port, isAuthoritativeResolverPath(config)),
                    port,
                    choice.qnameMtu,
                    choice.transport.name.lowercase()
                ).exceptionOrNull()
            }
        }
        if (err != null) throw err
        return port
    }

    /// Schedule a delayed recovery retry with capped exponential backoff. Used when a recovery
    /// attempt fails outright or is deferred because there is no usable network yet, so the tunnel
    /// doesn't stay dark waiting for an unrelated diagnostic trigger. Only one retry is ever pending
    /// (coalesced). The backoff resets to base on the next successful recovery.
    private fun scheduleRecoveryRetry(reason: String) {
        if (!tunnelActive) return
        recoveryRetryPending?.let { handler.removeCallbacks(it) }
        val delay = recoveryRetryBackoffMs
        recoveryRetryBackoffMs = (recoveryRetryBackoffMs * 2).coerceAtMost(RECOVERY_RETRY_MAX_MS)
        val r = Runnable {
            recoveryRetryPending = null
            if (!tunnelActive || recovering) return@Runnable
            if (!ResolverSelector.hasUsableNetwork(this)) {
                // Still offline — keep waiting (the ConnectivityManager onAvailable callback will
                // also re-trigger). Don't probe/condemn resolvers on a dead network.
                AppLog.w(TAG, "recovery retry deferred: still no usable network reason=$reason")
                scheduleRecoveryRetry(reason)
                return@Runnable
            }
            restartSlipstreamPath(reason)
        }
        recoveryRetryPending = r
        handler.postDelayed(r, delay)
        AppLog.w(TAG, "recovery retry scheduled in ${delay}ms reason=$reason")
    }

    private fun configureNativeLogging() {
        val path = if (AppLog.isFileLoggingEnabled(this)) AppLog.file(this).absolutePath else ""
        SlipstreamBridge.setLogFilePath(path)
        HevSocks5Tunnel.setCrashLogPath(AppLog.crashFile(this).absolutePath)
    }

    private fun registerNetworkChangeWatch() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        lastNetworkSignature = ResolverSelector.networkSignature(this)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = scheduleNetworkRecheck()
            override fun onLost(network: Network) = scheduleNetworkRecheck()
            override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) =
                scheduleNetworkRecheck()
        }
        runCatching { cm.registerNetworkCallback(request, cb) }
            .onSuccess { networkCallback = cb; AppLog.i(TAG, "network-change watch registered sig='$lastNetworkSignature'") }
            .onFailure { AppLog.w(TAG, "network-change watch register failed: ${it.message}") }
    }

    private fun unregisterNetworkChangeWatch() {
        networkCallback?.let { cb ->
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        networkCallback = null
        pendingNetworkRecheck?.let { handler.removeCallbacks(it) }
        pendingNetworkRecheck = null
        recoveryRetryPending?.let { handler.removeCallbacks(it) }
        recoveryRetryPending = null
    }

    private fun scheduleNetworkRecheck() {
        // Network callbacks fire in bursts during a switch; debounce and act once it settles.
        pendingNetworkRecheck?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            pendingNetworkRecheck = null
            if (!tunnelActive || recovering) return@Runnable
            val config = currentConfig ?: return@Runnable
            if (config.resolverMode != Config.ResolverMode.AUTO) return@Runnable
            val sig = ResolverSelector.networkSignature(this)
            if (sig.isBlank() || sig == lastNetworkSignature) return@Runnable
            AppLog.w(TAG, "underlying network changed '$lastNetworkSignature' -> '$sig'; re-selecting resolver+transport")
            lastNetworkSignature = sig
            if (System.currentTimeMillis() - lastRecoveryAt > RECOVERY_COOLDOWN_MS) {
                restartSlipstreamPath("network_changed")
            }
        }
        pendingNetworkRecheck = r
        handler.postDelayed(r, NETWORK_CHANGE_DEBOUNCE_MS)
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
        // A fresh recovery (from any trigger) supersedes a scheduled backoff retry.
        recoveryRetryPending?.let { handler.removeCallbacks(it) }
        recoveryRetryPending = null
        recoveryCount += 1
        val recoveryId = recoveryCount
        AppLog.w(TAG, "recovery#$recoveryId start reason=$reason")
        Thread({
            val previousChoice = currentResolver
            try {
                if (config.resolverMode == Config.ResolverMode.AUTO &&
                    !ResolverSelector.hasUsableNetwork(this)
                ) {
                    // No underlying network right now (SIM/Wi-Fi dropped mid-switch). Every resolver
                    // probe would fail and wrongly mark healthy resolvers bad, and restarting the
                    // native client is pointless with no transport. Keep the current path intact and
                    // wait for the network to return — the ConnectivityManager callback re-triggers,
                    // and the backoff retry is the safety net.
                    AppLog.w(TAG, "recovery#$recoveryId deferred: no usable network reason=$reason")
                    scheduleRecoveryRetry(reason)
                    return@Thread
                }
                val bridgePort = config.listenPort
                var slipstreamPort = config.listenPort + 1
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
                // Reconnect the current resolver with the opposite DNS carrier transport (udp<->tcp).
                // The resolver itself is fine, so it must not be rotated or marked bad here.
                val transportSwitchRecovery = reason.startsWith("transport_switch")
                // Network/SIM change: the old resolver belongs to the previous network and may be
                // unreachable or slow now. Re-select a resolver for the CURRENT network from scratch and
                // re-validate transport + qtype (both can differ per operator).
                val networkChangedRecovery = reason.startsWith("network_changed")
                val trafficRecovery = reason.startsWith("traffic_no_response") ||
                    resolverUnreachableRecovery
                val bridgeFailureRecovery = reason.startsWith("bridge_failures")
                    || failureStormRecovery
                // "accumulated" fires from a rolling failure count, which a heavy burst can trip
                // even when the resolver itself is fine (it's just momentarily overwhelmed by a
                // wave of parallel SOCKS CONNECTs during an upload). Give it one quick same-resolver
                // retry before condemning the resolver and paying for a full multi-candidate rescan
                // (~20s of total tunnel downtime). The "..._no_response" bridge-failure variant
                // (sustained silence) still rotates immediately -- that's a real dead-resolver signal.
                val bridgeAccumulatedRecovery = reason.startsWith("bridge_failures_accumulated")
                // A storm (>=12 new bridge failures within one 5s diag tick) can likewise just be
                // many parallel CONNECTs timing out together during a legitimate large upload, not a
                // bad resolver. Give it the same quick same-resolver retry as "accumulated"; if the
                // quick retry also fails, the resolver still gets marked bad on the next tick.
                val bridgeFailureFastRetry = bridgeAccumulatedRecovery || failureStormRecovery
                // Silence-type failures (tunnel went quiet: traffic_no_response, or bridge failures
                // during sustained no-response) may be a transient blip rather than a dead resolver.
                // Retry the CURRENT resolver once before rotating; if it is genuinely dead, the
                // waitForSlipstreamReady failure below marks it bad, so the next diagnostic tick sees
                // currentAlreadyFailed and rotates to the next-best cached resolver. resolver_unreachable
                // is deliberately excluded -- a failed TCP reachability probe already proves the
                // resolver is gone, so there is nothing to retry and we rotate immediately.
                val silenceRecovery = reason.startsWith("traffic_no_response") ||
                    (reason.startsWith("bridge_failures") && reason.endsWith("_no_response"))
                val currentHostForRetry = currentResolver?.selectedHost?.takeIf { it.isNotBlank() }
                val currentAlreadyFailed =
                    currentHostForRetry != null && currentHostForRetry in failedAutoResolvers
                val retryCurrentFirst =
                    silenceRecovery && currentResolver != null && !currentAlreadyFailed
                val nativeNotReadyRecovery = reason.startsWith("native_not_ready")
                val nativeDownFastRecovery = reason == "native_not_running" &&
                    config.resolverMode == Config.ResolverMode.AUTO &&
                    currentResolver != null
                val autoFastRecovery = config.resolverMode == Config.ResolverMode.AUTO &&
                    currentResolver != null &&
                    !failureStormRecovery &&
                    (nativeDownFastRecovery || nativeNotReadyRecovery)
                val reuseCurrentResolver = isNativeNoProgress ||
                    bridgeFailureFastRetry ||
                    retryCurrentFirst ||
                    (fastPathRecovery && !trafficRecovery && !bridgeFailureRecovery)
                val rotateResolver = !retryCurrentFirst &&
                    ((reason == "native_not_running" && !isNativeNoProgress) ||
                        trafficRecovery ||
                        (bridgeFailureRecovery && !bridgeFailureFastRetry) ||
                        nativeNotReadyRecovery ||
                        resolverUnreachableRecovery)
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
                val choice = forcedChoice ?: quickChoice ?: if (transportSwitchRecovery && currentResolver != null) {
                    // Sustained low-bandwidth: RE-VALIDATE (probe transports + qtype) and keep whatever
                    // actually performs, instead of blindly flipping the transport. Blind flipping caused
                    // tcp<->udp flapping on Tele2 (where udp+HTTPS doesn't work), which shredded
                    // throughput; re-validation re-picks the working transport and stops the flap.
                    AppLog.w(TAG, "recovery#$recoveryId re-validating transport/qtype for ${currentResolver!!.selectedHost}:${currentResolver!!.port} reason=$reason")
                    SlipstreamBridge.dnsQueryType = config.dnsQueryType
                    SlipstreamBridge.dnsLabelLength = config.dnsLabelLength
                    SlipstreamBridge.maxPollQps = config.maxPollQps
                    ResolverSelector.validateTransport(this, config, currentResolver!!, "recovery#$recoveryId:$reason")
                } else if (networkChangedRecovery) {
                    // Fresh network: forget the previous network's failed resolvers, pick a resolver for
                    // the current network, and re-run transport/qtype validation for it.
                    failedAutoResolvers.clear()
                    val fresh = ResolverSelector.chooseFast(this, config, "recovery#$recoveryId:$reason", failedAutoResolvers)
                    AppLog.w(TAG, "recovery#$recoveryId network changed; fresh resolver ${fresh.selectedHost}:${fresh.port}; re-validating transport/qtype")
                    SlipstreamBridge.dnsQueryType = config.dnsQueryType
                    SlipstreamBridge.dnsLabelLength = config.dnsLabelLength
                    SlipstreamBridge.maxPollQps = config.maxPollQps
                    ResolverSelector.validateTransport(this, config, fresh, "recovery#$recoveryId:$reason")
                } else if (reuseCurrentResolver && currentResolver != null) {
                    AppLog.w(TAG, "recovery#$recoveryId reusing resolver ${currentResolver!!.selectedHost}:${currentResolver!!.port} reason=$reason")
                    currentResolver!!
                } else {
                    // Cheap next-best failover: pick the next-best resolver from the cached ranked
                    // list using only a TCP-reachability probe (no slipstream speed-probe / probe
                    // QUIC clients, which were the expensive, device-loading part of the old path).
                    // Falls back to a full speed-probe rescan only if the cache yields nothing usable.
                    runCatching {
                        ResolverSelector.chooseFast(
                            this,
                            config,
                            "recovery#$recoveryId:$reason",
                            failedAutoResolvers
                        )
                    }.getOrElse { fastErr ->
                        AppLog.w(
                            TAG,
                            "recovery#$recoveryId chooseFast found no cached resolver (${fastErr.message}); " +
                                "falling back to full speed-probe rescan"
                        )
                        try {
                            ResolverSelector.choose(
                                this,
                                config,
                                "recovery#$recoveryId:$reason:rescan",
                                failedAutoResolvers
                            )
                        } catch (rescanErr: Throwable) {
                            // Total exhaustion: every local + cached resolver failed this session.
                            // Clear the failed set so the next tick retries from scratch instead of
                            // staying dark forever (the safety valve the old quickAutoRecovery clear
                            // used to provide, now applied only at true exhaustion).
                            if (failedAutoResolvers.isNotEmpty()) {
                                AppLog.w(
                                    TAG,
                                    "recovery#$recoveryId all resolvers exhausted; clearing failed set to retry: " +
                                        failedAutoResolvers.joinToString()
                                )
                                failedAutoResolvers.clear()
                            }
                            throw rescanErr
                        }
                    }
                }
                currentResolver = choice
                ResolverSelector.lastConnectedTransport = choice.transport
                val localSocks = localSocksCredentials()
                Thread.sleep(if (fastPathRecovery || nativeDownFastRecovery) 150 else 750)
                if (!tunnelActive) {
                    AppLog.w(TAG, "recovery#$recoveryId cancelled: tunnel stopped")
                    return@Thread
                }
                SlipstreamBridge.setVpnService(this)
                SlipstreamBridge.proxyOnlyMode = false
                // Do NOT reset dnsQueryType here: keep the qtype that transport/qtype validation locked
                // in at connect (e.g. a TXT fallback on a resolver that can't forward SVCB). A network
                // change re-runs validation via startTunnelWorker, which re-picks the qtype.
                // Rebind on a fresh free port if the previous client's detached thread is still wedged
                // on slipstreamPort (EADDRINUSE) — bridgePort (what tun2socks targets) is unchanged,
                // only the internal bridge→native hop moves. Same helper the initial-start path uses.
                slipstreamPort = startSlipstreamClientEscapingWedge(config, choice, slipstreamPort, "recovery#$recoveryId")
                if (!waitForSlipstreamReady(RECOVERY_READY_TIMEOUT_MS)) {
                    runCatching { SlipstreamBridge.stopClient() }
                    // A transport switch that fails to become ready is a transport problem, not a dead
                    // resolver -- don't condemn the resolver in that case.
                    if (config.resolverMode == Config.ResolverMode.AUTO && choice.selectedHost.isNotBlank() &&
                        !transportSwitchRecovery
                    ) {
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
                    password = if (config.authMode == Config.AuthMode.LOGIN_PASSWORD) config.password else null,
                    localUsername = localSocks.first,
                    localPassword = localSocks.second
                ).getOrThrow()
                readyFalseSince = 0
                slowResponseSince = 0
                lowBandwidthSince = 0
                resolverHealthFailures = 0
                lastResolverHealthCheckAt = System.currentTimeMillis()
                lastProgressAt = System.currentTimeMillis()
                if (transportSwitchRecovery && config.resolverMode == Config.ResolverMode.AUTO &&
                    choice.selectedHost.isNotBlank()
                ) {
                    // Persist the switched-to transport so the next connect on this network profile
                    // starts on it directly instead of re-guessing the throttled one.
                    ResolverSelector.rememberTransport(this, config, choice.selectedHost, choice.transport)
                    AppLog.w(
                        TAG,
                        "recovery#$recoveryId remembered switched transport=${choice.transport.name.lowercase()} " +
                            "for ${choice.selectedHost}"
                    )
                }
                // Recovered: cancel any pending backoff retry and reset the backoff window.
                recoveryRetryPending?.let { handler.removeCallbacks(it) }
                recoveryRetryPending = null
                recoveryRetryBackoffMs = RECOVERY_RETRY_BASE_MS
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
                } else if (tunnelActive) {
                    // Don't leave the tunnel dark after a failed recovery — reschedule with backoff
                    // instead of waiting for an unrelated diagnostic trigger to fire.
                    scheduleRecoveryRetry(reason)
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
            val localSocks = localSocksCredentials()
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
                ResolverListConfig(choice.hosts, choice.port, isAuthoritativeResolverPath(config)),
                slipstreamPort,
                choice.qnameMtu,
                choice.transport.name.lowercase()
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
                password = if (config.authMode == Config.AuthMode.LOGIN_PASSWORD) config.password else null,
                localUsername = localSocks.first,
                localPassword = localSocks.second
            ).getOrThrow()
            currentResolver = choice
            ResolverSelector.lastConnectedTransport = choice.transport
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
        // Trimmed "до лучших времён": no background throughput-ranking speed probe / resolver upgrade.
        // Resolver stays whatever the operator-only reachability pick chose; transport/qtype auto stays.
        if (!RESOLVER_SPEED_OPTIMIZER_ENABLED) return
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

        if (preferred.isEmpty()) {
            // Every local (DHCP) + current-connection resolver is already marked bad this session.
            // Previously this cleared the failed set and re-cycled the same operator DNS servers, which
            // caused an oscillation between them without ever trying the known-good resolvers in the
            // speed-probed cache. Instead return null so the caller escalates to chooseFast(), which
            // draws the next-best resolver from the full cache. The failed set is preserved (and only
            // cleared on true total exhaustion, in the rotate branch's rescan fallback).
            AppLog.w(
                TAG,
                "recovery#$recoveryId auto-fast local candidates exhausted; escalating to cached resolver pool " +
                    "failed=${failedAutoResolvers.joinToString()}"
            )
            return null
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
            skippedCount = failedAutoResolvers.size,
            transport = current?.transport ?: Config.ResolverTransport.UDP
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

    private fun maybeUpdateTrafficNotification(rx: Long, tx: Long, force: Boolean = false) {
        if (!ConfigStore.loadGlobalSettings(this).trafficNotification) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            return
        }
        val now = System.currentTimeMillis()
        val firstSample = notificationSampleAt == 0L
        val elapsedMs = (now - notificationSampleAt).takeIf { !firstSample && it > 0 } ?: 1000L
        val downRate = if (firstSample) 0L else ((rx - notificationRxLast).coerceAtLeast(0) * 1000L) / elapsedMs
        val upRate = if (firstSample) 0L else ((tx - notificationTxLast).coerceAtLeast(0) * 1000L) / elapsedMs
        notificationRxLast = rx
        notificationTxLast = tx
        notificationSampleAt = now
        val text = "↓ ${formatBytes(rx)} (${formatRate(downRate)})   ↑ ${formatBytes(tx)} (${formatRate(upRate)})"
        startForeground(1, notification("Connected", text))
    }

    private fun notification(title: String, text: String): Notification {
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
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_vaydns)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun formatBytes(value: Long): String {
        val v = value.coerceAtLeast(0)
        return when {
            v >= 1024L * 1024L -> "${v / 1024L / 1024L} MiB"
            v >= 1024L -> "${v / 1024L} KiB"
            else -> "$v B"
        }
    }

    private fun formatRate(value: Long): String {
        val v = value.coerceAtLeast(0)
        return when {
            v >= 1024L * 1024L -> "${v / 1024L / 1024L} MiB/s"
            v >= 1024L -> "${v / 1024L} KiB/s"
            else -> "$v B/s"
        }
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

        // Snapshot of the rich per-tick diagnostics already computed for the logcat "diag" line,
        // additionally exposed here so the in-app Diagnostics screen can show it (not just logcat).
        data class LiveDiag(
            val resolverHost: String = "",
            val resolverPort: Int = 0,
            val qnameMtu: Int = 0,
            val dnsTested: Int = 0,
            val dnsAlive: Int = 0,
            val dnsSkipped: Int = 0,
            val recovering: Boolean = false,
            val recoveries: Int = 0,
            val lastProgressMs: Long = 0,
            val readyFalseMs: Long = 0,
            val slowResponseMs: Long = 0,
            val lowBandwidthMs: Long = 0,
            val networkSignature: String = ""
        )

        @Volatile
        var liveDiag: LiveDiag = LiveDiag()
            private set
        private const val CHANNEL = "vaydns"
        private const val TAG = "TinyVpnService"
        private const val VPN_DNS_PRIMARY = "1.1.1.1"
        private const val VPN_DNS_SECONDARY = "8.8.8.8"
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
        // Runtime transport self-correction (auto udp<->tcp switch when the chosen carrier is ready but
        // throttled to near-uselessness). Reacts far sooner than LOW_BANDWIDTH_RECOVERY_MS (45s) because
        // a wrong transport never self-heals; bounded per episode to prevent flapping.
        private const val TRANSPORT_SWITCH_LOW_BW_MS = 15_000L
        private const val TRANSPORT_SWITCH_COOLDOWN_MS = 18_000L
        private const val MAX_TRANSPORT_SWITCHES_PER_EPISODE = 2
        // Debounce for underlying-network (SIM/Wi-Fi) change before re-selecting the resolver.
        private const val NETWORK_CHANGE_DEBOUNCE_MS = 4_000L
        private const val RECOVERY_COOLDOWN_MS = 5_000L
        // Auto-DNS trim: background throughput-ranking resolver optimizer is off "до лучших времён".
        private const val RESOLVER_SPEED_OPTIMIZER_ENABLED = false
        // Backoff for re-attempting a recovery that failed or was deferred (e.g. no network yet),
        // so the tunnel doesn't stay dark until an unrelated diagnostic trigger happens to fire.
        private const val RECOVERY_RETRY_BASE_MS = 3_000L
        private const val RECOVERY_RETRY_MAX_MS = 60_000L
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
