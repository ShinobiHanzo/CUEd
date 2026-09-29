// Top-level build file. Each module declares its own plugins via the version
// catalog (gradle/libs.versions.toml). The Android Gradle Plugin is only
// referenced from :app so that :core can be built on a JVM without the
// Android SDK or access to Google's Maven repository.
//
// Gradle warns that the Kotlin plugin is "loaded multiple times" because :core
// and :app each apply it. That is expected here: putting the Kotlin plugin on
// the root classpath would require AGP there too, which defeats the purpose.
tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
