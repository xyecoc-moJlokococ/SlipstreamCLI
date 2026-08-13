package app.smugly.desktop

import app.smugly.Config
import app.smugly.TunnelToml
import app.smugly.effectiveCdnfuToml
import app.smugly.effectiveS3fuToml

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
}
