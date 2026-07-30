package app.smugly

/**
 * Identifies the host platform at runtime. Used by UI/CLI to branch features
 * (e.g. VPN mode is Android/iOS-only; Windows desktop starts as SOCKS proxy).
 */
enum class HostPlatform {
    ANDROID,
    DESKTOP_WINDOWS,
    DESKTOP_LINUX,
    DESKTOP_MACOS,
    DESKTOP_OTHER,
    IOS,
    UNKNOWN
}

expect fun currentHostPlatform(): HostPlatform

fun HostPlatform.isDesktop(): Boolean = when (this) {
    HostPlatform.DESKTOP_WINDOWS,
    HostPlatform.DESKTOP_LINUX,
    HostPlatform.DESKTOP_MACOS,
    HostPlatform.DESKTOP_OTHER -> true
    else -> false
}

/** True when the platform can host a system-wide VPN tunnel (not just local SOCKS). */
fun HostPlatform.supportsSystemVpn(): Boolean = this == HostPlatform.ANDROID || this == HostPlatform.IOS

/** Android status-bar traffic notification — not used on desktop / iOS. */
fun HostPlatform.supportsTrafficNotification(): Boolean = this == HostPlatform.ANDROID

/**
 * Whether username/password on the local proxy listener is worth offering.
 *
 * Not on desktop: the listener is bound to loopback only, and the whole point there is the Windows
 * system proxy setting, which has nowhere to carry credentials — every routed request would come
 * back 407. On Android the local SOCKS port is reachable by other apps on the device, so it stays.
 */
fun HostPlatform.supportsLocalProxyAuth(): Boolean = !isDesktop()
