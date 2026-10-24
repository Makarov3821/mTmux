plugins {
    id("com.android.library")
    kotlin("android")
}

val whisperDir = rootProject.layout.projectDirectory.dir(".voice-benchmark/whisper.cpp").asFile
val quantization = providers.gradleProperty("voiceWhisperQuantization").orElse("q8_0").get()
require(quantization in setOf("q5_1", "q8_0")) { "voiceWhisperQuantization must be q5_1 or q8_0" }
val modelName = "ggml-base-$quantization.bin"
val modelBytes = if (quantization == "q8_0") 81_768_585L else 59_707_625L
val modelSha256 = if (quantization == "q8_0") "c577b9a86e7e048a0b7eada054f4dd79a56bbfa911fbdacf900ac5b567cbb7d9"
    else "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"
val baseModel = rootProject.layout.projectDirectory.file(".voice-benchmark/models/$modelName").asFile
val punctuationKind = providers.gradleProperty("voicePunctuationModel").orElse("ct").get()
require(punctuationKind in setOf("ct", "srf")) { "voicePunctuationModel must be ct or srf" }
val punctuationName = if (punctuationKind == "ct") "ct-punct-int8.onnx" else "srf-punct-int8.onnx"
val punctuationBytes = if (punctuationKind == "ct") 75_519_198L else 2_243_714L
val punctuationHash = if (punctuationKind == "ct") "65a3fb9f5ad7bfb96bf69e0dc4481df97f6ee60513c1d94ce981ba6effd524b1"
    else "650eb96762e2b6cfe12a3f1b44ddcab0a3f790e11a98afab3895f863d5eaec62"
val generatedAssets = layout.buildDirectory.dir("generated/voiceAssets")

android {
    namespace = "dev.mtmux.voice"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 26
        buildConfigField("String", "WHISPER_MODEL_NAME", "\"$modelName\"")
        buildConfigField("long", "WHISPER_MODEL_BYTES", "${modelBytes}L")
        buildConfigField("String", "WHISPER_MODEL_SHA256", "\"$modelSha256\"")
        buildConfigField("String", "PUNCTUATION_KIND", "\"$punctuationKind\"")
        buildConfigField("String", "PUNCTUATION_MODEL_NAME", "\"$punctuationName\"")
        buildConfigField("long", "PUNCTUATION_MODEL_BYTES", "${punctuationBytes}L")
        buildConfigField("String", "PUNCTUATION_MODEL_SHA256", "\"$punctuationHash\"")
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
    buildFeatures { buildConfig = true }
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
    commandLine("python3", rootProject.file("scripts/fetch_voice_benchmark.py"),
        if (quantization == "q8_0") "verify-base" else "verify-base-q5")
}
val verifyPunctuationModel by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("python3", rootProject.file("scripts/fetch_voice_punctuation.py"), "verify", "--model", punctuationKind)
}
val prepareVoiceModel by tasks.registering(Sync::class) {
    dependsOn(verifyVoiceModel, verifyPunctuationModel)
    from(baseModel)
    from(rootProject.file(".voice-benchmark/punctuation/$punctuationName"))
    if (punctuationKind == "srf") from(rootProject.file(".voice-benchmark/punctuation/srf-vocab.json"))
    into(generatedAssets.map { it.dir("models") })
}
tasks.configureEach {
    if (name.startsWith("configureCMake")) dependsOn(verifyWhisperSource)
    if (name.contains("Assets", ignoreCase = true) || name.contains("Lint", ignoreCase = true)) dependsOn(prepareVoiceModel)
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
    testImplementation("junit:junit:4.13.2")
}
