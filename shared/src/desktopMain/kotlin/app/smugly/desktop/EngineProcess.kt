package app.smugly.desktop

import app.smugly.platform.LogLevel
import app.smugly.platform.PlatformLog
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/**
 * Supervises one native tunnel engine (s3fu.exe, xray.exe, ...) as a child process.
 *
 * The engine owns the actual tunnel; this class owns its lifetime. Two things matter more than
 * anything else here:
 *
 *  - **Nothing outlives the app.** A tunnel process that survives a crashed or force-quit UI keeps
 *    a listening socket bound and keeps talking to the network with no way for the user to stop it,
 *    so the child is registered with a JVM shutdown hook and killed together with its descendants.
 *  - **Failures are visible.** The engine's stdout/stderr is the only place where "bad PSK",
 *    "bucket not found" or "port already in use" ever appears, so every line is forwarded to the
 *    app log and the tail is kept for the Diagnostics screen.
 */
class EngineProcess(
    /** Human-readable engine name used in logs ("s3fu", "xray"). */
    val name: String,
    private val command: List<String>,
    private val workingDir: File? = null,
    private val environment: Map<String, String> = emptyMap()
) {
    private companion object {
        const val TAG = "EngineProcess"
        const val TAIL_LINES = 300
        const val STOP_GRACE_MS = 1500L
    }

    @Volatile private var process: Process? = null
    @Volatile private var shutdownHook: Thread? = null
    private val tail = ArrayDeque<String>()

    /**
     * Where the running engine's PID is recorded.
     *
     * The shutdown hook covers a normal exit, but not `TerminateProcess` — Task Manager "End task",
     * a JVM crash, or a force-kill leave the engine running with the port still bound, which then
     * makes the next connect fail with a confusing "port in use". Verified live: killing the JVM
     * with -Force left xray.exe alive. So the PID is persisted and any survivor is reaped on the
     * next start.
     */
    private val pidFile: File
        get() = File(File(app.smugly.platform.AppPaths.filesDir(), "engines"), "$name.pid")

    /**
     * Kill an engine left behind by a previous run, if it is still alive and really ours.
     *
     * Public because the caller has to do this *before* its "is the port free?" check — a survivor
     * is precisely what holds the port, so checking first would report a conflict we are about to
     * resolve ourselves.
     */
    fun reapStale() {
        val f = pidFile
        val pid = runCatching { f.takeIf { it.exists() }?.readText()?.trim()?.toLong() }.getOrNull()
        if (pid == null) {
            runCatching { f.delete() }
            return
        }
        val handle = ProcessHandle.of(pid).orElse(null)
        if (handle != null && handle.isAlive) {
            // PIDs get reused, so only kill it when the command really is this engine.
            val cmd = handle.info().command().orElse("")
            if (cmd.contains(name, ignoreCase = true)) {
                PlatformLog.log(LogLevel.WARN, TAG, "reaping orphaned $name (pid=$pid) from a previous run")
                runCatching { handle.destroy() }
                if (!runCatching { handle.onExit().get(STOP_GRACE_MS, TimeUnit.MILLISECONDS) }.isSuccess) {
                    runCatching { handle.destroyForcibly() }
                }
            }
        }
        runCatching { f.delete() }
    }

    /** Last [TAIL_LINES] lines the engine printed — surfaced in Diagnostics. */
    fun logTail(): String = synchronized(tail) { tail.joinToString("\n") }

    val isAlive: Boolean get() = process?.isAlive == true

    /** Exit code once the engine has died, or null while it is still running / never started. */
    fun exitCode(): Int? = process?.let { if (it.isAlive) null else it.exitValue() }

    fun start(): Result<Unit> {
        stop()
        reapStale()
        return runCatching {
            PlatformLog.log(LogLevel.INFO, TAG, "$name start: ${command.joinToString(" ")}")
            val pb = ProcessBuilder(command)
                // s3fu logs to stderr, xray to stdout — merge so ordering is preserved.
                .redirectErrorStream(true)
            workingDir?.let { pb.directory(it) }
            if (environment.isNotEmpty()) pb.environment().putAll(environment)
            val p = pb.start()
            process = p
            runCatching {
                pidFile.parentFile?.mkdirs()
                pidFile.writeText(p.pid().toString())
            }

            val hook = Thread({ killTree(p) }, "$name-shutdown").also {
                Runtime.getRuntime().addShutdownHook(it)
            }
            shutdownHook = hook

            Thread({ pump(p) }, "$name-log").apply { isDaemon = true }.start()
            Unit
        }.onFailure {
            PlatformLog.log(LogLevel.ERROR, TAG, "$name failed to start: ${it.message}", it)
            process = null
        }
    }

    fun stop() {
        val p = process ?: return
        process = null
        shutdownHook?.let { hook ->
            // removeShutdownHook throws once shutdown is already in progress; that is fine,
            // the hook is about to run anyway.
            runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
        }
        shutdownHook = null
        if (p.isAlive) {
            PlatformLog.log(LogLevel.INFO, TAG, "$name stopping")
            killTree(p)
        }
        runCatching { pidFile.delete() }
    }

    private fun killTree(p: Process) {
        runCatching {
            // Descendants first: killing the parent orphans them on Windows.
            p.descendants().forEach { d -> runCatching { d.destroy() } }
            p.destroy()
            if (!p.waitFor(STOP_GRACE_MS, TimeUnit.MILLISECONDS)) {
                p.descendants().forEach { d -> runCatching { d.destroyForcibly() } }
                p.destroyForcibly()
                p.waitFor(STOP_GRACE_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun pump(p: Process) {
        runCatching {
            BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    synchronized(tail) {
                        tail.addLast(line)
                        while (tail.size > TAIL_LINES) tail.removeFirst()
                    }
                    PlatformLog.log(LogLevel.INFO, name, line)
                }
            }
        }
        val code = runCatching { p.waitFor() }.getOrNull()
        // An engine that exits on its own is always a failure — it is supposed to run until stopped.
        if (process === p) {
            PlatformLog.log(LogLevel.ERROR, TAG, "$name exited unexpectedly (code=$code)")
        }
    }
}

/**
 * Blocks until something accepts TCP on [host]:[port], or [timeoutMs] elapses.
 *
 * Engines bind their listener a little after process start, so "process is alive" is not the same
 * as "ready". Connecting is the only check that actually proves the tunnel's entry point is usable.
 */
fun waitForPort(host: String, port: Int, timeoutMs: Long): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val ok = runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 300)
                true
            }
        }.getOrDefault(false)
        if (ok) return true
        Thread.sleep(100)
    }
    return false
}

/** True when nothing is listening on [port] yet, i.e. it is safe to bind. */
fun isPortFree(host: String, port: Int): Boolean = runCatching {
    Socket().use { s ->
        s.connect(InetSocketAddress(host, port), 200)
        false // someone answered — port is taken
    }
}.getOrDefault(true)
