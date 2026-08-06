plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

/**
 * Stage a classpath that can run on **Windows** even when the build itself runs under WSL.
 *
 * `:shared` needs an Android SDK, so Gradle usually runs in WSL where `compose.desktop.currentOs`
 * resolves **Linux** Skiko. We still pull the full runtime classpath, then add the Windows Skiko
 * artifact — and **drop every non-Windows Skiko native** from the staged folder. Shipping
 * `skiko-awt-runtime-linux-x64` on a Windows install is pure dead weight (~12 MB on disk and on
 * the classpath scan); Skiko only loads the host OS jar anyway.
 */
val windowsRuntime: Configuration by configurations.creating {
    extendsFrom(configurations.runtimeClasspath.get())
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    windowsRuntime(compose.desktop.windows_x64)
}

// Without the same attributes as runtimeClasspath, KMP modules like skiko expose several variants
// (androidRuntimeElements / awtRuntimeElements) and resolution is ambiguous. Copying them keeps
// this working across Compose/Kotlin upgrades instead of pinning attribute names by hand.
afterEvaluate {
    val runtime = configurations.runtimeClasspath.get()
    runtime.attributes.keySet().forEach { key ->
        @Suppress("UNCHECKED_CAST")
        val typed = key as org.gradle.api.attributes.Attribute<Any>
        runtime.attributes.getAttribute(typed)?.let { windowsRuntime.attributes.attribute(typed, it) }
    }
}

tasks.register<Sync>("stageWindowsRuntime") {
    group = "distribution"
    description = "Copies Windows desktop runtime jars (Windows Skiko only — no linux/mac natives)."
    into(layout.buildDirectory.dir("windows-runtime/lib"))
    from(windowsRuntime) {
        // Keep skiko-awt-*.jar (common) and skiko-awt-runtime-windows-*; drop linux/macos/arm.
        exclude { details ->
            val n = details.name.lowercase()
            n.startsWith("skiko-awt-runtime-") && !n.contains("windows")
        }
    }
    from(tasks.named("jar"))
    // AppCDS archive is keyed to jar timestamps. Leaving a stale smugly-dev.jsa after staging
    // makes the next run dump cds warnings and run without the archive (cold tab switches again).
    doLast {
        val lib = layout.buildDirectory.dir("windows-runtime/lib").get().asFile
        lib.listFiles()
            ?.filter { f ->
                val n = f.name.lowercase()
                n.startsWith("skiko-awt-runtime-") && !n.contains("windows")
            }
            ?.forEach { stray ->
                stray.delete()
                logger.lifecycle("removed non-Windows Skiko native: ${stray.name}")
            }
        val jsa = layout.buildDirectory.file("windows-runtime/smugly-dev.jsa").get().asFile
        if (jsa.exists()) {
            jsa.delete()
            logger.lifecycle("deleted stale AppCDS archive ${jsa.name} (will rebuild on clean exit)")
        }
    }
}

compose.desktop {
    application {
        mainClass = "app.smugly.ui.DesktopMainKt"
        // Match packaged Smugly.exe memory profile (see build-windows-exe.ps1). Without these,
        // a dev run on a 16 GB machine commits ~250 MB G1 heap + 8 refinement threads while
        // live data is ~50 MB — Working Set ~350-400 MB with the VPN idle.
        //
        // skiko.renderApi is left unset so DesktopMain picks DIRECT3D on Windows (smooth FPS).
        // Override with JAVA_TOOL_OPTIONS=-Dskiko.renderApi=SOFTWARE for lower RAM.
        jvmArgs += listOf(
            "-Dskiko.vsync.enabled=true",
            "-Dsun.java2d.d3d=true",
            // No AWT erase on resize — races with Skiko Present and flickers the whole window.
            "-Dsun.awt.noerasebackground=true",
            "-Dsun.awt.erasebackgroundonresize=false",
            "-Xms32m",
            "-Xmx256m",
            "-XX:+UseSerialGC",
            "-XX:MaxMetaspaceSize=192m",
            "-XX:ReservedCodeCacheSize=96m",
        )
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb
            )
            packageName = "Smugly"
            packageVersion = "1.0.0"
        }
    }
}
