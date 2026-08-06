package app.smugly.ui

import app.smugly.platform.AppPaths
import app.smugly.platform.LogLevel
import app.smugly.platform.PlatformLog
import java.awt.Color
import java.awt.Component
import java.awt.Window
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-time Win32 dark class brush + SkiaLayer background for Windows resize edges.
 *
 * Live resize Present is left entirely to Skiko: [SkiaLayer.reshape] already calls
 * `renderImmediately()` on DIRECT3D. Extra setBounds / changeSize / GDI fill from our side
 * stacked Presents and made the whole window blink while tracking size well.
 */
object WindowsResizeArtifacts {
    private const val TAG = "WindowsResizeArtifacts"

    private const val COLORREF_SMUGLY_BG = 0x00111111
    private val darkAwt = Color(0x11, 0x11, 0x11)
    private val fixedHwnds = ConcurrentHashMap.newKeySet<Long>()
    private val gripResizing = AtomicBoolean(false)

    fun beginGripResize() {
        gripResizing.set(true)
    }

    fun endGripResize() {
        gripResizing.set(false)
    }

    fun isGripResizing(): Boolean = gripResizing.get()

    fun installSkikoClearColor() {
        if (System.getProperty("skiko.clearColor").isNullOrBlank()) {
            System.setProperty("skiko.clearColor", "0xFF111111")
        }
        if (System.getProperty("SKIKO_CLEAR_COLOR").isNullOrBlank()) {
            System.setProperty("SKIKO_CLEAR_COLOR", "0xFF111111")
        }
        // Do not block Present on vsync during reshape — stacks with live setBounds.
        if (System.getProperty("skiko.windows.waitForVsyncOnRedrawImmediately").isNullOrBlank()) {
            System.setProperty("skiko.windows.waitForVsyncOnRedrawImmediately", "false")
        }
    }

    /** Dark class brush on every new heavyweight HWND (once per HWND). */
    fun applyTo(window: Window) {
        if (!isWindows()) return
        if (!window.isDisplayable) runCatching { window.addNotify() }
        val hwnds = linkedSetOf<Long>()
        walk(window) { c -> hwndOf(c)?.let { hwnds += it } }
        val fresh = hwnds.filter { fixedHwnds.add(it) }
        if (fresh.isEmpty()) return
        applyNativeBrush(fresh)
        // skiko clears with layer.background — set once when HWNDs appear.
        walk(window) { c ->
            if (isSkiaLayer(c) && c.background != darkAwt) {
                runCatching { c.background = darkAwt }
                if (c is javax.swing.JComponent) runCatching { c.isOpaque = true }
            }
        }
        PlatformLog.log(
            LogLevel.INFO, TAG,
            "dark class brush on ${fresh.size} new hwnd(s)"
        )
    }

    /**
     * After open / maximise settle: ensure layer background is dark. Does **not** call
     * changeSize or renderImmediately — Skiko already presents on reshape.
     */
    fun ensureLayerBackground(window: Window) {
        walk(window) { c ->
            if (!isSkiaLayer(c)) return@walk
            if (c.background != darkAwt) runCatching { c.background = darkAwt }
            if (c is javax.swing.JComponent && !c.isOpaque) runCatching { c.isOpaque = true }
        }
        applyTo(window)
    }

    /** @deprecated kept for call sites; no Present. */
    fun forceSkikoPresent(window: Window) = ensureLayerBackground(window)

    fun presentAfterResize(window: Window) = ensureLayerBackground(window)

    fun syncLayerLive(window: Window) {
        // Intentionally empty for live drag. setBounds on the frame is enough; forcing
        // SkiaLayer bounds here double-fires renderImmediately and flickers.
        ensureLayerBackgroundQuiet(window)
    }

