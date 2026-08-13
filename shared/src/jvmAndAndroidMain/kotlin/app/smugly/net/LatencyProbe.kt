package app.smugly.net

import app.smugly.Config
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject

/**
 * How long the first packet takes to come back from a profile's own server.
 *
 * **This is the fallback.** [E2ELatencyProbe] runs the profile's real engine and times a request
 * through the tunnel, which is the number a row should show; this one only answers the weaker
 * "can that host be reached from here" and exists for the case where a platform has no engine for
 * the protocol (Slipstream has no Windows build). Reaching a server's port says nothing about
 * whether the credentials, the REALITY handshake or the exit's DNS work, so prefer the e2e probe
 * wherever it can run.
 *
 * Every protocol is probed at the hop that decides reachability, and nothing is inferred:
 *
 *  * **Slipstream** — a real DNS query to the configured resolver for the tunnel's own domain.
 *    That traverses resolver → authoritative → back, which is the tunnel's carrier; a resolver
 *    that answers for anything else but not for this domain is exactly the failure to catch.
 *  * **s3fu** — TCP+TLS reach to the storage endpoint, the only host the client ever talks to.
 *  * **cdnfu** — TCP reach to the CDN edge host in the profile URL.
 *  * **Xray** — TCP reach to the outbound server named in the config.
 *
 * A failure is reported as a failure rather than a large number: "unreachable" and "slow" are
 * different answers and the row should not blur them.
 */
object LatencyProbe {
    private const val TIMEOUT_MS = 5_000

    fun measure(config: Config): Result<Int> = runCatching {
        when (config.protocol) {
            Config.TunnelProtocol.SLIPSTREAM -> measureSlipstream(config)
            Config.TunnelProtocol.S3FU -> measureS3fu(config)
            Config.TunnelProtocol.CDNFU -> measureCdnfu(config)
            Config.TunnelProtocol.XRAY -> measureXray(config)
        }
    }

    // ---- per protocol ----

    private fun measureSlipstream(config: Config): Int {
        val host = config.resolverHost.trim()
        // An auto-DNS profile has no resolver until the app picks one, and picking one needs the
        // network APIs this shared code cannot reach — that is the e2e probe's job. Saying so beats
        // the old bare "no resolver configured", which is what every auto-DNS card reported.
        require(host.isNotEmpty()) {
            if (config.resolverMode == Config.ResolverMode.AUTO) {
                "auto-DNS picks its resolver at connect time; this platform cannot run the tunnel to find out"
            } else {
                "no resolver configured"
            }
        }
        val port = if (config.resolverPort in 1..65535) config.resolverPort else 53
        val domain = config.domain.trim().ifEmpty { "example.com" }
        // The engine's own carrier is UDP unless the profile says otherwise, and a resolver that
        // answers over one and not the other is a real, common difference — probe what the
        // profile would actually use.
        return if (config.resolverTransport == Config.ResolverTransport.TCP) {
            tcpRoundTrip(host, port)
        } else {
            dnsRoundTrip(host, port, domain)
        }
    }

    private fun measureS3fu(config: Config): Int {
        val endpoint = config.s3Endpoint.trim()
        require(endpoint.isNotEmpty()) { "no S3 endpoint configured" }
        val uri = URI(if (endpoint.contains("://")) endpoint else "https://$endpoint")
        val host = uri.host ?: error("bad S3 endpoint '$endpoint'")
        val port = if (uri.port > 0) uri.port else if (uri.scheme == "http") 80 else 443
        return tcpRoundTrip(host, port)
    }

    private fun measureCdnfu(config: Config): Int {
        val endpoint = config.cdnfuUrl.trim()
        require(endpoint.isNotEmpty()) { "no URL configured" }
        val uri = URI(if (endpoint.contains("://")) endpoint else "https://$endpoint")
        val host = uri.host ?: error("bad URL '$endpoint'")
        val port = if (uri.port > 0) uri.port else if (uri.scheme == "http") 80 else 443
        return tcpRoundTrip(host, port)
    }

    private fun measureXray(config: Config): Int {
        val (host, port) = xrayEndpoint(config.xrayConfigJson) ?: error("no server in Xray config")
        return tcpRoundTrip(host, port)
    }

