import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

val rustAbi = "arm64-v8a"
val rustTarget = "aarch64-linux-android"
val rustExecutableName = "humane-server"
val packagedRustLibraryName = "libpenumbra_server_android.so"
val rustProjectDir = rootProject.layout.projectDirectory.dir("server-rs")
val rustTargetBinary = rustProjectDir.file("target/$rustTarget/release/$rustExecutableName")
val generatedJniLibsDir = layout.buildDirectory.dir("generated/jniLibs/main")

val androidVersionName = project.findProperty("versionName") as String? ?: "1.0"

val buildRustServerAndroid by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the Rust server for Android arm64."
    workingDir = rustProjectDir.asFile
    commandLine("cargo", "ndk", "-t", rustAbi, "build", "--release")
    environment("PENUMBRA_VERSION", androidVersionName)
    // tokenizers/esaxx-rs and ort-sys can link the Android C++ runtime. This
    // Rust binary is launched as a standalone executable, so link libc++
    // statically instead of requiring libc++_shared.so to be packaged/loaded.
    environment("CXXSTDLIB", "c++_static")
    environment("ORT_CXX_STDLIB", "c++_static")
    environment("RUSTFLAGS", "-C link-arg=-lc++abi")
    // ort-sys' download-binaries feature is enabled transitively by memvid-core,
    // but Android ONNX Runtime binaries are provided by onnxruntime-android and
    // loaded dynamically. Setting ORT_LIB_LOCATION suppresses the unsupported
    // ort-sys Android download path when ort/load-dynamic is also enabled.
    environment("ORT_LIB_LOCATION", rustProjectDir.dir("target/unused-ort-lib-location").asFile.absolutePath)

    inputs.property("penumbraVersion", androidVersionName)
    inputs.property("cxxStdlib", "c++_static")
    inputs.property("ortCxxStdlib", "c++_static")
    inputs.property("rustflags", "-C link-arg=-lc++abi")
    inputs.files(
        fileTree(rustProjectDir.asFile) {
            exclude("target/**")
        }
    )
    outputs.file(rustTargetBinary)
}

val stageRustServerJniLibs by tasks.registering(Sync::class) {
    group = "build"
    description = "Stages the Rust server executable as a JNI lib."
    dependsOn(buildRustServerAndroid)

    into(generatedJniLibsDir)
    from(rustTargetBinary) {
        into(rustAbi)
        rename { packagedRustLibraryName }
    }
}

android {
    sourceSets {
        getByName("main") {
            jniLibs.setSrcDirs(listOf(generatedJniLibsDir))
        }
    }

    namespace = "com.penumbraos.server"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("abxdroppedapk.keystore")
            storePassword = "abxdroppedapk"
            keyAlias = "abxdroppedapk"
            keyPassword = "abxdroppedapk"
        }
    }

    defaultConfig {
        applicationId = "com.penumbraos.server"
        minSdk = 31
        targetSdk = 32
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = androidVersionName

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libpenumbra_server_android.so"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
    }
}

tasks.named("preBuild") {
    dependsOn(stageRustServerJniLibs)
}

dependencies {
    implementation("org.jmdns:jmdns:3.6.3")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.26.0")
}
