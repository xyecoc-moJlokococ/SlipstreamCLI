package app.smugly.tunnel

import android.content.Context
import app.smugly.util.AppLog
import java.io.File

/**
 * Bridge to `libxray.aar` -- Xray-core wrapped by `gomobile bind`
 * (see xray-mobile/, built from XTLS/Xray-core v26.7.11).
 *
 * Like [S3fuBridge] this needs no per-socket protection: Xray's outbound
 * connections must travel OUTSIDE the VPN, and [app.smugly.service.TinyVpnService]
 * already excludes this package from the tunnel via `addDisallowedApplication`.
 * Xray exposes a local SOCKS5 inbound and hev-socks5-tunnel bridges the TUN onto
 * it, exactly like the other two transports.
 */
object XrayBridge {
    private const val TAG = "XrayBridge"

    /** Direction arguments accepted by [queryStats]. */
    const val UPLINK = "uplink"
    const val DOWNLINK = "downlink"

    @Volatile private var loaded = false
    @Volatile private var initialized = false
    @Volatile private var lastError: String? = null
    @Volatile private var controller: libxray.CoreController? = null

    /** Files Xray resolves `geoip:` / `geosite:` routing rules from. */
    private val GEO_ASSETS = listOf("geoip.dat", "geosite.dat")
    @Volatile private var geoAssetsReady = false
    private val geoLock = Any()

    init {
        try {
            // gomobile's Seq loads libgojni.so from its own static initializer.
            libxray.Libxray.touch()
            loaded = true
            AppLog.i(TAG, "libxray loaded, core=${runCatching { libxray.Libxray.checkVersion() }.getOrNull()}")
        } catch (e: Throwable) {
            AppLog.e(TAG, "libxray load failed", e)
        }
    }

    fun isLoaded(): Boolean = loaded

    /**
     * One-time process setup: hand gomobile the app Context (needed so Xray can
     * read the AAR-bundled geoip.dat/geosite.dat through the asset manager), point
     * Xray at its on-disk asset dir, and route its logs into [AppLog].
     *
     * Safe to call repeatedly.
     */
    @Synchronized
    fun init(context: Context) {
        if (!loaded || initialized) return
        runCatching {
            go.Seq.setContext(context.applicationContext)
            val assetDir = File(context.filesDir, "xray").apply { mkdirs() }
            // Files present here win over the bundled assets, so a user-supplied
            // geoip.dat/geosite.dat can be dropped in without rebuilding the AAR.
            libxray.Libxray.initEnv(assetDir.absolutePath, "")
            libxray.Libxray.setLogHandler(object : libxray.LogHandler {
                override fun logLine(line: String) {
                    AppLog.i(TAG, "xray: ${line.trimEnd()}")
                }
            })
            initialized = true
            AppLog.i(TAG, "xray env initialized assetDir=${assetDir.absolutePath}")
            // ~28 MB of copying on first run and this is called from Application.onCreate, so
            // never inline. Anything that actually needs the files calls ensureGeoAssets and
            // blocks on the same lock.
            Thread({ ensureGeoAssets(context.applicationContext) }, "xray-geo-seed")
                .apply { isDaemon = true }
                .start()
        }.onFailure {
            AppLog.e(TAG, "xray env init failed", it)
            lastError = it.message ?: it.toString()
        }
    }

