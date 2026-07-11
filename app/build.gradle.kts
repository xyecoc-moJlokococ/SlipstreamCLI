import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
    namespace = "app.vaydns"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.vaydns"
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
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
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

tasks.named("preBuild") {
    dependsOn("cargoBuildArm64")
}
