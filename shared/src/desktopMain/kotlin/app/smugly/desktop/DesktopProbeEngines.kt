package app.smugly.desktop

import app.smugly.Config
import app.smugly.DnsResolverPool
import app.smugly.XrayConfigBuilder
import app.smugly.net.E2ELatencyProbe
import app.smugly.platform.AppPaths
import java.io.File

/**
 * Starts a profile's engine as a throwaway child process so [E2ELatencyProbe] can time a request
 * through it.
 *
 * Same binaries the real tunnel uses, but nothing is shared with it: its own port, its own config
 * file, its own process. That is what lets a latency check run while a tunnel is up — writing to
 * [DesktopTunnel]'s `xray-client.json` instead would rewrite the live tunnel's config under it.
 */
class DesktopProbeEngines(
    private val resolverPool: () -> String = { DnsResolverPool.DEFAULT_RAW }
) : E2ELatencyProbe.Launcher {

    override fun supports(protocol: Config.TunnelProtocol): Boolean = when (protocol) {
        Config.TunnelProtocol.XRAY -> EngineBinaries.find("xray") != null
        Config.TunnelProtocol.S3FU -> EngineBinaries.find("s3fu") != null
        Config.TunnelProtocol.CDNFU -> EngineBinaries.find("cdnfu") != null
        Config.TunnelProtocol.SLIPSTREAM -> EngineBinaries.findSlipstream() != null
    }

    // A child process has to be spawned and has to bind its listener; xray is quick, the tunnels
    // spend the time on their first handshake.
    override fun readyTimeoutMs(protocol: Config.TunnelProtocol): Long =
        if (protocol == Config.TunnelProtocol.XRAY) 8_000 else 15_000

    override fun launch(config: Config, socksPort: Int): E2ELatencyProbe.Session {
        val spec = spec(config, socksPort)
        // Port in the name so two probes running side by side get their own pid file and their own
        // log tag, and neither can reap the other.
        val process = EngineProcess("${spec.name}-probe-$socksPort", spec.command, spec.workingDir)
        process.start().getOrThrow()
        return object : E2ELatencyProbe.Session {
            override fun awaitReady(timeoutMs: Long): Boolean =
                waitForPort("127.0.0.1", socksPort, timeoutMs)

            override fun close() {
                process.stop()
                runCatching { spec.configFile.delete() }
            }
        }
    }

    private class Spec(
        val name: String,
        val command: List<String>,
        val workingDir: File?,
        val configFile: File
    )

    private fun spec(config: Config, socksPort: Int): Spec = when (config.protocol) {
        Config.TunnelProtocol.XRAY -> {
            val exe = EngineBinaries.require("xray")
            require(config.xrayConfigJson.isNotBlank()) { "the profile has no Xray configuration" }
            // Panel configs route on geoip: / geosite: tags, and those refuse to parse without the
            // real files — a probe would then fail for a reason that is not the profile's fault.
            DesktopTunnel.ensureXrayGeodata(exe.parentFile)
            // Inbounds replaced wholesale: the profile's own ports belong to the running tunnel.
            val file = probeConfigFile("xray-probe-$socksPort.json")
            file.writeText(XrayConfigBuilder.withOnlySocksInbound(config.xrayConfigJson, socksPort))
            // workingDir = engines/: xray resolves geoip.dat / geosite.dat relative to cwd.
            Spec("xray", listOf(exe.absolutePath, "run", "-c", file.absolutePath), exe.parentFile, file)
        }
        Config.TunnelProtocol.S3FU -> {
            val exe = EngineBinaries.require("s3fu")
            val file = probeConfigFile("s3fu-probe-$socksPort.toml")
            file.writeText(DesktopEngineConfigs.s3fu(config, socksPort))
            Spec("s3fu", listOf(exe.absolutePath, "--client", "--config", file.absolutePath), exe.parentFile, file)
        }
        Config.TunnelProtocol.CDNFU -> {
            val exe = EngineBinaries.require("cdnfu")
            val file = probeConfigFile("cdnfu-probe-$socksPort.toml")
            file.writeText(DesktopEngineConfigs.cdnfu(config, socksPort))
            Spec("cdnfu", listOf(exe.absolutePath, "--config", file.absolutePath), exe.parentFile, file)
        }
        Config.TunnelProtocol.SLIPSTREAM -> {
            val exe = EngineBinaries.requireSlipstream()
            val file = probeConfigFile("slipstream-probe-$socksPort.json")
            file.writeText(DesktopEngineConfigs.slipstream(config, socksPort, resolverPool()))
            Spec("slipstream", listOf(exe.absolutePath, "--config", file.absolutePath), exe.parentFile, file)
        }
    }

    /** Named after the port so two probes at once cannot overwrite each other's config. */
    private fun probeConfigFile(name: String): File =
        File(File(AppPaths.filesDir(), "engines").also { it.mkdirs() }, name)
}
