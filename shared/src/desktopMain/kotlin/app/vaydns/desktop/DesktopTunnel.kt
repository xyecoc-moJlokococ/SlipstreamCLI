package app.vaydns.desktop

import app.vaydns.Config
import app.vaydns.GlobalSettings
import app.vaydns.platform.AppPaths
import app.vaydns.platform.LogLevel
import app.vaydns.platform.PlatformLog
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Desktop tunnel orchestration: native engine process + local mixed proxy + system proxy.
 *
 * Layout is the same for every protocol so there is one code path to reason about:
 *
 * ```
 *   Windows system proxy  ->  127.0.0.1:<listenPort>      MixedProxyServer (HTTP + SOCKS5)
 *                                       |  SOCKS5
 *                             127.0.0.1:<listenPort + 1>  engine process (s3fu.exe / xray.exe)
 *                                       |
 *                                    tunnel
 * ```
 *
 * The engine only ever has to speak SOCKS5, and everything user-visible — local auth, traffic
 * counters, connection limits — lives in one place instead of being reimplemented per engine.
 */
object DesktopTunnel {
    private const val TAG = "DesktopTunnel"
    private const val ENGINE_READY_TIMEOUT_MS = 20_000L

    /** Result of a connect attempt; [warning] is shown to the user but does not mean failure. */
    data class StartOutcome(
        val listenPort: Int,
        val engineName: String,
        val systemProxyApplied: Boolean,
        val warning: String? = null
    )

    @Volatile private var engine: EngineProcess? = null
    @Volatile private var proxy: MixedProxyServer? = null
    @Volatile private var systemProxyApplied = false

    /**
     * Escape hatch: run the tunnel as a plain local proxy and leave the machine's proxy settings
     * alone. Useful when another tool already owns them, and the only safe way to exercise the
     * tunnel without cutting the current connection.
     */
    private fun systemProxyDisabled(): Boolean =
        System.getenv("VAYDNS_NO_SYSTEM_PROXY")?.lowercase() in setOf("1", "true", "yes")

    val isRunning: Boolean get() = proxy?.isRunning() == true && engine?.isAlive == true
    fun proxyServer(): MixedProxyServer? = proxy
    fun engineLogTail(): String = engine?.logTail().orEmpty()

    /**
     * Undo a system-proxy change left behind by a previous run that died without cleaning up.
     * Called on startup — otherwise the machine would be stuck pointing at a dead local port.
     */
    fun recoverFromCrash(): Boolean {
        if (!WindowsSystemProxy.hasPendingRestore()) return false
        PlatformLog.log(LogLevel.WARN, TAG, "previous run left a system proxy override; restoring")
        return WindowsSystemProxy.restore()
    }

