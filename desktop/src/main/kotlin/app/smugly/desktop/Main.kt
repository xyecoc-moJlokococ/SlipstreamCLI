package app.smugly.desktop

/**
 * Thin redirect: the real desktop entry is [app.smugly.ui.main] in `:shared`
 * (Compose Multiplatform window hosting the same UI as Android/iOS).
 *
 * Kept so older scripts that call `app.smugly.desktop.MainKt` still work when
 * pointed at the compose main via desktop/build.gradle.kts.
 */
fun main(args: Array<String>) {
    // CLI helpers without GUI:
    if (args.isNotEmpty()) {
        app.smugly.desktop.cliMain(args)
        return
    }
    app.smugly.ui.main()
}

private fun cliMain(args: Array<String>) {
    // Re-export previous CLI flags for headless use.
    val platform = app.smugly.currentHostPlatform()
    when {
        args.contains("--print-platform") -> println(platform.name)
        args.contains("--write-default-config") -> {
            val path = args.getOrNull(args.indexOf("--write-default-config") + 1)
                ?: java.io.File(app.smugly.platform.AppPaths.filesDir(), "default-profile.json").absolutePath
            val mode = app.smugly.Config.Mode.PROXY
            val json = app.smugly.ConfigJson.configToString(
                app.smugly.defaultConfig(listenPort = 1080, mode = mode)
            )
            java.io.File(path).apply { parentFile?.mkdirs() }.writeText(json)
            println("wrote $path")
        }
        args.contains("--validate-config") -> {
            val path = args.getOrNull(args.indexOf("--validate-config") + 1)
                ?: error("usage: --validate-config <file.json>")
            val config = app.smugly.ConfigJson.configFromString(java.io.File(path).readText())
            println("OK protocol=${config.protocol} domain=${config.domain} port=${config.listenPort}")
        }
        args.contains("--engines") -> println(EngineBinaries.report())
        args.contains("--connect") -> runHeadless()
        args.contains("--latency") ->
            measureLatency(args.getOrNull(args.indexOf("--latency") + 1))
        args.contains("--latency-config") -> {
            val path = args.getOrNull(args.indexOf("--latency-config") + 1)
                ?: error("usage: --latency-config <file.json>")
            printLatency(
                java.io.File(path).name,
                app.smugly.ConfigJson.configFromString(java.io.File(path).readText())
            )
        }
        args.contains("--show-system-proxy") -> {
            println("current: " + WindowsSystemProxy.snapshot().describe())
            println("pending restore: " + WindowsSystemProxy.hasPendingRestore())
        }
        // Recovery for the one case a shutdown hook cannot cover: the app was force-killed
        // (Task Manager / power loss) while it owned the system proxy, so the machine is left
        // pointing at a local port that no longer listens.
        args.contains("--restore-system-proxy") -> {
            if (!WindowsSystemProxy.hasPendingRestore()) {
                println("nothing to restore; current: " + WindowsSystemProxy.snapshot().describe())
            } else if (WindowsSystemProxy.restore()) {
                println("restored: " + WindowsSystemProxy.snapshot().describe())
            } else {
                println("restore FAILED — check the log")
            }
        }
        else -> println(
            "Unknown args. Use --print-platform | --write-default-config | --validate-config | " +
                "--engines | --connect | --latency [name] | --latency-config <file.json> | " +
                "--show-system-proxy | --restore-system-proxy, or no args for GUI."
        )
    }
}

/**
 * The same end-to-end probe the server rows use, printed with the reason a failure failed — which
 * a row can only render as a dash. Runs the profile's engine on a throwaway port, so it neither
 * touches a running tunnel nor the machine's proxy settings.
 *
 * `--latency` alone measures every stored profile; a name (or part of one) narrows it down.
 */
private fun measureLatency(nameFilter: String?) {
    val filter = nameFilter?.takeUnless { it.startsWith("--") }
    val profiles = app.smugly.ui.FileProfileStore().loadProfiles()
        .filter { filter == null || it.name.contains(filter, ignoreCase = true) }
    if (profiles.isEmpty()) {
        println(if (filter == null) "no profiles configured" else "no profile matches '$filter'")
        return
    }
    profiles.forEach { printLatency(it.name, it.config) }
}

private fun printLatency(name: String, config: app.smugly.Config) {
    val started = System.currentTimeMillis()
    val result = app.smugly.net.E2ELatencyProbe.measure(config, DesktopProbeEngines())
    val took = System.currentTimeMillis() - started
    result.fold(
        onSuccess = { println("%-34s %-10s %5d ms   (probe took %d ms)".format(name, config.protocol, it, took)) },
        onFailure = { println("%-34s %-10s      —   %s".format(name, config.protocol, it.message ?: it::class.java.simpleName)) }
    )
}

/**
 * Run the tunnel without a window, using the active profile, until Ctrl+C.
 *
 * Set `SMUGLY_NO_SYSTEM_PROXY=1` to keep the machine's proxy settings untouched and expose the
 * tunnel only as a local proxy on 127.0.0.1:<listenPort>.
 */
private fun runHeadless() {
    val store = app.smugly.ui.FileProfileStore()
    val settings = store.loadGlobalSettings()
    app.smugly.platform.PlatformLog.fileLoggingEnabled = settings.fileLogging
    val profiles = store.loadProfiles()
    val active = profiles.firstOrNull { it.id == store.loadActiveProfileId() } ?: profiles.firstOrNull()
    if (active == null) {
        println("no profiles configured")
        return
    }
    println("connecting profile='${active.name}' protocol=${active.config.protocol}")
    val outcome = DesktopTunnel.start(active.config, settings).getOrElse {
        println("FAILED: ${it.message}")
        return
    }
    outcome.warning?.let { println("WARNING: $it") }
    println("READY engine=${outcome.engineName} listen=127.0.0.1:${outcome.listenPort} systemProxy=${outcome.systemProxyApplied}")
    Runtime.getRuntime().addShutdownHook(Thread { DesktopTunnel.stop() })
    while (DesktopTunnel.isRunning) {
        Thread.sleep(1000)
        val p = DesktopTunnel.proxyServer() ?: break
        println("conns=${p.activeConnections()} ok=${p.connectOkCount()} fail=${p.connectFailCount()} rx=${p.rxBytes()} tx=${p.txBytes()}")
    }
    println("engine stopped")
    DesktopTunnel.stop()
}
