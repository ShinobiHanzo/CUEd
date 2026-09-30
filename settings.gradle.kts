pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // NewPipeExtractor (YouTube extraction for the built-in downloader) is published on JitPack only.
        maven("https://jitpack.io") { content { includeGroup("com.github.TeamNewPipe") } }
    }
}

rootProject.name = "CUEd"

include(":core")

// The Android module needs the Android SDK. Skip it when the SDK is not
// configured so `:core` can still be built and tested on a plain JVM box
// (CI, a laptop without Android Studio, a headless container).
val sdkConfigured = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    File(rootDir, "local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
if (sdkConfigured || providers.gradleProperty("cued.forceAndroid").isPresent) {
    include(":app")
} else {
    logger.lifecycle("CUEd: Android SDK not found, skipping :app (set ANDROID_HOME to enable)")
}
