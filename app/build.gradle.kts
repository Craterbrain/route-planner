plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.routeplanner"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.routeplanner"
        minSdk = 26
        targetSdk = 35
        // `-Pabis=arm64-v8a` builds for 64-bit phones only (about 30 MB smaller); the default keeps 32-bit ARM too.
        ndk { abiFilters += (project.findProperty("abis") as String? ?: "arm64-v8a,armeabi-v7a").split(",") }
        versionCode = 17
        versionName = "0.8.1"
    }

    buildTypes {
        release {
            // `./gradlew :app:assembleRelease -Pshrink` turns on R8 + resource shrinking. Off by default: it has not been
            // run on a device, and JNI-backed libraries (MapLibre, Valhalla, ML Kit) are the usual casualties.
            isMinifyEnabled = project.hasProperty("shrink")
            isShrinkResources = project.hasProperty("shrink")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    // Label/barcode scanning: CameraX + ML Kit bundled models (offline, no Play services needed).
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode)
    implementation(libs.mlkit.text)
    implementation(libs.valhalla.mobile)
    implementation(libs.valhalla.models)
    implementation(libs.valhalla.models.config)
    implementation(libs.maplibre)
    // Chromium network stack from Google Play services (Google suggestions only; absent = plain client).
    implementation(libs.play.services.cronet)
    // Already pulled in by material; declared because we use them directly.
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.coordinatorlayout)
}