    fun start(config: Config, settings: GlobalSettings): Result<StartOutcome> {
        stop()
        val listenPort = settings.listenPort.takeIf { it in 1..65534 } ?: 1080
        val enginePort = listenPort + 1

        return runCatching {
            val spec = engineSpec(config, enginePort)
            val proc = EngineProcess(spec.name, spec.command, spec.workingDir)
            // Before judging the ports: an engine orphaned by a force-kill is the most likely thing
            // holding them, and it is ours to clean up.
            proc.reapStale()

            if (!isPortFree("127.0.0.1", listenPort)) {
                error("port $listenPort is already in use — close the other proxy or change the local port")
            }
            if (!isPortFree("127.0.0.1", enginePort)) {
                error("port $enginePort (engine) is already in use")
            }

            proc.start().getOrThrow()
            engine = proc

            if (!waitForPort("127.0.0.1", enginePort, ENGINE_READY_TIMEOUT_MS)) {
                val tail = proc.logTail().lines().takeLast(12).joinToString("\n")
                proc.stop()
                engine = null
                error("${spec.name} did not start listening on $enginePort.\n$tail")
            }

            val localAuth = settings.localSocksAuthEnabled &&
                settings.localSocksUsername.isNotBlank() && settings.localSocksPassword.isNotBlank()
            val server = MixedProxyServer(
                listenHost = "127.0.0.1",
                listenPort = listenPort,
                upstreamHost = "127.0.0.1",
                upstreamPort = enginePort,
                localUser = if (localAuth) settings.localSocksUsername else null,
                localPass = if (localAuth) settings.localSocksPassword else null,
                maxActiveClients = config.maxActiveClients.coerceAtLeast(16)
            )
            server.start().getOrThrow()
            proxy = server

            var warning: String? = null
            var applied = false
            // Windows has nowhere to put proxy credentials in its proxy setting, so apps routed by
            // it would just get a 407 they cannot answer. On a loopback-only listener the auth buys
            // almost nothing anyway, so say so plainly rather than let it look like a broken tunnel.
            if (localAuth && WindowsSystemProxy.isWindows && !systemProxyDisabled()) {
                warning = "local SOCKS/HTTP auth is on: Windows cannot pass proxy credentials, so " +
                    "system-wide traffic will be rejected with 407. Turn it off in Settings for " +
                    "system-proxy mode."
            }
            if (WindowsSystemProxy.isWindows && !systemProxyDisabled()) {
                val previous = WindowsSystemProxy.apply("127.0.0.1:$listenPort").getOrElse {
                    // The tunnel itself is up; only the system-wide switch failed. Keep running so
                    // the user can still point apps at 127.0.0.1:<port> by hand.
                    warning = "tunnel is up on 127.0.0.1:$listenPort, but the system proxy could not " +
                        "be changed: ${it.message}"
                    null
                }
                if (previous != null) {
                    applied = true
                    systemProxyApplied = true
                    if (previous.enabled && !previous.server.endsWith(":$listenPort")) {
                        warning = listOfNotNull(
                            warning,
                            "system proxy was ${previous.describe()} (another proxy tool) — replaced, " +
                                "and restored on disconnect"
                        ).joinToString(" | ")
                    }
                }
            }

            PlatformLog.log(
                LogLevel.INFO, TAG,
                "connected engine=${spec.name} local=127.0.0.1:$listenPort engine=127.0.0.1:$enginePort " +
                    "systemProxy=$applied"
            )
            StartOutcome(listenPort, spec.name, applied, warning)
        }.onFailure {
            PlatformLog.log(LogLevel.ERROR, TAG, "connect failed: ${it.message}")
            stop()
        }
    }

    fun stop() {
        if (systemProxyApplied || WindowsSystemProxy.hasPendingRestore()) {
            WindowsSystemProxy.restore()
            systemProxyApplied = false
        }
        proxy?.stop()
        proxy = null
        engine?.stop()
        engine = null
    }

    // ---- engine wiring ----

    private data class EngineSpec(
        val name: String,
        val command: List<String>,
        val workingDir: File?
    )

    private fun engineSpec(config: Config, socksPort: Int): EngineSpec = when (config.protocol) {
        Config.TunnelProtocol.S3FU -> {
            val exe = EngineBinaries.require("s3fu")
            val cfg = writeS3fuConfig(config, socksPort)
            EngineSpec("s3fu", listOf(exe.absolutePath, "--client", "--config", cfg.absolutePath), exe.parentFile)
        }
        Config.TunnelProtocol.XRAY -> {
            val exe = EngineBinaries.require("xray")
            val cfg = writeXrayConfig(config, socksPort)
            EngineSpec("xray", listOf(exe.absolutePath, "run", "-c", cfg.absolutePath), exe.parentFile)
        }
        Config.TunnelProtocol.SLIPSTREAM -> error(
            "The Slipstream DNS engine has no Windows build yet — it needs picoquic built with " +
                "MSVC + CMake. Use an S3 or Xray profile on desktop for now."
        )
    }

    private fun engineConfigDir(): File =
        File(AppPaths.filesDir(), "engines").also { it.mkdirs() }