    private fun ensureLayerBackgroundQuiet(window: Window) {
        walk(window) { c ->
            if (!isSkiaLayer(c)) return@walk
            if (c.background != darkAwt) runCatching { c.background = darkAwt }
        }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    private fun isSkiaLayer(c: Component): Boolean {
        val n = c.javaClass.name
        return n == "org.jetbrains.skiko.SkiaLayer" || n.endsWith(".SkiaLayer")
    }

    private fun walk(c: Component, visit: (Component) -> Unit) {
        visit(c)
        if (c is java.awt.Container) {
            for (child in c.components) walk(child, visit)
        }
    }

    private fun hwndOf(component: Component): Long? = runCatching {
        if (!component.isDisplayable) return@runCatching null
        val peerField = Component::class.java.getDeclaredField("peer").also { it.isAccessible = true }
        val peer = peerField.get(component) ?: return@runCatching null
        val m = peer.javaClass.methods.firstOrNull { it.name.equals("getHWnd", ignoreCase = true) }
            ?: peer.javaClass.declaredMethods.firstOrNull {
                it.name.contains("HWnd", ignoreCase = true) && it.parameterCount == 0
            }?.also { it.isAccessible = true }
            ?: return@runCatching null
        when (val v = m.invoke(peer)) {
            is Long -> v
            is Int -> v.toLong()
            is Number -> v.toLong()
            else -> null
        }
    }.getOrNull()

    private fun applyNativeBrush(hwnds: List<Long>) {
        val script = File(AppPaths.filesDir(), "win-resize-fix.ps1")
        runCatching {
            script.parentFile?.mkdirs()
            val hwndList = hwnds.joinToString(",") { "${it}L" }
            script.writeText(
                """
                |${'$'}ErrorActionPreference = 'Stop'
                |${'$'}hwnds = @($hwndList) | ForEach-Object { [IntPtr]${'$'}_ }
                |${'$'}code = @'
                |using System;
                |using System.Runtime.InteropServices;
                |public static class SmuglyWinResizeFix {
                |  public const int GCLP_HBRBACKGROUND = -10;
                |  public const int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
                |  public const int DWMWA_BORDER_COLOR = 34;
                |  public const int DWMWA_CAPTION_COLOR = 35;
                |  [DllImport("user32.dll", EntryPoint="SetClassLongPtrW")]
                |  public static extern IntPtr SetClassLongPtr(IntPtr h, int n, IntPtr v);
                |  [DllImport("user32.dll", EntryPoint="SetClassLongW")]
                |  public static extern int SetClassLong32(IntPtr h, int n, int v);
                |  [DllImport("gdi32.dll")]
                |  public static extern IntPtr CreateSolidBrush(int colorRef);
                |  [DllImport("dwmapi.dll")]
                |  public static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);
                |  public static void Apply(IntPtr h, int colorRef) {
                |    IntPtr brush = CreateSolidBrush(colorRef);
                |    if (IntPtr.Size == 8) SetClassLongPtr(h, GCLP_HBRBACKGROUND, brush);
                |    else SetClassLong32(h, GCLP_HBRBACKGROUND, brush.ToInt32());
                |    int on = 1;
                |    DwmSetWindowAttribute(h, DWMWA_USE_IMMERSIVE_DARK_MODE, ref on, 4);
                |    int col = colorRef;
                |    DwmSetWindowAttribute(h, DWMWA_BORDER_COLOR, ref col, 4);
                |    DwmSetWindowAttribute(h, DWMWA_CAPTION_COLOR, ref col, 4);
                |  }
                |}
                |'@
                |if (-not ([System.Management.Automation.PSTypeName]'SmuglyWinResizeFix').Type) {
                |  Add-Type -TypeDefinition ${'$'}code
                |}
                |foreach (${'$'}h in ${'$'}hwnds) { [SmuglyWinResizeFix]::Apply(${'$'}h, $COLORREF_SMUGLY_BG) }
                |exit 0
                """.trimMargin()
            )
            val pb = ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-File", script.absolutePath
            ).redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(8, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                hwnds.forEach { fixedHwnds.remove(it) }
                return
            }
            if (p.exitValue() != 0) {
                PlatformLog.log(LogLevel.WARN, TAG, "native fix exit=${p.exitValue()}: ${out.trim().take(200)}")
                hwnds.forEach { fixedHwnds.remove(it) }
            }
        }.onFailure {
            PlatformLog.log(LogLevel.WARN, TAG, "native fix failed: ${it.message}")
            hwnds.forEach { fixedHwnds.remove(it) }
        }.also {
            runCatching { script.delete() }
        }
    }
}
