pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven { url = uri(rootDir.resolve(".ci/m2")) }
        mavenLocal()
        google()
        mavenCentral()
        // ytm-kt (YouTube Music radio/recommendations) and its sh.syk transitives
        maven { url = uri("https://maven.syk.sh") }
        // Disabled due to being down
        // maven { url = uri("https://maven.aliucord.com/releases") }
    }
}

rootProject.name = "humane-system-hook"
include(":hook")
include(":injector")
include(":server")
