package dev.mtmux.voicebenchmark

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class BenchmarkActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        output = TextView(this).apply {
            setPadding(32, 32, 32, 32)
            setTextColor(0xffe8efeb.toInt())
            setBackgroundColor(0xff101815.toInt())
            textSize = 14f
            gravity = Gravity.TOP
            text = "mTmux voice V0 benchmark\nWaiting for arguments…"
        }
        setContentView(output)

        val model = intent.getStringExtra(EXTRA_MODEL)
        val audio = intent.getStringExtra(EXTRA_AUDIO)
        val language = intent.getStringExtra(EXTRA_LANGUAGE) ?: "auto"
        val threads = intent.getIntExtra(EXTRA_THREADS, minOf(4, Runtime.getRuntime().availableProcessors()))
        val runId = intent.getStringExtra(EXTRA_RUN_ID)
        if (model == null || audio == null || runId == null || !RUN_ID.matches(runId)) {
            output.text = "Missing --es model/audio/runId. Use scripts/run_voice_benchmark.py."
            return
        }
        val modelFile = checkedInput(model, "models")
        val audioFile = checkedInput(audio, "audio")
        if (modelFile == null || audioFile == null || language !in ALLOWED_LANGUAGES || threads !in 1..16) {
            output.text = "Invalid path, language, or thread count."
            return
        }

        output.text = "Loading ${modelFile.name}\nAudio: ${audioFile.name}\nLanguage: $language, threads: $threads\n"
        executor.execute {
            val started = System.currentTimeMillis()
            val pssBefore = Debug.getPss()
            val nativeHeapBefore = Debug.getNativeHeapAllocatedSize()
            val result = runCatching {
                val pcm = WavPcm16.readMono16k(audioFile)
                val native = JSONObject(NativeWhisper.run(modelFile.absolutePath, pcm, language, threads).toString(Charsets.UTF_8))
                native.put("runId", runId)
                native.put("modelFile", modelFile.name)
                native.put("audioFile", audioFile.name)
                native.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                native.put("sdk", Build.VERSION.SDK_INT)
                native.put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
                native.put("availableProcessors", Runtime.getRuntime().availableProcessors())
                native.put("processPssBeforeKb", pssBefore)
                native.put("processPssAfterKb", Debug.getPss())
                native.put("nativeHeapBeforeBytes", nativeHeapBefore)
                native.put("nativeHeapAfterBenchmarkBytes", Debug.getNativeHeapAllocatedSize())
                native.put("wallStartedAtMs", started)
                native.put("wallFinishedAtMs", System.currentTimeMillis())
                native
            }
            val json = result.getOrElse { error ->
                JSONObject().put("schema", 1).put("runId", runId).put("error", error.javaClass.simpleName)
                    .put("message", error.message?.take(300) ?: "unknown benchmark error")
                    .put("wallStartedAtMs", started).put("wallFinishedAtMs", System.currentTimeMillis())
            }
            val resultDir = File(filesDir, "results").apply { mkdirs() }
            val target = File(resultDir, "$runId.json")
            val temporary = File(resultDir, "$runId.json.tmp")
            temporary.writeText(json.toString(2), Charsets.UTF_8)
            check(temporary.renameTo(target)) { "cannot publish benchmark result" }
            runOnUiThread { output.text = json.toString(2) }
        }
    }

    private fun checkedInput(path: String, folder: String): File? {
        val root = File(filesDir, folder).canonicalFile
        val file = File(path).canonicalFile
        return file.takeIf { it.isFile && it.path.startsWith(root.path + File.separator) }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_MODEL = "model"
        private const val EXTRA_AUDIO = "audio"
        private const val EXTRA_LANGUAGE = "language"
        private const val EXTRA_THREADS = "threads"
        private const val EXTRA_RUN_ID = "runId"
        private val RUN_ID = Regex("[a-zA-Z0-9_.-]{1,80}")
        private val ALLOWED_LANGUAGES = setOf("auto", "en", "zh", "ja", "es")
    }
}

internal object NativeWhisper {
    init { System.loadLibrary("mtmux_voice_benchmark") }
    external fun run(modelPath: String, pcm: FloatArray, language: String, threads: Int): ByteArray
}
