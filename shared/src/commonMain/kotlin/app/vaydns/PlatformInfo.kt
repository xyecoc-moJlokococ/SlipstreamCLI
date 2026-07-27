package app.vaydns

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
