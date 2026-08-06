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

/**
 * Mitigate white / desktop L-strips when resizing a dark Compose Desktop window on Windows.
 *
 * Root causes
 * -----------
 * Older skiko (Compose ≤1.8) hard-cleared every D3D frame with opaque white (`Canvas.clear(-1)`).
 * Compose **1.11+** / skiko#1141 clears with `SkiaLayer.background` instead — so setting the layer
 * background to SmuglyBg removes the white flash at the source.
 * Independently, the **gap** between HWND client size and the DXGI swapchain during live resize
 * shows either the class brush or (with `WS_EX_NOREDIRECTIONBITMAP`) the desktop — see
 * [Raph Levien](https://raphlinus.github.io/personal/2018/04/08/smooth-resize.html) and
 * [MS Q&A](https://learn.microsoft.com/en-us/answers/questions/5876868/how-to-fix-size-flickering-on-my-d2d-application).
 *
 * What we do
 * ----------
 * 1. Dark solid class brush on every heavyweight HWND (SO 63096226). No NOREDIRECTIONBITMAP.
 * 2. Set `SkiaLayer.background` / `backgroundColor` to #111 so skiko clear matches the app.
 * 3. On resize: `changeSize` + `redrawImmediately` (ResizeBuffers → Present same frame).
 */
object WindowsResizeArtifacts {
    private const val TAG = "WindowsResizeArtifacts"

    /** SmuglyBg #111111 as COLORREF 0x00BBGGRR. */
    private const val COLORREF_SMUGLY_BG = 0x00111111

    private val darkAwt = Color(0x11, 0x11, 0x11)

    /** HWNDs that already received the class-brush fix. */
    private val fixedHwnds = ConcurrentHashMap.newKeySet<Long>()

    /**
     * Call early in [main] before any window is shown.
     * Compose 1.11+ / skiko#1141 prefers [SkiaLayer.background]; system props cover forks that
     * still honour `SKIKO_CLEAR_COLOR`.
     */
    fun installSkikoClearColor() {
        if (System.getProperty("skiko.clearColor").isNullOrBlank()) {
            System.setProperty("skiko.clearColor", "0xFF111111")
        }
        if (System.getProperty("SKIKO_CLEAR_COLOR").isNullOrBlank()) {
            System.setProperty("SKIKO_CLEAR_COLOR", "0xFF111111")
        }
        if (System.getProperty("skiko.clear.color").isNullOrBlank()) {
            System.setProperty("skiko.clear.color", "0xFF111111")
        }
        // skiko#1141 path: default layer background used for canvas clear.
        runCatching {
            val props = Class.forName("org.jetbrains.skiko.SkiaLayerProperties")
            // static defaultClearColor if present on this skiko
            val field = props.declaredFields.firstOrNull {
                it.name.contains("defaultClear", ignoreCase = true) ||
                    it.name.contains("ClearColor", ignoreCase = true)
            }
            if (field != null) {
                field.isAccessible = true
                when {
                    field.type == Int::class.javaPrimitiveType || field.type == Integer::class.java ->
                        field.set(null, 0xFF111111.toInt())
                    field.type.name == "int" -> field.setInt(null, 0xFF111111.toInt())
                }
            }
        }
    }

    /**
     * Apply Win32 dark class brush to every heavyweight HWND under [window] that we have not
     * fixed yet. Safe to call repeatedly (e.g. on every resize / windowOpened) — Skiko's
     * HardwareLayer HWND often appears only after the first frame.
     */
    fun applyTo(window: Window) {
        if (!isWindows()) return
        if (!window.isDisplayable) {
            runCatching { window.addNotify() }
        }
        val hwnds = linkedSetOf<Long>()
        collectHwnds(window, hwnds)
        val fresh = hwnds.filter { fixedHwnds.add(it) }
        if (fresh.isEmpty()) return
        applyNativeBrush(fresh)
        PlatformLog.log(
            LogLevel.INFO, TAG,
            "dark class brush on ${fresh.size} new hwnd(s): " +
                fresh.joinToString { "0x${it.toString(16)}" }
        )
    }

