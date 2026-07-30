package app.smugly.desktop

import app.smugly.currentHostPlatform
import app.smugly.isDesktop
import app.smugly.platform.AppPaths
import app.smugly.platform.MemoryKeyValueStore
import app.smugly.supportsSystemVpn
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPlatformTest {
    @Test
    fun platform_is_desktop_not_vpn_host() {
        val p = currentHostPlatform()
        assertTrue(p.isDesktop())
        assertFalse(p.supportsSystemVpn())
    }

    @Test
    fun app_paths_writable() {
        val dir = File(AppPaths.filesDir())
        assertTrue(dir.exists() || dir.mkdirs())
        val probe = File(dir, "probe-write.txt")
        probe.writeText("ok")
        assertTrue(probe.readText() == "ok")
        probe.delete()
    }

    @Test
    fun memory_store_works() {
        val store = MemoryKeyValueStore()
        store.edit().putString("k", "v").putInt("n", 7).apply()
        assertTrue(store.getString("k") == "v")
        assertTrue(store.getInt("n", 0) == 7)
    }
}
