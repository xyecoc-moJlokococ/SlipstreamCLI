package app.smugly.desktop

import app.smugly.platform.AppPaths
import app.smugly.platform.LogLevel
import app.smugly.platform.PlatformLog
import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Windows per-user proxy settings (`HKCU\...\Internet Settings`), the ones the Settings app calls
 * "Manual proxy setup" and that WinINET-based software (Edge, Chrome, most installers/updaters)
 * follows.
 *
 * These keys are global to the user and **shared with every other proxy tool** — Throne, v2rayN,
 * Clash and friends all write the same four values. Whoever writes last wins, and none of them
 * coordinate. So this class is deliberately conservative:
 *
 *  - [snapshot] the existing values before touching anything, and persist that snapshot to disk
 *    (not just memory) so a crash or force-quit can still be undone on the next launch.
 *  - Never invent a bypass list: the user's existing [ProxySnapshot.override] is carried over, since
 *    it usually holds hard-won entries (Steam CDN domains and the like).
 *  - [restore] puts back exactly what was there, including "proxy was off".
 *
 * Note the settings only take effect for already-running apps after WinINET is notified, which the
 * registry write alone does not do — see [notifyWinInet].
 */
object WindowsSystemProxy {
    private const val TAG = "WindowsSystemProxy"
    private const val KEY = """HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings"""
    private const val BACKUP_FILE = "system-proxy-backup.json"

    val isWindows: Boolean
        get() = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    data class ProxySnapshot(
        val enabled: Boolean,
        val server: String,
        val override: String,
        val autoConfigUrl: String
    ) {
        fun toJson(): String = JSONObject()
            .put("enabled", enabled)
            .put("server", server)
            .put("override", override)
            .put("autoConfigUrl", autoConfigUrl)
            .toString(2)

        /** Human-readable form for warnings ("127.0.0.1:2080" / "off"). */
        fun describe(): String = when {
            !enabled && autoConfigUrl.isBlank() -> "off"
            autoConfigUrl.isNotBlank() -> "auto-config $autoConfigUrl"
            else -> server.ifBlank { "on (no server)" }
        }

        companion object {
            fun fromJson(text: String): ProxySnapshot? = runCatching {
                val o = JSONObject(text)
                ProxySnapshot(
                    enabled = o.optBoolean("enabled", false),
                    server = o.optString("server", ""),
                    override = o.optString("override", ""),
                    autoConfigUrl = o.optString("autoConfigUrl", "")
                )
            }.getOrNull()
        }
    }

    private val backupFile: File get() = File(AppPaths.filesDir(), BACKUP_FILE)

    // ---- read ----

    fun snapshot(): ProxySnapshot = ProxySnapshot(
        enabled = readValue("ProxyEnable")?.let { parseDword(it) != 0 } ?: false,
        server = readValue("ProxyServer").orEmpty(),
        override = readValue("ProxyOverride").orEmpty(),
        autoConfigUrl = readValue("AutoConfigURL").orEmpty()
    )

    private fun readValue(name: String): String? {
        val out = runCommand(listOf("reg", "query", KEY, "/v", name)) ?: return null
        // "    ProxyServer    REG_SZ    127.0.0.1:2080"
        val line = out.lineSequence().firstOrNull { it.trimStart().startsWith(name, ignoreCase = true) }
            ?: return null
        val parts = line.trim().split(Regex("\\s{2,}"))
        return if (parts.size >= 3) parts.drop(2).joinToString("    ") else ""
    }

    private fun parseDword(raw: String): Int = runCatching {
        if (raw.startsWith("0x", ignoreCase = true)) raw.substring(2).toInt(16) else raw.toInt()
    }.getOrDefault(0)

    // ---- write ----

    /**
     * Point the system proxy at [server] (e.g. "127.0.0.1:1080"), preserving the user's existing
     * bypass list. Returns the snapshot taken *before* the change so the caller can report what was
     * replaced; the same snapshot is written to disk for crash recovery.
     */
    fun apply(server: String, extraBypass: List<String> = emptyList()): Result<ProxySnapshot> {
        if (!isWindows) return Result.failure(IllegalStateException("system proxy is Windows-only"))
        val previous = snapshot()
        return runCatching {
            // Persist BEFORE mutating: if we die between the two writes, the next launch restores.
            if (!backupFile.exists()) backupFile.writeText(previous.toJson())

            val bypass = mergeBypass(previous.override, extraBypass)
            setValue("ProxyServer", "REG_SZ", server)
            setValue("ProxyOverride", "REG_SZ", bypass)
            setValue("ProxyEnable", "REG_DWORD", "1")
            // A stale PAC url would take priority over the manual server we just set.
            if (previous.autoConfigUrl.isNotBlank()) deleteValue("AutoConfigURL")
            notifyWinInet()
            PlatformLog.log(
                LogLevel.INFO, TAG,
                "system proxy -> $server (was ${previous.describe()}), bypass=${bypass.length} chars"
            )
            previous
        }.onFailure {
            PlatformLog.log(LogLevel.ERROR, TAG, "failed to set system proxy: ${it.message}", it)
        }
    }

