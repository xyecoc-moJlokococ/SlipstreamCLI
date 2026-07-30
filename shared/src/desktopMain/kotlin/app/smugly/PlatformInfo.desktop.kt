package app.smugly

actual fun currentHostPlatform(): HostPlatform {
    val os = System.getProperty("os.name")?.lowercase().orEmpty()
    return when {
        os.contains("win") -> HostPlatform.DESKTOP_WINDOWS
        os.contains("mac") || os.contains("darwin") -> HostPlatform.DESKTOP_MACOS
        os.contains("nux") || os.contains("nix") -> HostPlatform.DESKTOP_LINUX
        else -> HostPlatform.DESKTOP_OTHER
    }
}
