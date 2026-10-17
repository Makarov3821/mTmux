plugins {
    id("com.android.library")
    kotlin("android")
}

val whisperDir = rootProject.layout.projectDirectory.dir(".voice-benchmark/whisper.cpp").asFile
val baseModel = rootProject.layout.projectDirectory.file(".voice-benchmark/models/ggml-base-q5_1.bin").asFile
val generatedAssets = layout.buildDirectory.dir("generated/voiceAssets")

android {
    namespace = "dev.mtmux.voice"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DWHISPER_CPP_DIR=${whisperDir.absolutePath}",
                    "-DGGML_NATIVE=OFF", "-DGGML_OPENMP=OFF",
                    "-DCMAKE_C_FLAGS_DEBUG=-O3 -g", "-DCMAKE_CXX_FLAGS_DEBUG=-O3 -g",
                    "-DWHISPER_BUILD_EXAMPLES=OFF", "-DWHISPER_BUILD_TESTS=OFF", "-DWHISPER_BUILD_SERVER=OFF"
                )
                cppFlags += listOf("-std=c++17", "-O3")
            }
        }
    }
    buildTypes { release { isMinifyEnabled = false } }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
    sourceSets.getByName("main").assets.srcDir(generatedAssets)
    androidResources { noCompress += "bin" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(17) }

val verifyWhisperSource by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("python3", rootProject.file("scripts/fetch_voice_benchmark.py"), "verify-source")
}
val verifyVoiceModel by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("python3", rootProject.file("scripts/fetch_voice_benchmark.py"), "verify-base")
}
val prepareVoiceModel by tasks.registering(Sync::class) {
    dependsOn(verifyVoiceModel)
    from(baseModel)
    into(generatedAssets.map { it.dir("models") })
}
tasks.configureEach {
    if (name.startsWith("configureCMake")) dependsOn(verifyWhisperSource)
    if (name.contains("Assets", ignoreCase = true) || name.contains("Lint", ignoreCase = true)) dependsOn(prepareVoiceModel)
}

dependencies { testImplementation("junit:junit:4.13.2") }
