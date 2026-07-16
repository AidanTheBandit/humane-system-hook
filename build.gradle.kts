plugins {
    id("com.android.application") version "8.7.3" apply false
    // ytm-kt 0.6.0 currently resolves Kotlin 2.3 metadata. Keep the compiler
    // aligned or the music hook cannot compile.
    id("org.jetbrains.kotlin.android") version "2.3.0" apply false
}
