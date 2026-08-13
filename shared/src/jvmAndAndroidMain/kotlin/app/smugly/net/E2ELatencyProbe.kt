package app.smugly.net

import app.smugly.Config
import app.smugly.platform.LogLevel
import app.smugly.platform.PlatformLog
import java.io.InputStream
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * How long a request takes **through the profile's own tunnel**.
 *
 * The number a server row shows should answer "if I tap this, will it work, and how does it feel" —
 * and the only honest way to answer that is to run the thing. So the profile's real engine is
 * started on a throwaway local SOCKS port, one HTTP request is sent through it, and the round trip
 * is timed. A profile whose server answers TCP but whose UUID is wrong, whose REALITY handshake
 * fails, or whose exit resolves nothing therefore reports a failure instead of a healthy-looking
 * number — which a plain TCP connect to the server's port could never tell apart.
 *
 * What is deliberately *not* in the number: bringing the engine up. Starting a DNS tunnel takes
 * seconds of handshaking that has nothing to do with how the profile behaves once connected, so the
 * clock starts after the engine reports itself ready.
 *
 * Where a platform cannot run a protocol at all (no Slipstream engine on Windows) the launcher
 * declines and [LatencyProbe] answers the weaker "can this server be reached" question instead.
 */
object E2ELatencyProbe {
    private const val TAG = "E2ELatencyProbe"

    /**
     * Plain HTTP on purpose. A TLS handshake would add two round trips of noise on top of the one
     * thing being measured, and this URL is the same 204 endpoint every other client pings, so the
     * numbers are comparable with what happ / v2rayNG show.
     */
    private const val PROBE_HOST = "cp.cloudflare.com"
    private const val PROBE_PORT = 80
    private const val PROBE_PATH = "/generate_204"

    private const val REQUEST_TIMEOUT_MS = 10_000

    /**
     * Probes run on a small pool because "measure the whole folder" fires one per profile, and each
     * one is a live engine with its own sockets and memory. Three at a time keeps a 40-server
     * subscription from starting 40 tunnels at once while still finishing quickly.
     */
    private const val MAX_CONCURRENT_PROBES = 3

    private val pool = Executors.newFixedThreadPool(
        MAX_CONCURRENT_PROBES,
        ThreadFactory { runnable ->
            Thread(runnable, "latency-probe").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }
    )

    /** A tunnel engine running for the length of one probe. */
    interface Session : AutoCloseable {
        /**
         * Block until the engine can carry traffic, or [timeoutMs] runs out.
         * "Listening" is not enough for a tunnel that dials out on its own — a Slipstream client
         * accepts on its SOCKS port long before its QUIC session exists.
         */
        fun awaitReady(timeoutMs: Long): Boolean
    }

    /** Starts profiles' engines. One implementation per platform. */
    interface Launcher {
        /** False when this platform has no engine for [protocol] and the probe must fall back. */
        fun supports(protocol: Config.TunnelProtocol): Boolean

        /** Start [config]'s engine listening for SOCKS5 on 127.0.0.1:[socksPort], or throw. */
        fun launch(config: Config, socksPort: Int): Session

        /** How long that engine may take to become usable. */
        fun readyTimeoutMs(protocol: Config.TunnelProtocol): Long = 15_000
    }

    /** Run [config]'s probe on the shared pool; [onResult] fires on a probe thread. */
    fun submit(config: Config, launcher: Launcher?, onResult: (Result<Int>) -> Unit) {
        pool.execute {
            val result = runCatching { measure(config, launcher) }
                .getOrElse { Result.failure(it) }
            onResult(result)
        }
    }

    fun measure(config: Config, launcher: Launcher?): Result<Int> {
        if (launcher == null || !launcher.supports(config.protocol)) {
            PlatformLog.log(
                LogLevel.INFO, TAG,
                "no engine for ${config.protocol} on this platform; falling back to a reachability probe"
            )
            return LatencyProbe.measure(config)
        }
        val port = freeLocalPort()
        return runCatching {
            launcher.launch(config, port).use { session ->
                val readyTimeout = launcher.readyTimeoutMs(config.protocol)
                if (!session.awaitReady(readyTimeout)) {
                    error("the tunnel did not come up within ${readyTimeout / 1000}s")
                }
                val ms = requestRoundTripMs(config, port)
                PlatformLog.log(LogLevel.INFO, TAG, "${config.protocol} e2e latency ${ms}ms")
                ms
            }
        }.onFailure {
            PlatformLog.log(LogLevel.INFO, TAG, "${config.protocol} e2e probe failed: ${it.message}")
        }
    }

    /**
     * One HTTP round trip through the engine, from the moment the SOCKS connection is dialled to
     * the moment the status line comes back. That covers the tunnel handshake to the server, the
     * exit's own connection to the destination, and the request itself — everything the first click
     * in a browser would pay for.
     */
    private fun requestRoundTripMs(config: Config, socksPort: Int): Int {
        // Only Slipstream carries a login for its local listener; the rest run no-auth.
        val credentialed = config.protocol == Config.TunnelProtocol.SLIPSTREAM &&
            config.authMode == Config.AuthMode.LOGIN_PASSWORD
        val started = System.nanoTime()
        Socks5Client.connect(
            proxyPort = socksPort,
            host = PROBE_HOST,
            port = PROBE_PORT,
            username = if (credentialed) config.username else null,
            password = if (credentialed) config.password else null,
            timeoutMs = REQUEST_TIMEOUT_MS
        ).use { socket ->
            socket.soTimeout = REQUEST_TIMEOUT_MS
            val request = "GET $PROBE_PATH HTTP/1.1\r\n" +
                "Host: $PROBE_HOST\r\n" +
                "User-Agent: Smugly-LatencyProbe\r\n" +
                "Connection: close\r\n\r\n"
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.US_ASCII))
                flush()
            }
            val status = readStatusLine(socket.getInputStream())
            // Any status proves the round trip; only a non-answer is a failure. Captive portals and
            // panels that rewrite 204 into a redirect still tell us the tunnel carries traffic.
            require(status.startsWith("HTTP/")) { "the exit answered something that is not HTTP" }
            return elapsedMs(started)
        }
    }

    private fun readStatusLine(input: InputStream): String {
        val line = StringBuilder(64)
        while (line.length < 256) {
            val b = input.read()
            if (b < 0) {
                if (line.isEmpty()) error("the exit closed the connection without answering")
                break
            }
            if (b == '\n'.code) break
            if (b != '\r'.code) line.append(b.toChar())
        }
        return line.toString()
    }

    /**
     * A port nothing is listening on. Bound and released rather than guessed, so two probes running
     * side by side cannot pick the same one; the engine claims it a moment later.
     */
    private fun freeLocalPort(): Int = ServerSocket(0).use { it.localPort }

    private fun elapsedMs(startedNanos: Long): Int =
        ((System.nanoTime() - startedNanos) / 1_000_000).toInt().coerceAtLeast(1)
}