    /**
     * MS-style resize path for Skiko DIRECT3D:
     * size the layer → `ResizeBuffers` (`changeSize`) → full draw + Present (`redrawImmediately`).
     * Call on the EDT after the AWT window bounds changed.
     */
    fun forceSkikoPresent(window: Window) {
        if (window.width <= 0 || window.height <= 0) return
        walk(window) { c ->
            if (!isSkiaLayer(c)) return@walk
            val parent = c.parent
            val tw = parent?.width?.takeIf { it > 0 } ?: window.width
            val th = parent?.height?.takeIf { it > 0 } ?: window.height
            if (c.x != 0 || c.y != 0 || c.width != tw || c.height != th) {
                runCatching { c.setBounds(0, 0, tw, th) }
            }
            // skiko#1141: canvas is cleared with layer.background — must be #111, not white.
            runCatching { c.background = darkAwt }
            if (c is javax.swing.JComponent) runCatching { c.isOpaque = true }
            runCatching {
                val m = c.javaClass.methods.firstOrNull {
                    it.name == "setBackgroundColor" && it.parameterCount == 1
                }
                m?.invoke(c, 0xFF111111.toInt())
            }
            runCatching {
                val f = c.javaClass.declaredFields.firstOrNull {
                    it.name.equals("backgroundColor", ignoreCase = true) ||
                        it.name.equals("clearColor", ignoreCase = true)
                }
                if (f != null) {
                    f.isAccessible = true
                    when {
                        f.type == Int::class.javaPrimitiveType || f.type == Integer::class.java ->
                            f.set(c, 0xFF111111.toInt())
                        f.type == Color::class.java -> f.set(c, darkAwt)
                    }
                }
            }

            // SkiaLayer.setBounds already tries tryRedrawImmediately for DIRECT3D; also force
            // changeSize + redrawImmediately so ResizeBuffers and Present land in one frame
            // even when layout did not go through SkiaLayer.setBounds (e.g. only child Canvas).
            runCatching {
                val redrawer = invokeNoArg(c, "getRedrawer\$skiko")
                    ?: invokeNoArg(c, "getRedrawer")
                    ?: return@runCatching
                val scale = contentScaleOf(c)
                val pw = (c.width * scale).toInt().coerceAtLeast(1)
                val ph = (c.height * scale).toInt().coerceAtLeast(1)
                val changeSize = redrawer.javaClass.methods.firstOrNull {
                    it.name == "changeSize" && it.parameterCount == 2
                }
                changeSize?.isAccessible = true
                changeSize?.invoke(redrawer, pw, ph)
                val redraw = redrawer.javaClass.methods.firstOrNull {
                    it.name == "redrawImmediately" && it.parameterCount == 0
                }
                redraw?.isAccessible = true
                redraw?.invoke(redrawer)
            }

            // paint() → tryRedrawImmediately when not already rendering.
            runCatching {
                val g = c.graphics ?: return@runCatching
                try {
                    c.paint(g)
                } finally {
                    g.dispose()
                }
            }
        }
        // Late-created Skiko HWNDs get the dark brush on the next call.
        applyTo(window)
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    private fun isSkiaLayer(c: Component): Boolean {
        val n = c.javaClass.name
        return n == "org.jetbrains.skiko.SkiaLayer" || n.endsWith(".SkiaLayer")
    }

    private fun contentScaleOf(c: Component): Float = runCatching {
        val m = c.javaClass.methods.firstOrNull {
            it.name == "getContentScale" && it.parameterCount == 0
        } ?: return@runCatching 1f
        when (val v = m.invoke(c)) {
            is Float -> v
            is Double -> v.toFloat()
            is Number -> v.toFloat()
            else -> 1f
        }
    }.getOrDefault(1f).coerceAtLeast(0.5f)

    private fun invokeNoArg(target: Any, name: String): Any? = runCatching {
        val m = target.javaClass.methods.firstOrNull {
            it.name == name && it.parameterCount == 0
        } ?: target.javaClass.declaredMethods.firstOrNull {
            it.name == name && it.parameterCount == 0
        }?.also { it.isAccessible = true }
        m?.invoke(target)
    }.getOrNull()

    private fun collectHwnds(root: Component, out: MutableSet<Long>) {
        walk(root) { c ->
            hwndOf(c)?.let { out += it }
        }
    }

    private fun walk(c: Component, visit: (Component) -> Unit) {
        visit(c)
        if (c is java.awt.Container) {
            for (child in c.components) walk(child, visit)
        }
    }

    /**
     * Reflect into AWT peer → `getHWnd()` (HotSpot Windows). Returns null on failure.
     */
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

    /**
     * Dark solid class brush only (no WS_EX_NOREDIRECTIONBITMAP).
     * File-based PowerShell so DllImport quotes survive.
     */
    private fun applyNativeBrush(hwnds: List<Long>) {
        val script = File(AppPaths.filesDir(), "win-resize-fix.ps1")
        runCatching {
            script.parentFile?.mkdirs()
            val hwndList = hwnds.joinToString(",") { "${it}L" }
            // COLORREF 0x00bbggrr — #111111 → 0x00111111.
            // GCLP_HBRBACKGROUND = -10; CreateSolidBrush (SO 63096226).
            // DWM dark caption/border only — no NOREDIRECTIONBITMAP (Raph / GDI gap fill).
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
                |  public const int DWMWA_COLOR_NONE = unchecked((int)0xFFFFFFFE);
                |  [DllImport("user32.dll", EntryPoint="SetClassLongPtrW")]
                |  public static extern IntPtr SetClassLongPtr(IntPtr h, int n, IntPtr v);
                |  [DllImport("user32.dll", EntryPoint="SetClassLongW")]
                |  public static extern int SetClassLong32(IntPtr h, int n, int v);
                |  [DllImport("user32.dll")]
                |  public static extern bool InvalidateRect(IntPtr h, IntPtr rect, bool erase);
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
                |    InvalidateRect(h, IntPtr.Zero, true);
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
                "powershell",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy", "Bypass",
                "-File", script.absolutePath
            ).redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(8, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                PlatformLog.log(LogLevel.WARN, TAG, "native resize fix timed out")
                // Allow retry.
                hwnds.forEach { fixedHwnds.remove(it) }
                return
            }
            if (p.exitValue() != 0) {
                PlatformLog.log(
                    LogLevel.WARN, TAG,
                    "native resize fix exit=${p.exitValue()}: ${out.trim().take(400)}"
                )
                hwnds.forEach { fixedHwnds.remove(it) }
            }
        }.onFailure {
            PlatformLog.log(LogLevel.WARN, TAG, "native resize fix failed: ${it.message}")
            hwnds.forEach { fixedHwnds.remove(it) }
        }.also {
            runCatching { script.delete() }
        }
    }
}