    /**
     * Make sure geoip.dat / geosite.dat are real files in the on-disk asset dir.
     *
     * libxray's `InitEnv` installs a file reader that falls back to the APK's bundled assets, but
     * that only covers Xray's own filesystem helper. Routing rules take a different path: parsing
     * `geoip:private` or `geosite:category-ru` resolves the asset directory directly and dies with
     * "failed to open geosite.dat > stat …/files/xray/geosite.dat: no such file or directory" —
     * which is why every subscription config failed to start while hand-made ones (no routing
     * rules) worked. Panels use those rules routinely, so the files have to exist on disk.
     *
     * Only fills in what is missing, so a user-supplied .dat dropped into the dir still wins.
     * Blocks for the length of the copy — call it off the main thread.
     */
    fun ensureGeoAssets(context: Context) {
        if (geoAssetsReady) return
        synchronized(geoLock) {
            if (geoAssetsReady) return
            val dir = File(context.filesDir, "xray").apply { mkdirs() }
            for (name in GEO_ASSETS) {
                val target = File(dir, name)
                if (target.length() > 0) continue
                runCatching {
                    // Through a temp file: a copy cut short by a kill would otherwise leave a
                    // truncated .dat that looks present and then fails to parse forever.
                    val tmp = File(dir, "$name.part")
                    context.assets.open(name).use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                    check(tmp.renameTo(target)) { "could not rename ${tmp.name}" }
                    AppLog.i(TAG, "seeded $name (${target.length()} bytes)")
                }.onFailure {
                    // A build made with XRAY_NO_GEO=1 has no such asset; Xray then reports the
                    // missing file itself, which is the same story the user would get anyway.
                    AppLog.e(TAG, "could not seed $name", it)
                }
            }
            geoAssetsReady = true
        }
    }

    /** Xray-core version string, or null when the library failed to load. */
    fun version(): String? =
        if (loaded) runCatching { libxray.Libxray.checkVersion() }.getOrNull() else null

    /**
     * Parse [configJson] exactly as [startClient] would, without starting anything.
     * Returns the core's own error message, or null when the config is valid.
     */
    fun validateConfig(configJson: String): String? {
        if (!loaded) return "libxray is not loaded"
        return runCatching { libxray.Libxray.testConfig(configJson) }
            .fold(onSuccess = { null }, onFailure = { it.message ?: it.toString() })
    }

    /**
     * Start the core with [configJson]. The config must contain a SOCKS inbound
     * for the TUN bridge to attach to -- callers go through
     * [app.smugly.XrayConfigBuilder.withSocksPort] to guarantee that.
     */
    fun startClient(configJson: String): Result<Unit> {
        if (!loaded) return Result.failure(IllegalStateException("libxray is not loaded"))
        if (!initialized) return Result.failure(IllegalStateException("XrayBridge.init was not called"))
        stopClient() // a stale instance would hold the SOCKS port
        lastError = null

        val handler = object : libxray.CoreCallbackHandler {
            override fun startup(): Long = 0
            override fun shutdown(): Long = 0
            override fun onEmitStatus(code: Long, message: String): Long {
                AppLog.i(TAG, "core status code=$code $message")
                return 0
            }
        }

        val core = runCatching { libxray.Libxray.newCoreController(handler) }.getOrElse {
            AppLog.e(TAG, "newCoreController failed", it)
            lastError = it.message ?: it.toString()
            return Result.failure(it)
        }

        return runCatching { core.startLoop(configJson) }
            .fold(
                onSuccess = {
                    controller = core
                    AppLog.i(TAG, "xray started")
                    Result.success(Unit)
                },
                onFailure = {
                    lastError = it.message ?: it.toString()
                    AppLog.e(TAG, "xray start failed: $lastError")
                    Result.failure(it)
                }
            )
    }

    fun stopClient() {
        val core = controller ?: return
        controller = null
        AppLog.i(TAG, "stop xray")
        runCatching { core.stopLoop() }
            .onFailure { AppLog.w(TAG, "xray stop failed: ${it.message}") }
    }

    fun isRunning(): Boolean =
        controller?.let { core -> runCatching { core.isRunning }.getOrDefault(false) } ?: false

    fun lastError(): String? = lastError

    /**
     * Read and RESET one outbound traffic counter, in bytes.
     * [direct] is [UPLINK] or [DOWNLINK]; requires `stats` + `policy.system` in
     * the config (the generated ones have both).
     */
    fun queryStats(tag: String, direct: String): Long =
        controller?.let { core -> runCatching { core.queryStats(tag, direct) }.getOrDefault(0L) } ?: 0L
}
