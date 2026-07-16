plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val includeFrida = providers.gradleProperty("includeFrida")
    .map { it.equals("true", ignoreCase = true) || it == "1" }
    .getOrElse(false)

android {
    namespace = "com.penumbraos.hook"
    compileSdk = 34

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("abxdroppedapk.keystore")
            storePassword = "abxdroppedapk"
            keyAlias = "abxdroppedapk"
            keyPassword = "abxdroppedapk"
        }
    }

    defaultConfig {
        applicationId = "com.penumbraos.hook"
        minSdk = 31
        targetSdk = 32
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = project.findProperty("versionName") as String? ?: "1.0"

        // Only arm64 — the Humane AI Pin is arm64-v8a only
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    sourceSets {
        getByName("main") {
            if (includeFrida) {
                jniLibs.srcDir("frida")
            }
        }
    }

    packaging {
        jniLibs {
            // Native libs MUST be extracted to disk so we can System.load() by absolute path
            // from inside the target process (ironman).
            useLegacyPackaging = true

            if (includeFrida) {
                // Prevent AGP from stripping Frida Gadget files:
                // - libfrida-gadget.so must not be stripped (breaks the binary)
                // - libfrida-gadget.config.so is a JSON config file disguised as .so —
                //   strip would corrupt/fail on it
                keepDebugSymbols += "**/libfrida-gadget.so"
                keepDebugSymbols += "**/libfrida-gadget.config.so"
            }
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
        // NewPipeExtractor uses API 33+ java.* methods (e.g. URLEncoder.encode(String,
        // Charset)); the Pin is API 32, so backport them via core library desugaring.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    implementation("com.aliucord:Aliuhook:1.1.4")
    // PipePipe extractor (NewPipe fork) — extracts YouTube/SoundCloud stream URLs without a
    // po_token (uses ANDROID_VR/iOS/tvHTML5 innertube clients), which vanilla NewPipe can't do
    // on the Pin (no WebView). SHADED fat jar: protobuf-java/okhttp/okio/commons/org.json are
    // relocated under com.penumbraos.shaded.* so they don't collide with ironman's
    // protobuf-javalite (the collision -> VerifyError -> bootloop). org.schabi.newpipe.extractor
    // stays put (MusicHooks uses it); okhttp is consumed only by NewPipeDownloader.java (Java,
    // to dodge Kotlin-metadata issues with the shaded classes).
    implementation(files("libs/pipepipe-shaded.jar"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.4")

    // ytm-kt — YouTube Music API (starts a real "song radio" queue for radio_autoplay,
    // optionally personalized via an account cookie). Uses ktor's bundled CIO engine
    // (no okhttp, so no clash with the shaded jar). EXCLUDE its transitive vanilla
    // NewPipeExtractor: our shaded PipePipe jar already provides org.schabi.newpipe.extractor
    // (un-relocated), and pulling a second copy would duplicate those classes.
    implementation("dev.toastbits:ytm-kt:0.6.0") {
        exclude(group = "com.github.teamnewpipe", module = "NewPipeExtractor")
    }
    // ytm-kt's suspend API is called from YtmRadio via runBlocking; coroutines is only a
    // runtime (implementation) dep of ytm-kt, so declare it for our compile classpath too.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
