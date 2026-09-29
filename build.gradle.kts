// Top-level build file. Each module declares its own plugins via the version
// catalog (gradle/libs.versions.toml). The Android Gradle Plugin is only
// referenced from :app so that :core can be built on a JVM without the
// Android SDK or access to Google's Maven repository.
tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
