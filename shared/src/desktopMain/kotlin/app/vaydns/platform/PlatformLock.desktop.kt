package app.vaydns.platform

actual class PlatformLock {
    actual fun <T> withLock(block: () -> T): T = synchronized(this, block)
}
