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
    private const val CONN_KEY = """HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings\Connections"""
    private const val CONN_DEFAULT = "DefaultConnectionSettings"
    private const val CONN_LEGACY = "SavedLegacySettings"
    private const val BACKUP_FILE = "system-proxy-backup.json"

    val isWindows: Boolean
        get() = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    data class ProxySnapshot(
        val enabled: Boolean,
        val server: String,
        val override: String,
        val autoConfigUrl: String,
        /** Base64 of DefaultConnectionSettings, so restore puts the Settings UI back too. */
        val connectionSettingsB64: String = "",
        val legacySettingsB64: String = "",
        /**
         * Other REG_BINARY values under Connections (per-adapter / localized LAN names).
         * Settings and WinINET often read these instead of DefaultConnectionSettings.
         */
        val extraConnectionBlobsB64: Map<String, String> = emptyMap()
    ) {
        fun toJson(): String {
            val extra = JSONObject()
            extraConnectionBlobsB64.forEach { (k, v) -> extra.put(k, v) }
            return JSONObject()
                .put("enabled", enabled)
                .put("server", server)
                .put("override", override)
                .put("autoConfigUrl", autoConfigUrl)
                .put("connectionSettingsB64", connectionSettingsB64)
                .put("legacySettingsB64", legacySettingsB64)
                .put("extraConnectionBlobsB64", extra)
                .toString(2)
        }

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
                    autoConfigUrl = o.optString("autoConfigUrl", ""),
                    connectionSettingsB64 = o.optString("connectionSettingsB64", ""),
                    legacySettingsB64 = o.optString("legacySettingsB64", ""),
                    extraConnectionBlobsB64 = o.optJSONObject("extraConnectionBlobsB64")
                        ?.let { extra ->
                            extra.keys().asSequence().associateWith { extra.optString(it, "") }
                                .filterValues { it.isNotBlank() }
                        }.orEmpty()
                )
            }.getOrNull()
        }
    }

    private val backupFile: File get() = File(AppPaths.filesDir(), BACKUP_FILE)

    // ---- read ----

    fun snapshot(): ProxySnapshot {
        val blobs = readAllConnectionBlobs()
        return ProxySnapshot(
            enabled = readValue("ProxyEnable")?.let { parseDword(it) != 0 } ?: false,
            server = readValue("ProxyServer").orEmpty(),
            override = readValue("ProxyOverride").orEmpty(),
            autoConfigUrl = readValue("AutoConfigURL").orEmpty(),
            connectionSettingsB64 = blobs[CONN_DEFAULT].orEmpty(),
            legacySettingsB64 = blobs[CONN_LEGACY].orEmpty(),
            extraConnectionBlobsB64 = blobs.filterKeys { it != CONN_DEFAULT && it != CONN_LEGACY }
        )
    }

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
            setValue("AutoDetect", "REG_DWORD", "0")
            deleteValue("AutoConfigURL")
            // WinINET official API (sysproxy.exe). This is what flips the Settings
            // "Manual proxy setup" toggle (FLAGS_UI). Registry-only writes leave that page empty.
            if (!applyPerConnection(enabled = true, server = server, bypass = bypass)) {
                PlatformLog.log(LogLevel.WARN, TAG, "sysproxy helper missing or failed; writing DefaultConnectionSettings only")
            }
            writeDefaultBlobs(enabled = true, server = server, bypass = bypass)
            applyWinHttp(server, bypass)
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
            } else {
                deleteValue("AutoConfigURL")
            }
            restoreConnectionBlob(saved)
            restoreWinHttp()
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
        // `<local>` does not always cover 127.0.0.1 (Chrome still proxies it). The
        // engine and MixedProxy both bind loopback — those must never go back into us.
        // Do NOT add `::1`: WinINET InternetSetOption returns 87 if the bypass contains
        // IPv6, and Settings then shows "Manual proxy setup" as Off.
        listOf("127.0.0.1", "localhost", "<local>").forEach { host ->
            if (seen.none { it.equals(host, ignoreCase = true) }) seen += host
        }
        return sanitizeBypassForWinInet(seen.joinToString(";"))
    }

    /**
     * WinINET rejects IPv6 tokens (`::1`) with error 87. Settings reads the same API,
     * so a rejected call leaves "Manual proxy setup" empty even when ProxyEnable=1.
     */
    internal fun sanitizeBypassForWinInet(raw: String): String =
        raw.split(';').map { it.trim() }.filter { it.isNotEmpty() && !isUnsafeBypassToken(it) }
            .distinct()
            .joinToString(";")

    private fun isUnsafeBypassToken(token: String): Boolean =
        token.contains("::") || token.count { it == ':' } >= 2

    /**
     * WinHTTP is a separate store from WinINET. curl, some updaters and a few browsers
     * read it; leaving it "direct" is why those apps keep using the raw ISP path.
     * Machine-wide and often needs no extra rights for the current user session.
     */
    private fun applyWinHttp(server: String, bypass: String) {
        val bypassArg = bypass.split(';').filter { it.isNotBlank() && it != "<local>" }
            .take(20).joinToString(";")
        val ok = runCommand(
            listOf(
                "netsh", "winhttp", "set", "proxy",
                "proxy-server=$server",
                "bypass-list=${bypassArg.ifBlank { "localhost;127.0.0.1" }}"
            )
        ) != null
        if (!ok) {
            PlatformLog.log(LogLevel.INFO, TAG, "winhttp proxy not set (often needs admin) — WinINET still applied")
        }
    }

    private fun restoreWinHttp() {
        runCommand(listOf("netsh", "winhttp", "reset", "proxy"))
    }

    private fun writeConnectionBlob(enabled: Boolean, server: String, bypass: String) {
        writeDefaultBlobs(enabled, server, bypass)
    }

    /** Only the two well-known keys. Writing every Connections value corrupts named adapters. */
    private fun writeDefaultBlobs(enabled: Boolean, server: String, bypass: String) {
        for (name in listOf(CONN_DEFAULT, CONN_LEGACY)) {
            val existing = readBinary(name)
            val blob = WindowsConnectionSettings.encode(
                enabled = enabled,
                server = server,
                bypass = bypass,
                pacUrl = "",
                counter = WindowsConnectionSettings.nextCounter(existing)
            )
            writeBinary(name, blob)
        }
    }

    private fun restoreConnectionBlob(saved: ProxySnapshot) {
        val def = saved.connectionSettingsB64.takeIf { it.isNotBlank() }
            ?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }
        val leg = saved.legacySettingsB64.takeIf { it.isNotBlank() }
            ?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }
        if (def != null) {
            writeBinary(CONN_DEFAULT, def)
        } else {
            writeDefaultBlobs(saved.enabled, saved.server, saved.override)
        }
        if (leg != null) writeBinary(CONN_LEGACY, leg)
        if (saved.enabled) {
            applyPerConnection(true, saved.server, sanitizeBypassForWinInet(saved.override))
        } else {
            applyPerConnection(false, saved.server, sanitizeBypassForWinInet(saved.override))
        }
    }

    /**
     * WinINET official path — same one v2rayN / Clash use. Writes FLAGS + FLAGS_UI so the
     * Settings "Manual proxy setup" toggle actually flips, and clears AUTOCONFIG_URL so a
     * leftover PAC cannot override the manual server.
     */
    private fun applyPerConnection(enabled: Boolean, server: String, bypass: String): Boolean {
        val exe = findSysProxy() ?: return false
        val cmd = if (enabled) {
            listOf(exe.absolutePath, "set", server, bypass)
        } else {
            listOf(exe.absolutePath, "off")
        }
        val out = runCommand(cmd, timeoutMs = 15_000)
        if (out == null) {
            PlatformLog.log(LogLevel.WARN, TAG, "sysproxy ${cmd.drop(1).joinToString(" ")} failed")
            return false
        }
        PlatformLog.log(LogLevel.INFO, TAG, "sysproxy: ${out.lineSequence().firstOrNull().orEmpty()}")
        return true
    }

    private fun findSysProxy(): File? {
        val names = listOf("sysproxy.exe", "SysProxy.exe")
        val dirs = buildList {
            runCatching {
                WindowsSystemProxy::class.java.protectionDomain.codeSource?.location?.toURI()
                    ?.let { File(it).parentFile }
            }.getOrNull()?.let {
                add(it)
                add(File(it, "engines"))
            }
            add(File(AppPaths.filesDir(), "engines"))
            System.getProperty("user.dir")?.let { cwd ->
                add(File(cwd, "engines"))
                add(File(cwd, "tools/sysproxy"))
            }
        }
        return dirs.distinct().firstNotNullOfOrNull { dir ->
            names.map { File(dir, it) }.firstOrNull { it.isFile }
        }
    }

    private fun connectionValueNames(): List<String> {
        val script = File(AppPaths.filesDir(), "smugly-list-conn.ps1")
        return runCatching {
            script.writeText(
                "\$k = Get-Item -LiteralPath " +
                    "'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\\Connections'; " +
                    "\$k.GetValueNames() | ForEach-Object { \$_ }"
            )
            runCommand(
                listOf(
                    "powershell", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-File", script.absolutePath
                )
            )?.lineSequence()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toList()
                .orEmpty()
        }.getOrElse { emptyList() }.also { runCatching { script.delete() } }
    }

    private fun readAllConnectionBlobs(): Map<String, String> {
        val names = connectionValueNames()
        if (names.isEmpty()) {
            return listOf(CONN_DEFAULT, CONN_LEGACY).mapNotNull { name ->
                readBinary(name)?.let { name to java.util.Base64.getEncoder().encodeToString(it) }
            }.toMap()
        }
        return names.mapNotNull { name ->
            readBinary(name)?.let { name to java.util.Base64.getEncoder().encodeToString(it) }
        }.toMap()
    }

    private fun readBinary(name: String): ByteArray? {
        val dir = AppPaths.filesDir()
        val payload = File(dir, "smugly-read-proxy-bin.json")
        val script = File(dir, "smugly-read-proxy-bin.ps1")
        return runCatching {
            payload.writeText(JSONObject().put("name", name).toString(), Charsets.UTF_8)
            script.writeText(READ_BINARY_SCRIPT)
            val out = runCommand(
                listOf(
                    "powershell", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass",
                    "-File", script.absolutePath,
                    "-PayloadPath", payload.absolutePath
                )
            )?.trim().orEmpty()
            if (out.isBlank()) null else java.util.Base64.getDecoder().decode(out)
        }.getOrNull().also {
            runCatching { script.delete() }
            runCatching { payload.delete() }
        }
    }

    private fun writeBinary(name: String, data: ByteArray) {
        val dir = AppPaths.filesDir()
        val payload = File(dir, "smugly-write-proxy-bin.json")
        val script = File(dir, "smugly-write-proxy-bin.ps1")
        payload.writeText(
            JSONObject()
                .put("name", name)
                .put("b64", java.util.Base64.getEncoder().encodeToString(data))
                .toString(),
            Charsets.UTF_8
        )
        script.writeText(WRITE_BINARY_SCRIPT)
        try {
            val out = runCommand(
                listOf(
                    "powershell", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass",
                    "-File", script.absolutePath,
                    "-PayloadPath", payload.absolutePath
                )
            )
            require(out != null) { "failed to write $name" }
        } finally {
            runCatching { script.delete() }
            runCatching { payload.delete() }
        }
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
     *
     * Options: 39 = SETTINGS_CHANGED, 37 = REFRESH, 95 = PROXY_SETTINGS_CHANGED (Win8+).
     *
     * Must run from a **.ps1 file**, not `powershell -Command "…"`. Passing the DllImport line
     * through -Command strips the quotes around `"wininet.dll"`, Add-Type fails to compile, and we
     * logged "WinINET refresh failed" even though the registry write itself succeeded (measured).
     */
    private fun notifyWinInet() {
        val script = File(AppPaths.filesDir(), "wininet-refresh.ps1")
        val ok = runCatching {
            script.parentFile?.mkdirs()
            // Type name is unique so a sticky PowerShell host (if any) cannot collide.
            // ${'$'} — literal '$' for PowerShell; do not use $$ (Kotlin 2.x multi-dollar rules).
            script.writeText(
                """
                |${'$'}ErrorActionPreference = 'Stop'
                |${'$'}code = @'
                |using System;
                |using System.Runtime.InteropServices;
                |public static class SmuglyWinINetNotify {
                |  [DllImport("wininet.dll", SetLastError=true)]
                |  public static extern bool InternetSetOption(IntPtr h, int opt, IntPtr buf, int len);
                |}
                |'@
                |if (-not ([System.Management.Automation.PSTypeName]'SmuglyWinINetNotify').Type) {
                |  Add-Type -TypeDefinition ${'$'}code
                |}
                |[void][SmuglyWinINetNotify]::InternetSetOption([IntPtr]::Zero, 39, [IntPtr]::Zero, 0)
                |[void][SmuglyWinINetNotify]::InternetSetOption([IntPtr]::Zero, 37, [IntPtr]::Zero, 0)
                |[void][SmuglyWinINetNotify]::InternetSetOption([IntPtr]::Zero, 95, [IntPtr]::Zero, 0)
                |exit 0
                """.trimMargin()
            )
            val out = runCommand(
                listOf(
                    "powershell",
                    "-NoProfile",
                    "-NonInteractive",
                    "-ExecutionPolicy", "Bypass",
                    "-File", script.absolutePath
                ),
                timeoutMs = 15_000
            )
            out != null
        }.getOrElse { e ->
            PlatformLog.log(LogLevel.WARN, TAG, "WinINET refresh threw: ${e.message}")
            false
        }
        runCatching { script.delete() }
        if (!ok) {
            PlatformLog.log(
                LogLevel.WARN, TAG,
                "WinINET refresh failed; registry was updated but some apps may lag until restart"
            )
        }
    }

    private fun runCommand(cmd: List<String>, timeoutMs: Long = 8_000): String? = runCatching {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            PlatformLog.log(LogLevel.WARN, TAG, "command timed out: ${cmd.take(3).joinToString(" ")}…")
            return null
        }
        if (p.exitValue() != 0) {
            val snippet = text.trim().take(400)
            PlatformLog.log(
                LogLevel.WARN, TAG,
                "command exit=${p.exitValue()}: ${cmd.take(4).joinToString(" ")}" +
                    if (snippet.isNotEmpty()) " — $snippet" else ""
            )
            null
        } else {
            text
        }
    }.getOrNull()

    private val READ_BINARY_SCRIPT = """
            param([Parameter(Mandatory=${'$'}true)][string]${'$'}PayloadPath)
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}j = Get-Content -Raw -Encoding UTF8 -LiteralPath ${'$'}PayloadPath | ConvertFrom-Json
            ${'$'}k = Get-Item -LiteralPath 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings\Connections'
            ${'$'}b = ${'$'}k.GetValue([string]${'$'}j.name)
            if (${'$'}null -eq ${'$'}b) { exit 2 }
            [Convert]::ToBase64String([byte[]]${'$'}b)
        """.trimIndent()

        private val WRITE_BINARY_SCRIPT = """
            param([Parameter(Mandatory=${'$'}true)][string]${'$'}PayloadPath)
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}j = Get-Content -Raw -Encoding UTF8 -LiteralPath ${'$'}PayloadPath | ConvertFrom-Json
            ${'$'}bytes = [Convert]::FromBase64String([string]${'$'}j.b64)
            Set-ItemProperty -LiteralPath 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings\Connections' `
                -Name ([string]${'$'}j.name) -Value ${'$'}bytes -Type Binary
        """.trimIndent()

        private val WININET_PER_CONN_SCRIPT = """
            param([Parameter(Mandatory=${'$'}true)][string]${'$'}PayloadPath)
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}j = Get-Content -Raw -Encoding UTF8 -LiteralPath ${'$'}PayloadPath | ConvertFrom-Json
            ${'$'}enable = [bool]${'$'}j.enable
            ${'$'}server = [string]${'$'}j.server
            ${'$'}bypass = [string]${'$'}j.bypass
            ${'$'}conns = @()
            if (${'$'}j.connections) { ${'$'}conns = @(${'$'}j.connections) }
            ${'$'}code = @'
            using System;
            using System.Runtime.InteropServices;
            public static class SmuglyWinINetApply {
              public const int INTERNET_OPTION_PER_CONNECTION_OPTION = 75;
              public const int INTERNET_PER_CONN_FLAGS = 1;
              public const int INTERNET_PER_CONN_PROXY_SERVER = 2;
              public const int INTERNET_PER_CONN_PROXY_BYPASS = 3;
              public const int INTERNET_PER_CONN_AUTOCONFIG_URL = 4;
              public const int INTERNET_PER_CONN_FLAGS_UI = 10;
              public const int PROXY_TYPE_DIRECT = 1;
              public const int PROXY_TYPE_PROXY = 2;
              [StructLayout(LayoutKind.Sequential)]
              public struct OPTION { public int dwOption; public int padding; public IntPtr value; }
              [StructLayout(LayoutKind.Sequential)]
              public struct LIST {
                public int dwSize; public int padding; public IntPtr pszConnection;
                public int dwOptionCount; public int dwOptionError; public IntPtr pOptions;
              }
              [DllImport("wininet.dll", SetLastError = true, CharSet = CharSet.Unicode)]
              public static extern bool InternetSetOption(IntPtr h, int opt, IntPtr buf, int len);
              public static int Apply(string connection, bool enable, string server, string bypass) {
                int flags = PROXY_TYPE_DIRECT | (enable ? PROXY_TYPE_PROXY : 0);
                IntPtr serverPtr = Marshal.StringToHGlobalUni(enable ? (server ?? "") : "");
                IntPtr bypassPtr = Marshal.StringToHGlobalUni(bypass ?? "");
                IntPtr pacPtr = Marshal.StringToHGlobalUni("");
                IntPtr namePtr = string.IsNullOrEmpty(connection) ? IntPtr.Zero : Marshal.StringToHGlobalUni(connection);
                OPTION[] opts = new OPTION[5];
                opts[0].dwOption = INTERNET_PER_CONN_FLAGS; opts[0].value = new IntPtr(flags);
                opts[1].dwOption = INTERNET_PER_CONN_FLAGS_UI; opts[1].value = new IntPtr(flags);
                opts[2].dwOption = INTERNET_PER_CONN_PROXY_SERVER; opts[2].value = serverPtr;
                opts[3].dwOption = INTERNET_PER_CONN_PROXY_BYPASS; opts[3].value = bypassPtr;
                opts[4].dwOption = INTERNET_PER_CONN_AUTOCONFIG_URL; opts[4].value = pacPtr;
                int optSize = Marshal.SizeOf(typeof(OPTION));
                IntPtr optsPtr = Marshal.AllocHGlobal(optSize * opts.Length);
                for (int i = 0; i < opts.Length; i++)
                  Marshal.StructureToPtr(opts[i], IntPtr.Add(optsPtr, i * optSize), false);
                LIST list = new LIST();
                list.dwSize = Marshal.SizeOf(typeof(LIST));
                list.pszConnection = namePtr;
                list.dwOptionCount = opts.Length;
                list.pOptions = optsPtr;
                IntPtr listPtr = Marshal.AllocHGlobal(list.dwSize);
                Marshal.StructureToPtr(list, listPtr, false);
                int err = 0;
                try {
                  if (!InternetSetOption(IntPtr.Zero, INTERNET_OPTION_PER_CONNECTION_OPTION, listPtr, list.dwSize))
                    err = Marshal.GetLastWin32Error();
                } finally {
                  Marshal.FreeHGlobal(listPtr); Marshal.FreeHGlobal(optsPtr);
                  Marshal.FreeHGlobal(serverPtr); Marshal.FreeHGlobal(bypassPtr); Marshal.FreeHGlobal(pacPtr);
                  if (namePtr != IntPtr.Zero) Marshal.FreeHGlobal(namePtr);
                }
                return err;
              }
            }
            '@
            if (-not ([System.Management.Automation.PSTypeName]'SmuglyWinINetApply').Type) {
              Add-Type -TypeDefinition ${'$'}code
            }
            ${'$'}err = [SmuglyWinINetApply]::Apply(${'$'}null, ${'$'}enable, ${'$'}server, ${'$'}bypass)
            Write-Output ("default-err=" + ${'$'}err)
            foreach (${'$'}c in ${'$'}conns) {
              ${'$'}e = [SmuglyWinINetApply]::Apply([string]${'$'}c, ${'$'}enable, ${'$'}server, ${'$'}bypass)
              Write-Output ("named-err=" + ${'$'}e + " name=" + ${'$'}c)
            }
        """.trimIndent()
}
