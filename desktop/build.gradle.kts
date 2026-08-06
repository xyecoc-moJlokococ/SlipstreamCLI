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
 * Stage a classpath that can run on **Windows** even when the build itself runs elsewhere.
 *
 * The project has to be built under WSL (the `:shared` KMP module needs an Android SDK, and there
 * is none on the Windows side), but `compose.desktop.currentOs` then resolves the Linux Skiko
 * native. Adding the Windows artifact puts both in the staged directory; Skiko picks the one
 * matching the host at runtime, so the same folder runs on either OS.
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
    description = "Copies every runtime jar (incl. Windows Skiko) into build/windows-runtime/lib."
    into(layout.buildDirectory.dir("windows-runtime/lib"))
    from(windowsRuntime)
    from(tasks.named("jar"))
    // AppCDS archive is keyed to jar timestamps. Leaving a stale smugly-dev.jsa after staging
    // makes the next run dump cds warnings and run without the archive (cold tab switches again).
    doLast {
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
        // skiko.renderApi is left unset so DesktopMain's SOFTWARE default applies (measured
        // ~90 MB less than DIRECT3D). Override with JAVA_TOOL_OPTIONS=-Dskiko.renderApi=DIRECT3D.
        jvmArgs += listOf(
            "-Dskiko.vsync.enabled=true",
            "-Dsun.java2d.d3d=true",
            "-Dsun.awt.noerasebackground=false",
            "-Dsun.awt.erasebackgroundonresize=true",
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
