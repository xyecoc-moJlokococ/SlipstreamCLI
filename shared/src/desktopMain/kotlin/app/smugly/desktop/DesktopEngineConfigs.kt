package app.smugly.desktop

import app.smugly.Config
import app.smugly.DnsResolverPool
import app.smugly.TunnelToml
import app.smugly.effectiveCdnfuToml
import app.smugly.effectiveS3fuToml
import org.json.JSONArray
import org.json.JSONObject

/**
 * The config text each native engine is started with.
 *
 * Kept apart from [DesktopTunnel] because the latency probe starts the very same engines on a
 * throwaway port: the tunnel and the probe must agree on every knob, or a profile would be measured
 * with settings it never runs with.
 *
 * The profile carries the whole config file now, so this no longer renders one from a fixed list
 * of fields — it takes the profile's text and overrides only the local listen address. That is
 * also what keeps desktop and phone honest: both run the exact text the user edited, instead of
 * two hand-written generators that had already drifted apart (desktop pinned `pool.size = 32` and
 * `pipeline = 8` where the phone used 0 and 16).
 */
object DesktopEngineConfigs {

    /** Matches [app.smugly.tunnel.SlipstreamBridge] so desktop and the phone pace the same way. */
    const val SLIPSTREAM_KEEP_ALIVE_MS = 5000
    const val SLIPSTREAM_PACING_GAIN_PROBE = 1.4
    const val SLIPSTREAM_DNS_TCP_PACKET_LOOP_BURST = 32

    /** s3fu reads a TOML config; see `crates/s3fu-core/src/config.rs`. */
    fun s3fu(c: Config, socksPort: Int): String {
        val toml = c.effectiveS3fuToml()
        require(toml.isNotBlank()) { "s3fu config is empty" }
        return TunnelToml.withForcedTopLevelKey(toml, "socks_listen", "127.0.0.1:$socksPort")
    }

    /** cdnfu client TOML — see `configs/client.toml` in the cdn-fuckup repo. */
    fun cdnfu(c: Config, socksPort: Int): String {
        val toml = c.effectiveCdnfuToml()
        require(toml.isNotBlank()) { "cdnfu config is empty" }
        return TunnelToml.withForcedTopLevelKey(toml, "listen", "127.0.0.1:$socksPort")
    }

    /**
     * JSON for `slipstream-client --config`. Schema is 1:1 with the CLI
     * (`crates/slipstream-client/src/main.rs` `ClientFileConfig`).
     *
     * [resolverPoolRaw] is the desktop Settings pool, used only when the profile is on AUTO —
     * there is no Android [ResolverSelector] here, so we feed the configured IPs and skip
     * `(local)` (desktop cannot read the machine's DHCP resolvers the way the phone can).
     */
    fun slipstream(
        c: Config,
        socksPort: Int,
        resolverPoolRaw: String = DnsResolverPool.DEFAULT_RAW
    ): String {
        val domain = c.domain.trim()
        require(domain.isNotEmpty()) { "Slipstream domain is empty" }
        val port = c.resolverPort.takeIf { it in 1..65535 } ?: 53
        val authoritative = c.resolverPathMode == Config.ResolverPathMode.AUTHORITATIVE
        val hosts = slipstreamResolverHosts(c, resolverPoolRaw)
        require(hosts.isNotEmpty()) {
            if (c.resolverMode == Config.ResolverMode.AUTO) {
                "auto-DNS pool has no usable resolvers on desktop (local/DHCP is phone-only). " +
                    "Add resolver IPs in Settings or switch the profile to Manual."
            } else {
                "no resolver configured"
            }
        }
        // JNI does the same: TCP cannot stripe across resolvers the way UDP can.
        val transport = if (c.resolverTransport == Config.ResolverTransport.TCP) "tcp" else "udp"
        val effectiveHosts = if (transport == "tcp") hosts.take(1) else hosts
        val resolvers = JSONArray()
        for (host in effectiveHosts) {
            resolvers.put(
                JSONObject()
                    .put("addr", "$host:$port")
                    .put("authoritative", authoritative)
            )
        }
        return JSONObject()
            .put("tcp_listen_host", "127.0.0.1")
            .put("tcp_listen_port", socksPort)
            .put("resolvers", resolvers)
            .put("domain", domain)
            .put("resolver_transport", transport)
            .put("dns_query_type", c.dnsQueryType)
            .put("dns_label_length", c.dnsLabelLength)
            .put("dns_label_length_jitter", c.dnsLabelLengthJitter)
            .put("max_poll_qps", c.maxPollQps)
            .put("base64u_encoding", c.base64uEncoding)
            .put("keep_alive_interval", SLIPSTREAM_KEEP_ALIVE_MS)
            .put("pacing_gain_probe", SLIPSTREAM_PACING_GAIN_PROBE)
            .put("dns_tcp_packet_loop_burst", SLIPSTREAM_DNS_TCP_PACKET_LOOP_BURST)
            .put("qname_mtu", 0)
            .toString()
    }

    fun slipstreamResolverHosts(c: Config, resolverPoolRaw: String): List<String> {
        if (c.resolverMode == Config.ResolverMode.MANUAL) {
            return DnsResolverPool.parseManualHosts(c.resolverHost)
        }
        return DnsResolverPool.parse(resolverPoolRaw)
            .filter { !DnsResolverPool.isLocalSentinel(it) }
    }
}
