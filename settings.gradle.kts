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
        // ytm-kt (YouTube Music radio/recommendations) and its sh.syk transitives.
        // Restrict resolution to just these groups so this repo can't shadow other deps.
        maven {
            url = uri("https://maven.syk.sh")
            content {
                includeGroup("dev.toastbits")
                includeGroup("sh.syk")
            }
        }
        // Disabled due to being down
        // maven { url = uri("https://maven.aliucord.com/releases") }
    }
}

rootProject.name = "humane-system-hook"
include(":hook")
include(":injector")
include(":server")
