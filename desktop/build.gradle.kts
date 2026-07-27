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

compose.desktop {
    application {
        mainClass = "app.vaydns.ui.DesktopMainKt"
        // GPU Skiko + kill Windows white erase on resize (AWT default bg is white).
        jvmArgs += listOf(
            "-Dskiko.renderApi=DIRECT3D",
            "-Dskiko.vsync.enabled=true",
            "-Dsun.java2d.d3d=true",
            "-Dsun.awt.noerasebackground=true",
            "-Dsun.awt.erasebackgroundonresize=false",
        )
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb
            )
            packageName = "Vaydns"
            packageVersion = "1.0.0"
        }
    }
}
