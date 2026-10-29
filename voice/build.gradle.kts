plugins {
    id("com.android.library")
    kotlin("android")
}

val voiceCache = rootProject.layout.projectDirectory.dir(".voice-models")
val generatedAssets = layout.buildDirectory.dir("generated/xasrAssets")

android {
    namespace = "dev.mtmux.voice"
    compileSdk = 36
    defaultConfig { minSdk = 26; ndk { abiFilters += listOf("arm64-v8a", "x86_64") } }
    sourceSets.getByName("main") {
        java.srcDir(voiceCache.dir("runtime/kotlin"))
        jniLibs.srcDir(voiceCache.dir("runtime/jniLibs"))
        assets.srcDir(generatedAssets)
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(17) }

val verifyVoiceRuntime by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("python3", rootProject.file("scripts/fetch_voice.py"), "verify-runtime")
}
val verifyVoiceModel by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("python3", rootProject.file("scripts/fetch_voice.py"), "verify-model")
}
val prepareVoiceModel by tasks.registering(Sync::class) {
    dependsOn(verifyVoiceModel)
    from(voiceCache.dir("models/x-asr")) {
        include("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt")
    }
    into(generatedAssets.map { it.dir("models/x-asr") })
}
tasks.configureEach {
    if (name == "preBuild" || name.startsWith("compile") && name.endsWith("Kotlin")) dependsOn(verifyVoiceRuntime)
    if (name.contains("Assets", ignoreCase = true) || name.contains("Lint", ignoreCase = true)) dependsOn(prepareVoiceModel)
}
dependencies { testImplementation("junit:junit:4.13.2") }
