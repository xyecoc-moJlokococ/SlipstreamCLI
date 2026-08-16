package app.smugly.desktop

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encoder for `HKCU\...\Internet Settings\Connections\DefaultConnectionSettings`.
 *
 * The Settings app and WinINET do **not** treat `ProxyEnable` / `ProxyServer` as authoritative.
 * They read this blob. Writing only the simple keys is why "Manual proxy setup" stays Off
 * while `ProxyEnable=1` is already in the registry.
 *
 * Layout (little-endian), same one Clash / v2rayN / the IE control panel write:
 *
 * ```
 * u32 version          (0x46)
 * u32 updateCounter
 * u32 flags            DIRECT=1, PROXY=2, PAC=4, WPAD=8
 * u32 serverLen + bytes
 * u32 bypassLen + bytes
 * u32 pacUrlLen + bytes
 * ```
 */
internal object WindowsConnectionSettings {
    const val VERSION = 0x46
    const val FLAG_DIRECT = 0x01
    const val FLAG_PROXY = 0x02
    const val FLAG_PAC = 0x04
    const val FLAG_WPAD = 0x08

    data class Parsed(
        val version: Int,
        val counter: Int,
        val flags: Int,
        val server: String,
        val bypass: String,
        val pacUrl: String
    ) {
        val proxyEnabled: Boolean get() = flags and FLAG_PROXY != 0
    }

    fun encode(
        enabled: Boolean,
        server: String,
        bypass: String,
        pacUrl: String = "",
        counter: Int = 1,
        extraFlags: Int = 0
    ): ByteArray {
        // Drop PAC/WPAD from extraFlags: Settings treats those as "use setup script"
        // and hides the manual proxy even when ProxyEnable=1.
        val flags = FLAG_DIRECT or
            (if (enabled) FLAG_PROXY else 0) or
            (if (pacUrl.isNotBlank()) FLAG_PAC else 0) or
            (extraFlags and FLAG_PAC.inv() and FLAG_WPAD.inv())
        val serverB = server.toByteArray(Charsets.US_ASCII)
        val bypassB = bypass.toByteArray(Charsets.US_ASCII)
        val pacB = pacUrl.toByteArray(Charsets.US_ASCII)
        val buf = ByteBuffer.allocate(12 + 4 + serverB.size + 4 + bypassB.size + 4 + pacB.size + 32)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(VERSION)
        buf.putInt(counter.coerceAtLeast(1))
        buf.putInt(flags)
        putLenString(buf, serverB)
        putLenString(buf, bypassB)
        putLenString(buf, pacB)
        // Trailing zeros match what Windows itself leaves after the strings.
        while (buf.position() < buf.capacity()) buf.put(0)
        return buf.array()
    }

    fun decode(raw: ByteArray): Parsed? {
        if (raw.size < 16) return null
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val version = buf.int
        val counter = buf.int
        val flags = buf.int
        val server = getLenString(buf) ?: return null
        val bypass = getLenString(buf) ?: return null
        val pac = if (buf.remaining() >= 4) getLenString(buf).orEmpty() else ""
        return Parsed(version, counter, flags, server, bypass, pac)
    }

    fun nextCounter(existing: ByteArray?): Int {
        val parsed = existing?.let { decode(it) } ?: return 1
        return parsed.counter + 1
    }

    private fun putLenString(buf: ByteBuffer, bytes: ByteArray) {
        buf.putInt(bytes.size)
        buf.put(bytes)
    }

    private fun getLenString(buf: ByteBuffer): String? {
        if (buf.remaining() < 4) return null
        val len = buf.int
        if (len < 0 || len > buf.remaining()) return null
        val bytes = ByteArray(len)
        buf.get(bytes)
        return String(bytes, Charsets.US_ASCII)
    }
}
