package app.vaydns.desktop

/**
 * Thin redirect: the real desktop entry is [app.vaydns.ui.main] in `:shared`
 * (Compose Multiplatform window hosting the same UI as Android/iOS).
 *
 * Kept so older scripts that call `app.vaydns.desktop.MainKt` still work when
 * pointed at the compose main via desktop/build.gradle.kts.
 */
fun main(args: Array<String>) {
    // CLI helpers without GUI:
    if (args.isNotEmpty()) {
        app.vaydns.desktop.cliMain(args)
        return
    }
    app.vaydns.ui.main()
}

private fun cliMain(args: Array<String>) {
    // Re-export previous CLI flags for headless use.
    val platform = app.vaydns.currentHostPlatform()
    when {
        args.contains("--print-platform") -> println(platform.name)
        args.contains("--write-default-config") -> {
            val path = args.getOrNull(args.indexOf("--write-default-config") + 1)
                ?: java.io.File(app.vaydns.platform.AppPaths.filesDir(), "default-profile.json").absolutePath
            val mode = app.vaydns.Config.Mode.PROXY
            val json = app.vaydns.ConfigJson.configToString(
                app.vaydns.defaultConfig(listenPort = 1080, mode = mode)
            )
            java.io.File(path).apply { parentFile?.mkdirs() }.writeText(json)
            println("wrote $path")
        }
        args.contains("--validate-config") -> {
            val path = args.getOrNull(args.indexOf("--validate-config") + 1)
                ?: error("usage: --validate-config <file.json>")
            val config = app.vaydns.ConfigJson.configFromString(java.io.File(path).readText())
            println("OK protocol=${config.protocol} domain=${config.domain} port=${config.listenPort}")
        }
        else -> println("Unknown args. Use --print-platform | --write-default-config | --validate-config, or no args for GUI.")
    }
}
