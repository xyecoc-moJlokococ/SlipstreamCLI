package app.smugly.net

import android.content.Context
import app.smugly.Config
import app.smugly.ResolverSelector
import app.smugly.XrayConfigBuilder
import app.smugly.tunnel.ResolverListConfig
import app.smugly.tunnel.SlipstreamBridge
import app.smugly.tunnel.XrayBridge
import app.smugly.util.AppLog
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Starts a profile's engine on the phone so [E2ELatencyProbe] can time a request through it.
 *
 * Every engine here runs *beside* the live tunnel rather than instead of it: measuring a row's
 * latency while connected must not drop the connection. That is why Xray gets its own core
 * instance and Slipstream uses the probe-client entry point (which is keyed by listen port), and
 * also why s3fu / cdnfu are declined — their native side keeps one client per process, so probing
 * one would tear down whatever is running.
 */
class AndroidProbeEngines(context: Context) : E2ELatencyProbe.Launcher {
    private val appContext = context.applicationContext

    override fun supports(protocol: Config.TunnelProtocol): Boolean = when (protocol) {
        Config.TunnelProtocol.XRAY -> XrayBridge.isLoaded()
        Config.TunnelProtocol.SLIPSTREAM -> SlipstreamBridge.isLoaded()
        // One native client per process: starting a probe would stop the user's tunnel.
        Config.TunnelProtocol.S3FU, Config.TunnelProtocol.CDNFU -> false
    }

    override fun readyTimeoutMs(protocol: Config.TunnelProtocol): Long = when (protocol) {
        // Xray binds its inbound during startLoop; anything past a second means it did not start.
        Config.TunnelProtocol.XRAY -> 5_000
        // A DNS tunnel has a QUIC handshake to finish over a carrier that is slow by design. Kept
        // close to the resolver selector's own 5s probe budget because a dead candidate costs this
        // whole timeout before the next one is tried.
        else -> 8_000
    }

    override fun launch(config: Config, socksPort: Int): E2ELatencyProbe.Session =
        when (config.protocol) {
            Config.TunnelProtocol.XRAY -> launchXray(config, socksPort)
            Config.TunnelProtocol.SLIPSTREAM -> launchSlipstream(config, socksPort)
            else -> error("${config.protocol} cannot be probed end to end on Android")
        }

    private fun launchXray(config: Config, socksPort: Int): E2ELatencyProbe.Session {
        require(config.xrayConfigJson.isNotBlank()) { "the profile has no Xray configuration" }
        XrayBridge.init(appContext)
        // Routing rules with geoip: / geosite: refuse to parse without the real files, and a probe
        // is exactly the moment a fresh install has not copied them yet.
        XrayBridge.ensureGeoAssets(appContext)
        // Every inbound replaced, not just the SOCKS one: panel configs also carry an HTTP inbound
        // on a fixed port, and two probes (or a probe next to the live tunnel) would collide on it.
        val json = XrayConfigBuilder.withOnlySocksInbound(config.xrayConfigJson, socksPort)
        val instance = XrayBridge.startInstance(json).getOrThrow()
        return object : E2ELatencyProbe.Session {
            override fun awaitReady(timeoutMs: Long): Boolean =
                instance.isRunning && awaitPort(socksPort, timeoutMs)

            override fun close() {
                instance.close()
            }
        }
    }

    /**
     * Slipstream needs a resolver before it can start, and an auto-DNS profile has none stored —
     * which is why these rows showed a dash: the old probe asked the config for a resolver host,
     * found an empty string and reported a failure without ever touching the network. The resolver
     * is picked here the same way a connect picks it, and each candidate is *actually run*, so the
     * answer covers the carrier as well as the tunnel.
     */
    private fun launchSlipstream(config: Config, socksPort: Int): E2ELatencyProbe.Session {
        val domain = config.domain.trim()
        require(domain.isNotEmpty()) { "the profile has no tunnel domain" }
        val candidates = ResolverSelector.probeResolverCandidates(appContext, config)
            .take(MAX_RESOLVER_CANDIDATES)
        require(candidates.isNotEmpty()) { "no resolver to try — set one, or connect once on this network" }

        // Only one candidate can hold the port at a time, so they are tried in turn and the first
        // one whose QUIC session comes up is the one measured.
        var lastError: Throwable? = null
        for (candidate in candidates) {
            val session = runCatching { startProbeClient(domain, candidate, socksPort, config) }
                .getOrElse { lastError = it; null }
                ?: continue
            if (session.awaitReady(readyTimeoutMs(Config.TunnelProtocol.SLIPSTREAM))) {
                AppLog.i(TAG, "probe resolver ${candidate.host} (${candidate.transport}) came up")
                return session
            }
            session.close()
            lastError = IllegalStateException(
                "resolver ${candidate.host} over ${candidate.transport.name.lowercase()} did not come up"
            )
        }
        throw lastError ?: IllegalStateException("no resolver carried the tunnel")
    }

