import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.mozilla.rust-android-gradle.rust-android")
}

val minSdkVersion = 24
val cargoProfile = (findProperty("CARGO_PROFILE") as String?) ?: run {
    if (gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }) "release" else "debug"
}
val opensslBaseDir = file("${System.getenv("HOME")}/android-openssl/android-ssl")

fun abiFromTarget(target: String): String = when {
    target.startsWith("aarch64") -> "arm64-v8a"
    target.startsWith("armv7") || target.startsWith("arm") -> "armeabi-v7a"
    target.startsWith("i686") -> "x86"
    target.startsWith("x86_64") -> "x86_64"
    else -> target
}

android {
    namespace = "app.smugly"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.smugly"
        minSdk = minSdkVersion
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    ndkVersion = "29.0.14206865"

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/cpp/Android.mk")
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir("build/rustJniLibs/android")
            // libs3fu.so is staged here by the cargoBuildS3fu task (kept separate from the
            // rust-android plugin's managed dir so the plugin never clobbers it).
            jniLibs.srcDir("build/s3fuJniLibs")
            // libcdnfu.so staged by cargoBuildCdnfu (same isolation).
            jniLibs.srcDir("build/cdnfuJniLibs")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    buildTypes {
        // A debuggable app is never AOT-compiled by ART and runs with CheckJNI, which
        // roughly triples Compose composition/layout cost. This variant is the same code
        // with debuggable off, signed with the debug key so it installs over the debug
        // build — use it whenever you are judging how the UI actually feels.
        release {
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Multiplatform core + Compose UI (same screens as desktop/iOS).
    implementation(project(":shared"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui:1.7.8")
    implementation("androidx.compose.foundation:foundation:1.7.8")
    implementation("androidx.compose.material3:material3:1.3.1")
    // libxray.aar = Xray-core wrapped by `gomobile bind` (see xray-mobile/build-android.sh).
    // Ships its own jni/arm64-v8a/libgojni.so plus the geoip/geosite assets.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}

val cargoHome = System.getenv("HOME") + "/.cargo"
val cargoBin = "$cargoHome/bin"

cargo {
    cargoCommand = "$cargoBin/cargo"
    rustcCommand = "$cargoBin/rustc"
    module = "../../slipstream-rust"
    libname = "slipstream"
    targets = listOf("arm64")
    profile = cargoProfile
    rustupChannel = "stable"
    extraCargoBuildArguments = listOf(
        "-p", "slipstream-client",
        "--lib",
        "--features", "openssl-static,picoquic-minimal-build",
    )
    exec = { spec, toolchain ->
        val currentPath = System.getenv("PATH") ?: ""
        spec.environment("PATH", "$cargoHome/bin:$currentPath")
        spec.environment("RUST_ANDROID_GRADLE_PYTHON_COMMAND", "python3")
        spec.environment("RUST_ANDROID_GRADLE_CC_LINK_ARG", "-Wl,-z,max-page-size=16384,-soname,lib$libname.so")
        spec.environment("RUST_ANDROID_GRADLE_LINKER_WRAPPER_PY", "$projectDir/src/main/rust/linker-wrapper.py")
        spec.environment("RUST_ANDROID_GRADLE_TARGET", "target/${toolchain.target}/$cargoProfile/lib$libname.so")

        val abi = abiFromTarget(toolchain.target)
        spec.environment("ANDROID_NDK_HOME", android.ndkDirectory.absolutePath)
        spec.environment("ANDROID_ABI", abi)
        spec.environment("ANDROID_PLATFORM", "android-$minSdkVersion")
        spec.environment("PICOQUIC_BUILD_DIR", "$projectDir/../../slipstream-rust/.picoquic-build/$abi")
        spec.environment("PICOQUIC_AUTO_BUILD", "1")
        spec.environment("BUILD_TYPE", if (cargoProfile == "release") "Release" else "Debug")

        val opensslAbiDir = opensslBaseDir.resolve(abi)
        spec.environment("OPENSSL_DIR", opensslAbiDir.absolutePath)
        spec.environment("OPENSSL_LIB_DIR", opensslAbiDir.resolve("lib").absolutePath)
        spec.environment("OPENSSL_INCLUDE_DIR", opensslAbiDir.resolve("include").absolutePath)
        spec.environment("OPENSSL_ROOT_DIR", opensslAbiDir.absolutePath)
        spec.environment("OPENSSL_CRYPTO_LIBRARY", opensslAbiDir.resolve("lib/libcrypto.a").absolutePath)
        spec.environment("OPENSSL_SSL_LIBRARY", opensslAbiDir.resolve("lib/libssl.a").absolutePath)
        spec.environment("OPENSSL_USE_STATIC_LIBS", "1")
        spec.environment("CARGO_ENCODED_RUSTFLAGS", "--remap-path-prefix=${System.getProperty("user.home")}=~")

        android.ndkDirectory.resolve("toolchains/llvm/prebuilt").listFiles()
            ?.firstOrNull { it.isDirectory }
            ?.resolve("bin")
            ?.let { bin ->
                spec.environment("AR", bin.resolve("llvm-ar").absolutePath)
                spec.environment("RANLIB", bin.resolve("llvm-ranlib").absolutePath)
            }
    }
}

// Build libs3fu.so (the s3-fuckup S3 dead-drop tunnel) for arm64 alongside libslipstream.so.
// The rust-android-gradle `cargo {}` block only drives one module, so this second Rust crate
// (a different workspace at ../../../s3-fuckup) is built by a dedicated Exec task that mirrors
// the NDK cross-compile env. See s3-fuckup/build-android.sh.
val cargoBuildS3fu by tasks.registering(Exec::class) {
    val moduleDir = file("$projectDir/../../../s3-fuckup")
    val outDir = file("$projectDir/build/s3fuJniLibs/arm64-v8a")
    workingDir = moduleDir
    commandLine("bash", "${moduleDir.absolutePath}/build-android.sh")
    doFirst {
        outDir.mkdirs()
        environment("ANDROID_NDK_HOME", android.ndkDirectory.absolutePath)
        environment("S3FU_OUT_DIR", outDir.absolutePath)
    }
}

// Build libcdnfu.so (cdn-fuckup XHTTP-over-CDN tunnel) for arm64. Same shape as
// cargoBuildS3fu — a separate Rust workspace at ../../../cdn-fuckup.
val cargoBuildCdnfu by tasks.registering(Exec::class) {
    val moduleDir = file("$projectDir/../../../cdn-fuckup")
    val outDir = file("$projectDir/build/cdnfuJniLibs/arm64-v8a")
    workingDir = moduleDir
    commandLine("bash", "${moduleDir.absolutePath}/build-android.sh")
    doFirst {
        outDir.mkdirs()
        environment("ANDROID_NDK_HOME", android.ndkDirectory.absolutePath)
        environment("CDNFU_OUT_DIR", outDir.absolutePath)
    }
}

// Build libxray.aar (Xray-core via gomobile bind). Unlike the Rust crates this is
// NOT rebuilt on every build -- a gomobile bind of the whole Xray dependency tree
// takes minutes and the wrapper changes rarely. It only runs when the AAR is
// missing (fresh clone); re-run it by hand after touching xray-mobile/:
//   bash xray-mobile/build-android.sh
val xrayAar = file("$projectDir/libs/libxray.aar")
val buildXrayAar by tasks.registering(Exec::class) {
    val moduleDir = file("$projectDir/../xray-mobile")
    workingDir = moduleDir
    commandLine("bash", "${moduleDir.absolutePath}/build-android.sh")
    onlyIf { !xrayAar.exists() }
}

tasks.named("preBuild") {
    dependsOn("cargoBuildArm64")
    dependsOn(cargoBuildS3fu)
    dependsOn(cargoBuildCdnfu)
    dependsOn(buildXrayAar)
}
