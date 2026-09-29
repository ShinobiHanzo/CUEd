// Top-level build file. Each module declares its own plugins via the version
// catalog (gradle/libs.versions.toml). The Android Gradle Plugin is only
// referenced from :app so that :core can be built on a JVM without the
// Android SDK or access to Google's Maven repository.
plugins {
    // Declared once at the root (apply false) so Gradle loads the Kotlin plugin a single time
    // for both modules. AGP is deliberately not listed here (see comment above).
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