    private fun startProbeClient(
        domain: String,
        candidate: ResolverSelector.ProbeCandidate,
        socksPort: Int,
        config: Config
    ): E2ELatencyProbe.Session {
        withProfileDnsKnobs(config) {
            SlipstreamBridge.startProbeClient(
                domain,
                // Path mode from the profile, exactly as a real connect passes it: recursive and
                // authoritative are different journeys through DNS, and one working says nothing
                // about the other.
                ResolverListConfig(
                    listOf(candidate.host),
                    candidate.port,
                    config.resolverPathMode == Config.ResolverPathMode.AUTHORITATIVE
                ),
                socksPort,
                candidate.qnameMtu,
                candidate.transport.name.lowercase()
            ).getOrThrow()
        }
        return object : E2ELatencyProbe.Session {
            override fun awaitReady(timeoutMs: Long): Boolean {
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    if (SlipstreamBridge.isProbeReady(socksPort)) return true
                    if (!SlipstreamBridge.isProbeRunning(socksPort)) return false
                    Thread.sleep(READY_POLL_MS)
                }
                return false
            }

            override fun close() {
                runCatching { SlipstreamBridge.stopProbeClient(socksPort) }
            }
        }
    }

    /**
     * Run [start] with the *probed* profile's DNS fingerprint knobs applied, then put back whatever
     * was there.
     *
     * The engine reads these as process-wide fields at start, so probing a profile whose query type
     * is HTTPS while the app's globals still say TXT would measure a tunnel the profile never uses —
     * and on a server that only answers one of them, would report a working card as dead.
     *
     * While a tunnel is up the knobs are left alone entirely: they belong to the live client, and a
     * reconnect that lands between the set and the restore would come back up misconfigured. The
     * probe then inherits the running profile's fingerprint, which is the safer of the two errors.
     */
    private fun withProfileDnsKnobs(config: Config, start: () -> Unit) {
        if (SlipstreamBridge.isRunning()) {
            start()
            return
        }
        val qtype = SlipstreamBridge.dnsQueryType
        val labelLength = SlipstreamBridge.dnsLabelLength
        val jitter = SlipstreamBridge.dnsLabelLengthJitter
        val maxPoll = SlipstreamBridge.maxPollQps
        val maxData = SlipstreamBridge.maxDataQps
        val base64u = SlipstreamBridge.base64uEncoding
        try {
            SlipstreamBridge.dnsQueryType = config.dnsQueryType
            SlipstreamBridge.dnsLabelLength = config.dnsLabelLength
            SlipstreamBridge.dnsLabelLengthJitter = config.dnsLabelLengthJitter
            SlipstreamBridge.maxPollQps = config.maxPollQps
            SlipstreamBridge.maxDataQps = config.maxDataQps
            SlipstreamBridge.base64uEncoding = config.base64uEncoding
            start()
        } finally {
            SlipstreamBridge.dnsQueryType = qtype
            SlipstreamBridge.dnsLabelLength = labelLength
            SlipstreamBridge.dnsLabelLengthJitter = jitter
            SlipstreamBridge.maxPollQps = maxPoll
            SlipstreamBridge.maxDataQps = maxData
            SlipstreamBridge.base64uEncoding = base64u
        }
    }

    private fun awaitPort(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val open = runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }
                true
            }.getOrDefault(false)
            if (open) return true
            Thread.sleep(READY_POLL_MS)
        }
        return false
    }

    private companion object {
        const val TAG = "ProbeEngines"
        const val READY_POLL_MS = 100L

        /**
         * How many resolvers one probe may try before giving up. Each failure costs the full ready
         * timeout, and a row that takes half a minute to report a dash helps nobody.
         */
        const val MAX_RESOLVER_CANDIDATES = 2
    }
}
