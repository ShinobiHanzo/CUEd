plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Pure Kotlin/JVM. No Android dependencies on purpose: everything in here
// (FFT, spectrogram, BPM detection, tempo matching, crossfade curves,
// recommendation heuristics, share payload codec) is unit-testable on any
// machine with a JDK and is reused by the Android app.
// Target 17 bytecode (what Android's D8 consumes) but compile with whatever
// JDK is present (17+) so this module builds anywhere without toolchain downloads.
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
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "failed", "skipped") }
}
