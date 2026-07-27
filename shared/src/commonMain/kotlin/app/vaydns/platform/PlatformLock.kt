package app.vaydns.platform

/** Multiplatform mutual exclusion (JVM/Android = monitor; iOS = NSLock). */
expect class PlatformLock() {
    fun <T> withLock(block: () -> T): T
}
