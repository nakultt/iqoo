plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

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
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // §9 Phase 0 — domain types shared with the backend and the bot.
    implementation(project(":core-models"))

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
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")

    // On-device inference: Qwen3-VL-4B-Instruct (w4a16) on the Snapdragon NPU.
    // 0.4.0 is the floor: earlier builds resolve AI Hub models through a
    // per-model info.json pinned to an older release, which 404s for
    // Qwen3-VL-4B-Instruct. 0.4.0 reads the current global release manifest.
    implementation("com.qualcomm.qti:geniex-android:0.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Persistence (§6.1) — Room replaces the in-memory Repo; the outbox lives here.
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")

    // Real scanning (§6.1): ML Kit decodes QR + Code128 from one frame, on-device.
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // Ed25519 verification. Platform Ed25519 is API 33+, and minSdk here is 31,
    // so Tink carries it on the devices that would otherwise be excluded.
    implementation("com.google.crypto.tink:tink-android:1.13.0")

    // Sync (§6.1): Ktor client + a WorkManager outbox, idempotent by client UUID.
    implementation("io.ktor:ktor-client-core:3.0.3")
    implementation("io.ktor:ktor-client-okhttp:3.0.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Location for PoD capture (§5.4 GPS + timestamp).
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Camera capture feeding the vision tower.
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")

    // On-device QR generation for the sender flow — labels are rendered
    // on the phone itself (ZXing core has no Android dependency).
    implementation("com.google.zxing:core:3.5.3")

    // Real Kokoro neural voice (82M ONNX, CPU): verdicts spoken in true
    // Kokoro voices with no network. The 92 MB weights download on demand
    // into the app's private files dir — never bundled in the APK.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")

    // Instrumented checks for the NPU path — they need a real Hexagon, so they
    // only run on a device with the bundle already pulled.
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