    /**
     * The profile's own server: the first outbound that has one, **in the order the config lists
     * them**. Xray itself falls back to that outbound when routing picks nothing, so it is the one
     * a connection actually uses.
     *
     * Walking the whole document instead would be at the mercy of JSON key order, which org.json
     * does not promise — on a chained profile that happily timed a child node or a `dns.servers`
     * entry instead of the primary, and the row then showed a latency belonging to a different
     * country. Inside one outbound the walk stays, because vless/vmess (`vnext`) and
     * shadowsocks/trojan (`servers`) keep the host in different places.
     */
    private fun xrayEndpoint(json: String): Pair<String, Int>? {
        if (json.isBlank()) return null
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val outbounds = root.optJSONArray("outbounds")
        if (outbounds != null) {
            for (i in 0 until outbounds.length()) {
                val outbound = outbounds.optJSONObject(i) ?: continue
                // freedom / blackhole / dns have no server to reach; skipping them keeps the first
                // *real* peer, not whichever one happens to sort first.
                when (outbound.optString("protocol").lowercase()) {
                    "freedom", "blackhole", "dns", "loopback" -> continue
                }
                endpointIn(outbound)?.let { return it }
            }
        }
        return endpointIn(root)
    }

    /** First `address` + `port` pair anywhere under [node]; arrays keep their order. */
    private fun endpointIn(node: Any?): Pair<String, Int>? = when (node) {
        is JSONObject -> {
            val address = node.optString("address").ifBlank { node.optString("host") }
            val port = node.optInt("port", -1)
            if (address.isNotBlank() && port in 1..65535) {
                address to port
            } else {
                node.keys().asSequence().firstNotNullOfOrNull { endpointIn(node.opt(it)) }
            }
        }
        is JSONArray -> (0 until node.length()).firstNotNullOfOrNull { endpointIn(node.opt(it)) }
        else -> null
    }

    // ---- primitives ----

    private fun tcpRoundTrip(host: String, port: Int): Int {
        val started = System.nanoTime()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), TIMEOUT_MS)
        }
        return elapsedMs(started)
    }

    private fun dnsRoundTrip(host: String, port: Int, domain: String): Int {
        val query = dnsQuery(domain)
        DatagramSocket().use { socket ->
            socket.soTimeout = TIMEOUT_MS
            val address = InetAddress.getByName(host)
            val started = System.nanoTime()
            socket.send(DatagramPacket(query, query.size, address, port))
            val reply = ByteArray(512)
            val packet = DatagramPacket(reply, reply.size)
            // Ignore anything that is not the answer to our own query id: on a busy UDP socket a
            // stray packet would otherwise be timed as if it were the reply.
            while (true) {
                socket.receive(packet)
                if (packet.length >= 2 && reply[0] == query[0] && reply[1] == query[1]) break
                if (elapsedMs(started) > TIMEOUT_MS) throw IOException("no matching DNS reply")
            }
            return elapsedMs(started)
        }
    }

    /** Minimal DNS query: one TXT question, recursion desired. */
    private fun dnsQuery(domain: String): ByteArray {
        val out = ArrayList<Byte>(64)
        val id = Random.nextInt(0, 0xFFFF)
        out.add((id shr 8).toByte()); out.add(id.toByte())
        out.add(0x01); out.add(0x00)          // flags: standard query, recursion desired
        out.add(0x00); out.add(0x01)          // qdcount = 1
        repeat(6) { out.add(0x00) }           // an/ns/ar counts = 0
        for (label in domain.split('.')) {
            if (label.isEmpty()) continue
            val bytes = label.encodeToByteArray()
            require(bytes.size <= 63) { "DNS label too long" }
            out.add(bytes.size.toByte())
            bytes.forEach { out.add(it) }
        }
        out.add(0x00)                         // end of name
        out.add(0x00); out.add(0x10)          // qtype = TXT
        out.add(0x00); out.add(0x01)          // qclass = IN
        return out.toByteArray()
    }

    private fun elapsedMs(startedNanos: Long): Int =
        ((System.nanoTime() - startedNanos) / 1_000_000).toInt().coerceAtLeast(1)
}
