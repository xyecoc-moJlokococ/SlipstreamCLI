plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.2.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    // 1.11.1 ships skiko that clears SkiaLayer with layer.background (not hard-coded white),
    // which is the real fix for white resize strips on Windows (skiko#1141 / CMP-7919).
    id("org.jetbrains.compose") version "1.11.1" apply false
    id("org.mozilla.rust-android-gradle.rust-android") version "0.9.6" apply false
}
