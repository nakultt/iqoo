import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * Secrets reach the APK through the gitignored `local.properties`, never
 * through source control — GitHub push protection (rightly) blocks commits
 * carrying API keys. The telegram-bot module follows the same convention with
 * its own `secrets.properties`.
 */
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun buildConfigString(name: String): String =
    (localProperties.getProperty(name) ?: "")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

android {
    namespace = "com.veritransit.inspector"
    compileSdk = 36
    // Matches the NDK the GenieX prebuilt .so files were linked against.
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "com.veritransit.inspector"
        // GenieX (QAIRT/HTP) requires API 31+.
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // OpenRouter cloud fallback; a missing key just disables the cloud leg.
        buildConfigField("String", "OPENROUTER_API_KEY", "\"${buildConfigString("openrouter.api.key")}\"")
        ndk {
            // Snapdragon only — the GenieX AAR ships ~80 MB of arm64 QNN libs and
            // nothing else; packaging other ABIs just bloats the APK.
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/veritransit.keystore")
            storePassword = "veritransit"
            keyAlias = "veritransit"
            keyPassword = "veritransit"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    packaging {
        // QNN backends are dlopen'd by name from the app's native lib dir, so they
        // have to be real files on disk rather than entries inside the APK.
        jniLibs.useLegacyPackaging = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")

    // On-device inference: Qwen3-VL-4B-Instruct (w4a16) on the Snapdragon NPU.
    // 0.4.0 is the floor: earlier builds resolve AI Hub models through a
    // per-model info.json pinned to an older release, which 404s for
    // Qwen3-VL-4B-Instruct. 0.4.0 reads the current global release manifest.
    implementation("com.qualcomm.qti:geniex-android:0.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Camera capture feeding the vision tower.
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")

    // JVM-side unit tests for the pure logic (reconciliation mapping, status
    // derivation, bill-reading confidence parsing) — these run in CI without
    // a device or the model bundle.
    testImplementation(kotlin("test"))

    // Instrumented checks for the NPU path — they need a real Hexagon, so they
    // only run on a device with the bundle already pulled.
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
