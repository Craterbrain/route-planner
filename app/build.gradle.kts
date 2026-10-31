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
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        versionCode = 5
        versionName = "0.5.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation(libs.valhalla.mobile)
    implementation(libs.valhalla.models)
    implementation(libs.valhalla.models.config)
    implementation(libs.maplibre)
    // Already pulled in by material; declared because we use them directly.
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.coordinatorlayout)
}
