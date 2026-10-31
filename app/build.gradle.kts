plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.bilidownload"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.bilidownload"
        // Android 6.0 (API 23) and newer. Activity 1.10.x requires API 23+.
        minSdk = 23
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"
    }

    // No ABI filters/splits: each APK includes all native ABIs supplied by dependencies,
    // so one APK can be installed on supported 32-bit and 64-bit ARM devices.
    // (The current dependency set is expected to be ABI-neutral.)

    buildTypes {
        release {
            // CI-distributed APKs must be signed to install. This uses the standard
            // debug key for test distribution; replace with a private release key
            // before publishing to an app store.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.okhttp)
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation(libs.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