    /** s3fu reads a TOML config; see `crates/s3fu-core/src/config.rs`. */
    private fun writeS3fuConfig(c: Config, socksPort: Int): File {
        require(c.s3Endpoint.isNotBlank()) { "S3 endpoint is empty" }
        require(c.s3Bucket.isNotBlank()) { "S3 bucket is empty" }
        val toml = buildString {
            appendLine("endpoint = ${tomlString(c.s3Endpoint.trim())}")
            appendLine("bucket = ${tomlString(c.s3Bucket.trim())}")
            appendLine("access_key = ${tomlString(c.s3AccessKey.trim())}")
            appendLine("secret_key = ${tomlString(c.s3SecretKey.trim())}")
            if (c.s3Prefix.isNotBlank()) appendLine("prefix = ${tomlString(c.s3Prefix.trim())}")
            if (c.s3Login.isNotBlank()) appendLine("login = ${tomlString(c.s3Login.trim())}")
            if (c.s3Psk.isNotBlank()) appendLine("psk = ${tomlString(c.s3Psk.trim())}")
            appendLine("socks_listen = ${tomlString("127.0.0.1:$socksPort")}")
        }
        val f = File(engineConfigDir(), "s3fu-client.toml")
        f.writeText(toml)
        return f
    }

    /**
     * Xray runs the profile's own JSON, but its **inbounds are replaced**: the profile was written
     * for the phone, where Xray binds the port apps talk to directly. Here that port belongs to
     * [MixedProxyServer], so Xray is moved to the engine port and given a plain SOCKS5 inbound.
     * Outbounds / routing / dns from the profile are untouched.
     */
    private fun writeXrayConfig(c: Config, socksPort: Int): File {
        require(c.xrayConfigJson.isNotBlank()) { "Xray configuration is empty" }
        val root = runCatching { JSONObject(c.xrayConfigJson) }
            .getOrElse { throw IllegalArgumentException("Xray config is not valid JSON: ${it.message}") }
        require(root.optJSONArray("outbounds")?.length() ?: 0 > 0) {
            "Xray config has no outbounds"
        }
        root.put(
            "inbounds",
            JSONArray().put(
                JSONObject()
                    .put("tag", "socks-in")
                    .put("protocol", "socks")
                    .put("listen", "127.0.0.1")
                    .put("port", socksPort)
                    .put("settings", JSONObject().put("auth", "noauth").put("udp", true))
                    .put(
                        "sniffing",
                        JSONObject()
                            .put("enabled", true)
                            .put("destOverride", JSONArray().put("http").put("tls"))
                    )
            )
        )
        val f = File(engineConfigDir(), "xray-client.json")
        f.writeText(root.toString(2))
        return f
    }

    private fun tomlString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

/**
 * Finds the native engine executables. They are not bundled into the jar — they are separate
 * binaries built by `_wsl_build_s3fu_windows.sh` / `_build_xray_windows.ps1` and dropped next to
 * the app, so the search order goes from most explicit to most convenient.
 */
object EngineBinaries {
    private const val TAG = "EngineBinaries"

    private val exeSuffix: String
        get() = if (WindowsSystemProxy.isWindows) ".exe" else ""

    fun find(name: String): File? {
        val fileName = name + exeSuffix
        val candidates = buildList {
            System.getenv("VAYDNS_ENGINE_DIR")?.let { add(File(it, fileName)) }
            // Next to the running app (installed layout) and its /engines subdir.
            appDir()?.let {
                add(File(it, fileName))
                add(File(File(it, "engines"), fileName))
            }
            add(File(File(AppPaths.filesDir(), "engines"), fileName))
            // Dev convenience: repo checkout layout.
            add(File(System.getProperty("user.dir"), "engines/$fileName"))
        }
        return candidates.firstOrNull { it.isFile && it.canExecute() }
            ?: candidates.firstOrNull { it.isFile }
    }

    fun require(name: String): File = find(name) ?: throw IllegalStateException(
        "engine '$name${exeSuffix}' not found. Put it in ${File(AppPaths.filesDir(), "engines")} " +
            "or set VAYDNS_ENGINE_DIR."
    )

    /** Directory the app was launched from, when it can be determined. */
    private fun appDir(): File? = runCatching {
        val src = DesktopTunnel::class.java.protectionDomain?.codeSource?.location ?: return@runCatching null
        File(src.toURI()).parentFile
    }.getOrNull()

    /** Engines present right now — used by Diagnostics so a missing binary is obvious. */
    fun report(): String = listOf("s3fu", "xray").joinToString("\n") { name ->
        val f = find(name)
        if (f != null) "$name: ${f.absolutePath}" else "$name: NOT FOUND"
    }
}
