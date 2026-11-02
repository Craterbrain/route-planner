import java.util.Properties

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
        versionCode = 29
        versionName = "1.0.1"
    }

    // Debug builds: the machine's long-standing debug identity, kept outside the repo in ~/.config/route-planner/signing/
    // (or RP_SIGNING_DIR), so development installs keep updating. Without it, the default debug key is used.
    val signingDir = File(System.getenv("RP_SIGNING_DIR") ?: "${System.getProperty("user.home")}/.config/route-planner/signing")
    val signingProps = Properties().apply { File(signingDir, "signing.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }

    // Release builds (v1.0 onwards): a separate private key that is NOT the debug key. CI supplies it through
    // RP_RELEASE_KEYSTORE / RP_RELEASE_STORE_PASSWORD / RP_RELEASE_KEY_ALIAS / RP_RELEASE_KEY_PASSWORD; locally it is read
    // from ~/.config/route-planner/release-signing/signing.properties (or RP_RELEASE_SIGNING_DIR).
    val releaseDir = File(System.getenv("RP_RELEASE_SIGNING_DIR") ?: "${System.getProperty("user.home")}/.config/route-planner/release-signing")
    val releaseProps = Properties().apply { File(releaseDir, "signing.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
    fun releaseValue(env: String, prop: String): String? = System.getenv(env) ?: releaseProps.getProperty(prop)
    val releaseStore = System.getenv("RP_RELEASE_KEYSTORE") ?: releaseProps.getProperty("storeFile")?.let { File(releaseDir, it).path }
    val haveReleaseKey = releaseStore != null && File(releaseStore).exists() &&
        releaseValue("RP_RELEASE_STORE_PASSWORD", "storePassword") != null && releaseValue("RP_RELEASE_KEY_ALIAS", "keyAlias") != null

    signingConfigs {
        if (signingProps.containsKey("storeFile")) create("app") {
            storeFile = File(signingDir, signingProps.getProperty("storeFile"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
        if (haveReleaseKey) create("release") {
            storeFile = File(releaseStore!!)
            storePassword = releaseValue("RP_RELEASE_STORE_PASSWORD", "storePassword")
            keyAlias = releaseValue("RP_RELEASE_KEY_ALIAS", "keyAlias")
            keyPassword = releaseValue("RP_RELEASE_KEY_PASSWORD", "keyPassword") ?: storePassword
        }
    }
    buildTypes {
        debug {
            signingConfigs.findByName("app")?.let { signingConfig = it }
        }
        release {
            // `./gradlew :app:assembleRelease -Pshrink` turns on R8 + resource shrinking. Off by default: it has not been
            // run on a device, and JNI-backed libraries (MapLibre, Valhalla, ML Kit) are the usual casualties.
            isMinifyEnabled = project.hasProperty("shrink")
            isShrinkResources = project.hasProperty("shrink")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Never fall back to a debug key for something that gets published: `-PrequireReleaseKey` (CI) turns a missing
            // release key into a build error. Without it a local release build is debug-signed, for testing only.
            signingConfig = signingConfigs.findByName("release")
                ?: if (project.hasProperty("requireReleaseKey")) throw GradleException("No release signing key found (see app/build.gradle.kts)")
                else signingConfigs.findByName("app") ?: signingConfigs.getByName("debug")
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
