plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.cued.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.cued.app"
        minSdk = 26
        targetSdk = 35
        // The release workflow passes these from the git tag / run number.
        versionCode = (System.getenv("CUED_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("CUED_VERSION_NAME") ?: "0.1.0"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // Real key from CI secrets when present; otherwise the public dev key in
        // keystore/ so pre-release builds install and upgrade over each other.
        // See keystore/README.md.
        create("release") {
            val ksPath = System.getenv("CUED_KEYSTORE_PATH") ?: "$rootDir/keystore/cued-dev.jks"
            storeFile = file(ksPath)
            storePassword = System.getenv("CUED_KEYSTORE_PASSWORD") ?: "cued-dev"
            keyAlias = System.getenv("CUED_KEY_ALIAS") ?: "cued-dev"
            keyPassword = System.getenv("CUED_KEY_PASSWORD") ?: "cued-dev"
        }
    }

    buildTypes {
        release {
            // Minification is off until the app has been exercised on real devices;
            // an unshrunk APK is a few MB larger but cannot be broken by R8 stripping
            // something reflection-based. Flip both to true once that's been checked.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    lint {
        // Media3 marks most of its surface @UnstableApi; the app opts in at the use sites that matter.
        disable += "UnsafeOptInUsageError"
        abortOnError = false
        warningsAsErrors = false
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/INDEX.LIST")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.guava)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.zxing.core)
    implementation(libs.nanohttpd)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
}
