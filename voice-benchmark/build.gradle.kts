plugins {
    id("com.android.application")
    kotlin("android")
}

val whisperDir = rootProject.layout.projectDirectory.dir(".voice-benchmark/whisper.cpp").asFile

android {
    namespace = "dev.mtmux.voicebenchmark"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "dev.mtmux.voicebenchmark"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DWHISPER_CPP_DIR=${whisperDir.absolutePath}",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_OPENMP=OFF",
                    "-DWHISPER_BUILD_EXAMPLES=OFF",
                    "-DWHISPER_BUILD_TESTS=OFF",
                    "-DWHISPER_BUILD_SERVER=OFF"
                )
                cppFlags += listOf("-std=c++17", "-O3")
            }
        }
    }

    buildTypes {
        release {
            // Developer-only benchmark APK: release-optimized native code, installable with debug key.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            isDebuggable = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    testImplementation("junit:junit:4.13.2")
}

val verifyWhisperSource by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("python3", rootProject.file("scripts/fetch_voice_benchmark.py"), "verify-source")
}
tasks.configureEach {
    if (name.startsWith("configureCMake")) dependsOn(verifyWhisperSource)
}
