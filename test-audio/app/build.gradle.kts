plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "dev.mtmux.audiotest"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "dev.mtmux.audiotest"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += providers.gradleProperty("audioTestAbis").orElse("arm64-v8a").get().split(",") }
        externalNativeBuild { cmake {
            arguments += listOf("-DWHISPER_CPP_DIR=${rootDir}/.cache/legacy-whisper/whisper.cpp", "-DGGML_NATIVE=OFF", "-DGGML_OPENMP=OFF",
                // Debug is for the software emulator. Avoid its CVTPS2PD page-edge fault.
                "-DCMAKE_CXX_FLAGS_DEBUG=-O3 -g -fno-vectorize -fno-slp-vectorize",
                "-DCMAKE_C_FLAGS_DEBUG=-O3 -g -fno-vectorize -fno-slp-vectorize")
            cppFlags += listOf("-std=c++17", "-O3")
        } }
    }
    buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") } }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets.getByName("main") {
        java.srcDir(rootProject.file("vendor/kotlin"))
        jniLibs.srcDir(rootProject.file("vendor/jniLibs"))
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF" }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
    implementation("org.apache.commons:commons-compress:1.28.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
