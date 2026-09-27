plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "dev.mtmux"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.mtmux"
        minSdk = 26
        targetSdk = 36
        versionCode = 26
        versionName = "0.7.1-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Release signing comes only from environment variables (CI secrets or
    // scripts/verify_release_signing.sh). Without them the release APK is unsigned.
    val releaseKeystore = System.getenv("MTMUX_KEYSTORE")?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = file(releaseKeystore)
            storePassword = System.getenv("MTMUX_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("MTMUX_KEY_ALIAS")
            keyPassword = System.getenv("MTMUX_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            // R8 may break JSch / Bouncy Castle reflection; keep disabled until verified.
            isMinifyEnabled = false
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF") }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
