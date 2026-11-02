plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// A scan-only test bench: runs ML Kit text + barcode recognition and the label parser over a folder of pictures and writes what it
// saw. No map, no routing, no UI. Driven from a PC by tools/label-lab/bench.py. Not shipped.
android {
    namespace = "app.routeplanner.labelbench"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.routeplanner.labelbench"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1"
        ndk { abiFilters += "arm64-v8a" }
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
    implementation(libs.material)          // only to resolve the androidx versions the app already uses (offline cache)
    implementation(libs.mlkit.barcode)
    implementation(libs.mlkit.text)
}
