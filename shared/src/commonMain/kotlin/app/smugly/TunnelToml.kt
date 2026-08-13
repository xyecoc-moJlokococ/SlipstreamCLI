package app.smugly

/**
 * Client configs for the Rust tunnels, as the TOML their engines actually read.
 *
 * The app used to keep one Kotlin field per setting and hand them to the engine as
 * separate JNI parameters, so a profile could only express the handful of knobs someone
 * had remembered to plumb through — a small fraction of what the config format supports.
 * The profile now carries the config file itself and the engine parses it, which makes
 * "what the app can set" and "what the engine understands" the same set for good.
 *
 * [forS3fu] / [forCdnfu] render a config from the legacy per-field values. That is how
 * profiles made by older builds (and links that still carry query parameters) keep
 * working: nothing is rewritten on disk, the text is simply derived when the stored one
 * is blank. The moment the user edits the box, their text wins — see [effectiveS3fuToml].
 */
object TunnelToml {

    /**
     * Return [toml] with a top-level `key = value` of our own, replacing whatever the
     * config said. Used for the local listen address: that belongs to the host process
     * (it picks the port and points the TUN at it), not to the operator's config.
     *
     * TOML rejects a duplicate key outright, so an existing assignment is commented out
     * rather than left to collide. Only lines before the first `[table]` are considered —
     * a `listen` inside some section means something else.
     */
    fun withForcedTopLevelKey(toml: String, key: String, value: String): String {
        val out = StringBuilder()
        out.append(key).append(" = ").append(q(value)).append('\n')
        var inTable = false
        for (line in toml.split("\n")) {
            val trimmed = line.trim()
            if (!inTable && trimmed.startsWith("[")) inTable = true
            val isOurs = !inTable && !trimmed.startsWith("#") &&
                trimmed.substringBefore('=').trim().trim('"', '\'') == key &&
                trimmed.contains('=')
            if (isOurs) out.append("# ").append(line).append('\n') else out.append(line).append('\n')
        }
        return out.toString()
    }

    /** Quote a value as a TOML basic string. */
    private fun q(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }

    fun forS3fu(c: Config): String = buildString {
        appendLine("# s3-fuckup client config. Everything s3fu's config file supports works here;")
        appendLine("# see config.example.toml in the s3-fuckup repo for the full list.")
        appendLine("# socks_listen is set by the app and ignored here.")
        appendLine("endpoint   = ${q(c.s3Endpoint)}")
        appendLine("bucket     = ${q(c.s3Bucket)}")
        appendLine("access_key = ${q(c.s3AccessKey)}")
        appendLine("secret_key = ${q(c.s3SecretKey)}")
        appendLine("prefix     = ${q(c.s3Prefix.ifBlank { "s3fu" })}")
        appendLine("psk        = ${q(c.s3Psk)}")
        appendLine()
        appendLine("# A client must not delete: the server reaps the drop, and a client that")
        appendLine("# deletes objects the peer has not read yet wedges the session.")
        appendLine("allow_delete = false")
    }

    /**
     * "auto" on a phone used to leave GET/query defaults that fight cookie stealth and
     * multipath, so the service pinned the lab-proven shape instead. That coercion now
     * happens here, when a legacy profile's fields are turned into a config — otherwise
     * migrating one would quietly change how it behaves on the wire.
     */
    private fun knob(raw: String, fallback: String): String {
        val v = raw.trim()
        return if (v.isEmpty() || v.equals("auto", ignoreCase = true)) fallback else v
    }

    fun forCdnfu(c: Config): String = buildString {
        appendLine("# cdn-fuckup client config. Everything cdnfu's config file supports works here;")
        appendLine("# see configs/client.toml in the cdn-fuckup repo for the full list.")
        appendLine("# listen is set by the app and ignored here.")
        appendLine("url  = ${q(c.cdnfuUrl)}")
        // Host is what the edge is addressed as when url points at an IP; blank = take it
        // from the url itself.
        appendLine("host = ${q(c.cdnfuHost)}")
        appendLine("psk  = ${q(c.cdnfuPsk)}")
        appendLine()
        appendLine("[path]")
        appendLine("mimic = ${q(knob(c.cdnfuMimic, "mixed"))}")
        appendLine()
        appendLine("[uplink]")
        appendLine("method = ${q(knob(c.cdnfuUplinkMethod, "POST"))}")
        appendLine("path   = ${q(knob(c.cdnfuUplinkPath, "api"))}")
        appendLine("data   = ${q(knob(c.cdnfuUplinkData, "body"))}")
        // Uplink depth is what upload speed rides on: every post costs a round trip
        // through the edge, and only overlapping them amortises it.
        appendLine("pipeline = 16")
        appendLine()
        appendLine("[downlink]")
        appendLine("mode = ${q(knob(c.cdnfuDownlinkMode, "stream"))}")
        appendLine("udp_mode = \"stream\"")
        appendLine("poll_depth = 2")
        appendLine("udp_poll_depth = 8")
        // The tunnel's NAT lease: dropping the session drops the server's UDP socket, so a
        // hole-punched peer (Parsec, WebRTC) is left sending to a port that no longer exists.
        appendLine("udp_idle_secs = 90")
        appendLine()
        appendLine("[xhttp]")
        val placement = knob(c.cdnfuXhttpPlacement, "cookie")
        appendLine("session_placement = ${q(placement)}")
        appendLine("seq_placement     = ${q(placement)}")
        appendLine("pad_placement     = ${q(placement)}")
        appendLine("data_placement    = ${q(placement)}")
        appendLine()
        appendLine("[multipath]")
        // 0/1 = single path. Striping holds a pool permit per path for a whole TCP flow,
        // so a high count deadlocks as soon as an app opens many sockets at once.
        appendLine("paths = ${if (c.cdnfuMultipath <= 0) 1 else c.cdnfuMultipath}")
        appendLine()
        appendLine("[pool]")
        // 0 = unlimited concurrent packet-up sessions. A hard cap starves browsers and
        // speed tests (parallel TLS + DoH + ads) and freezes every flow.
        appendLine("size = 0")
        appendLine()
        appendLine("[tls]")
        appendLine("chrome = 137")
        // h1 bodies are buffered by reverse-proxy edges, h2 frames are not — this one flag
        // silently killed every streaming downlink.
        appendLine("http1_only = false")
    }
}

/**
 * The s3fu config this profile actually runs: the user's own text once they have edited
 * it, otherwise one derived from the legacy fields so old profiles keep working.
 */
fun Config.effectiveS3fuToml(): String =
    s3fuToml.ifBlank { TunnelToml.forS3fu(this) }

/** As [effectiveS3fuToml], for cdnfu. */
fun Config.effectiveCdnfuToml(): String =
    cdnfuToml.ifBlank { TunnelToml.forCdnfu(this) }
