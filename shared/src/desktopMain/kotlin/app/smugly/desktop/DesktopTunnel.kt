package app.smugly.desktop

import app.smugly.Config
import app.smugly.GlobalSettings
import app.smugly.platform.AppPaths
import app.smugly.platform.LogLevel
import app.smugly.platform.PlatformLog
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

    /** How long a just-killed engine is given to hand its listener back before we call it taken. */
    private const val PORT_RELEASE_TIMEOUT_MS = 4_000L
    /**
     * Concurrent client cap for the local mixed proxy when the engine is **not** Slipstream.
     * `Config.maxActiveClients` (default 40) is a DNS-tunnel memory guard for Slipstream only —
     * applying it to Xray/S3 made browsers hit "connection limit 40 reached; dropped" under
     * normal multi-tab load. 512 covers multi-tab browsers with headroom; 4096 reserved
     * ~400+ MB of relay ByteBuffers for connections that almost never exist.
     */
    private const val NON_SLIPSTREAM_MAX_CLIENTS = 512

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
        System.getenv("SMUGLY_NO_SYSTEM_PROXY")?.lowercase() in setOf("1", "true", "yes")

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

            // Wait, don't just look: the engine this call replaces was killed a moment ago and the
            // OS hands its listener back slightly later, so an instant check failed on a port that
            // was about to be free — which is exactly what made switching profiles report
            // "port 1081 (engine) is already in use" now and then.
            if (!waitForPortFree("127.0.0.1", listenPort, PORT_RELEASE_TIMEOUT_MS)) {
                error("port $listenPort is already in use — close the other proxy or change the local port")
            }
            if (!waitForPortFree("127.0.0.1", enginePort, PORT_RELEASE_TIMEOUT_MS)) {
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

            // No local auth on desktop by design: the listener is loopback-only, and the system
            // proxy setting has nowhere to carry credentials, so requiring them would just turn
            // every system-routed request into a 407 nobody can answer.
            //
            // maxActiveClients from the profile is Slipstream-only (DNS tunnel pressure). Xray /
            // s3fu gets a high local-proxy ceiling so multi-tab browsers are not dropped.
            val proxyLimit = when (config.protocol) {
                Config.TunnelProtocol.SLIPSTREAM ->
                    config.maxActiveClients.coerceAtLeast(1)
                else -> NON_SLIPSTREAM_MAX_CLIENTS
            }
            val server = MixedProxyServer(
                listenHost = "127.0.0.1",
                listenPort = listenPort,
                upstreamHost = "127.0.0.1",
                upstreamPort = enginePort,
                maxActiveClients = proxyLimit
            )
            server.start().getOrThrow()
            proxy = server

            var warning: String? = null
            var applied = false
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
        Config.TunnelProtocol.CDNFU -> {
            val exe = EngineBinaries.require("cdnfu")
            val cfg = writeCdnfuConfig(config, socksPort)
            EngineSpec("cdnfu", listOf(exe.absolutePath, "--config", cfg.absolutePath), exe.parentFile)
        }
        Config.TunnelProtocol.XRAY -> {
            val exe = EngineBinaries.require("xray")
            ensureXrayGeodata(exe.parentFile)
            val cfg = writeXrayConfig(config, socksPort)
            // workingDir = engines/: xray resolves geoip.dat / geosite.dat relative to cwd.
            EngineSpec("xray", listOf(exe.absolutePath, "run", "-c", cfg.absolutePath), exe.parentFile)
        }
        Config.TunnelProtocol.SLIPSTREAM -> error(
            "The Slipstream DNS engine has no Windows build yet — it needs picoquic built with " +
                "MSVC + CMake. Use an S3, CDN, or Xray profile on desktop for now."
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
            if (c.s3Psk.isNotBlank()) appendLine("psk = ${tomlString(c.s3Psk.trim())}")
            appendLine("socks_listen = ${tomlString("127.0.0.1:$socksPort")}")
        }
        val f = File(engineConfigDir(), "s3fu-client.toml")
        f.writeText(toml)
        return f
    }

    /** cdnfu client TOML — mirrors configs/client.toml shape. */
    private fun writeCdnfuConfig(c: Config, socksPort: Int): File {
        require(c.cdnfuUrl.isNotBlank()) { "URL is empty" }
        require(c.cdnfuPsk.isNotBlank()) { "CDN PSK is empty" }
        val placement = c.cdnfuXhttpPlacement.trim().ifBlank { "cookie" }
        val toml = buildString {
            appendLine("listen = ${tomlString("127.0.0.1:$socksPort")}")
            appendLine("url = ${tomlString(c.cdnfuUrl.trim())}")
            appendLine("psk = ${tomlString(c.cdnfuPsk.trim())}")
            appendLine()
            appendLine("[path]")
            appendLine("mimic = ${tomlString(c.cdnfuMimic.trim().ifBlank { "mixed" })}")
            appendLine()
            appendLine("[uplink]")
            appendLine("method = ${tomlString(c.cdnfuUplinkMethod.trim().ifBlank { "POST" })}")
            appendLine("path = ${tomlString(c.cdnfuUplinkPath.trim().ifBlank { "api" })}")
            appendLine("data = ${tomlString(c.cdnfuUplinkData.trim().ifBlank { "body" })}")
            appendLine("pipeline = 8")
            appendLine()
            appendLine("[xhttp]")
            appendLine("session_placement = ${tomlString(placement)}")
            appendLine("seq_placement = ${tomlString(placement)}")
            appendLine("pad_placement = ${tomlString(placement)}")
            appendLine("data_placement = ${tomlString(placement)}")
            appendLine()
            appendLine("[downlink]")
            appendLine("mode = ${tomlString(c.cdnfuDownlinkMode.trim().ifBlank { "poll" })}")
            appendLine()
            appendLine("[tls]")
            appendLine("chrome = 137")
            appendLine("http1_only = true")
            appendLine()
            appendLine("[multipath]")
            appendLine("paths = ${c.cdnfuMultipath.coerceIn(1, 32).takeIf { c.cdnfuMultipath > 0 } ?: 4}")
            appendLine()
            appendLine("[pool]")
            appendLine("size = 32")
        }
        val f = File(engineConfigDir(), "cdnfu-client.toml")
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

    /**
     * Profiles from panels almost always use `geoip:` / `geosite:` routing tags. Xray loads
     * `geoip.dat` and `geosite.dat` from its working directory (the engines folder). Missing
     * files used to fail start with exit 23 and a hard-to-read geodata path error.
     *
     * If they are absent next to the binary, copy from the repo assets / known locations so a
     * fresh install or an incomplete package still works once.
     */
    private fun ensureXrayGeodata(enginesDir: File?) {
        if (enginesDir == null) return
        enginesDir.mkdirs()
        for (name in listOf("geoip.dat", "geosite.dat")) {
            val dest = File(enginesDir, name)
            if (dest.isFile && dest.length() > 1024L) continue
            val src = EngineBinaries.findAsset(name) ?: continue
            runCatching {
                src.copyTo(dest, overwrite = true)
                PlatformLog.log(
                    app.smugly.platform.LogLevel.INFO,
                    "DesktopTunnel",
                    "seeded $name -> ${dest.absolutePath}"
                )
            }
        }
        val missing = listOf("geoip.dat", "geosite.dat")
            .filter { !File(enginesDir, it).isFile }
        if (missing.isNotEmpty()) {
            PlatformLog.log(
                app.smugly.platform.LogLevel.WARN,
                "DesktopTunnel",
                "Xray geodata missing in ${enginesDir.absolutePath}: ${missing.joinToString()}. " +
                    "Profiles with geoip:/geosite: rules will fail until these files are placed next to xray.exe."
            )
        }
    }
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
            System.getenv("SMUGLY_ENGINE_DIR")?.let { add(File(it, fileName)) }
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

    /**
     * Locate a non-executable asset that rides next to the engines (geoip.dat, geosite.dat).
     * Same search order as [find], plus the repo's `xray-mobile/assets/` for local builds.
     */
    fun findAsset(fileName: String): File? {
        val candidates = buildList {
            System.getenv("SMUGLY_ENGINE_DIR")?.let { add(File(it, fileName)) }
            appDir()?.let {
                add(File(it, fileName))
                add(File(File(it, "engines"), fileName))
            }
            add(File(File(AppPaths.filesDir(), "engines"), fileName))
            add(File(System.getProperty("user.dir"), "engines/$fileName"))
            // Checked out alongside the project.
            add(File(System.getProperty("user.dir"), "xray-mobile/assets/$fileName"))
            // jpackage layout: app/ is cwd-ish; sources live two levels up only in the repo.
            appDir()?.parentFile?.parentFile?.let {
                add(File(it, "xray-mobile/assets/$fileName"))
                add(File(it, "engines/$fileName"))
            }
        }
        return candidates.firstOrNull { it.isFile && it.length() > 1024L }
    }

    fun require(name: String): File = find(name) ?: throw IllegalStateException(
        "engine '$name${exeSuffix}' not found. Put it in ${File(AppPaths.filesDir(), "engines")} " +
            "or set SMUGLY_ENGINE_DIR."
    )

    /** Directory the app was launched from, when it can be determined. */
    private fun appDir(): File? = runCatching {
        val src = DesktopTunnel::class.java.protectionDomain?.codeSource?.location ?: return@runCatching null
        File(src.toURI()).parentFile
    }.getOrNull()

    /** Engines present right now — used by Diagnostics so a missing binary is obvious. */
    fun report(): String = buildString {
        listOf("s3fu", "cdnfu", "xray").forEach { name ->
            val f = find(name)
            appendLine(if (f != null) "$name: ${f.absolutePath}" else "$name: NOT FOUND")
        }
        listOf("geoip.dat", "geosite.dat").forEach { name ->
            val f = findAsset(name)
            append(if (f != null) "$name: ${f.absolutePath}" else "$name: NOT FOUND")
            appendLine()
        }
    }
}
