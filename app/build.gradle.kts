plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.btcsignal.app"
    compileSdk = 34

    defaultConfig {
        // Deliberately different from the namespace / original applicationId
        // ("com.btcsignal.app") so this build installs as a SEPARATE app
        // alongside the previous version instead of overwriting it. Android
        // treats applicationId as the app's identity on-device; namespace only
        // affects the generated R/BuildConfig package, so it's left unchanged.
        applicationId = "com.btcsignal.app.alpha"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0.0-alpha"

        // Where the app downloads the Kronos ONNX package from (set by the GitHub workflow;
        // can also be edited inside the app on the Kronos tab).
        buildConfigField("String", "KRONOS_MODEL_URL", "\"${project.findProperty("kronosModelUrl") ?: ""}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core / Lifecycle
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    // Compose (BOM)
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore (settings persistence)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Networking: OkHttp (REST + WebSocket). JSON parsing uses org.json (bundled in Android)
    // deliberately, to avoid pulling in an extra serialization plugin/toolchain.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ONNX Runtime (runs the exported Kronos graphs on-device)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")

    // WorkManager (periodic reconciliation / restart safety net)
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Charts (lightweight, pure-Compose line/bar charts — no heavy chart lib dependency)
    // Implemented natively in ui/components/Charts.kt using Canvas, so no extra dependency required.

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.json:json:20240303") // real org.json impl for JVM unit tests (StrategyRegistry parsing)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