    /** Put back whatever was there before [apply]. Safe to call when nothing was changed. */
    fun restore(): Boolean {
        if (!isWindows) return false
        val saved = readBackup() ?: return false
        val ok = runCatching {
            if (saved.enabled) {
                setValue("ProxyServer", "REG_SZ", saved.server)
                setValue("ProxyEnable", "REG_DWORD", "1")
            } else {
                setValue("ProxyEnable", "REG_DWORD", "0")
                // Leave ProxyServer as-is: other tools keep their address there while disabled.
            }
            setValue("ProxyOverride", "REG_SZ", saved.override)
            if (saved.autoConfigUrl.isNotBlank()) {
                setValue("AutoConfigURL", "REG_SZ", saved.autoConfigUrl)
            }
            notifyWinInet()
            PlatformLog.log(LogLevel.INFO, TAG, "system proxy restored to ${saved.describe()}")
            true
        }.getOrElse {
            PlatformLog.log(LogLevel.ERROR, TAG, "failed to restore system proxy: ${it.message}", it)
            false
        }
        if (ok) runCatching { backupFile.delete() }
        return ok
    }

    /** True when a previous run left the system proxy pointing at us (crash / force-quit). */
    fun hasPendingRestore(): Boolean = isWindows && backupFile.exists()

    private fun readBackup(): ProxySnapshot? =
        runCatching { backupFile.takeIf { it.exists() }?.readText() }
            .getOrNull()
            ?.let { ProxySnapshot.fromJson(it) }

    /**
     * Keep the user's bypass list and add ours. Losing entries here is user-visible (Steam and
     * local addresses start going through the tunnel), so this only ever adds.
     */
    private fun mergeBypass(existing: String, extra: List<String>): String {
        val seen = LinkedHashSet<String>()
        existing.split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { seen += it }
        extra.forEach { if (it.isNotBlank()) seen += it.trim() }
        if (seen.none { it.equals("<local>", ignoreCase = true) }) seen += "<local>"
        return seen.joinToString(";")
    }

    private fun setValue(name: String, type: String, data: String) {
        // /f overwrites without prompting; empty data still needs the /d flag present.
        val cmd = listOf("reg", "add", KEY, "/v", name, "/t", type, "/d", data, "/f")
        require(runCommand(cmd) != null) { "reg add $name failed" }
    }

    private fun deleteValue(name: String) {
        runCommand(listOf("reg", "delete", KEY, "/v", name, "/f"))
    }

    /**
     * Tell WinINET the settings changed. Without this, already-running processes keep using the old
     * proxy until they happen to re-read it, which makes connect/disconnect look broken.
     * 39 = INTERNET_OPTION_SETTINGS_CHANGED, 37 = INTERNET_OPTION_REFRESH.
     */
    private fun notifyWinInet() {
        val script = """
            ${'$'}sig = '[DllImport("wininet.dll", SetLastError=true)] public static extern bool InternetSetOption(IntPtr h, int o, IntPtr b, int l);'
            ${'$'}t = Add-Type -MemberDefinition ${'$'}sig -Name WinINetProxy -Namespace Smugly -PassThru
            ${'$'}t::InternetSetOption([IntPtr]::Zero, 39, [IntPtr]::Zero, 0) | Out-Null
            ${'$'}t::InternetSetOption([IntPtr]::Zero, 37, [IntPtr]::Zero, 0) | Out-Null
        """.trimIndent()
        runCommand(
            listOf("powershell", "-NoProfile", "-NonInteractive", "-Command", script),
            timeoutMs = 15_000
        ) ?: PlatformLog.log(LogLevel.WARN, TAG, "WinINET refresh failed; apps may lag behind")
    }

    private fun runCommand(cmd: List<String>, timeoutMs: Long = 8_000): String? = runCatching {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            return null
        }
        if (p.exitValue() != 0) null else text
    }.getOrNull()
}
