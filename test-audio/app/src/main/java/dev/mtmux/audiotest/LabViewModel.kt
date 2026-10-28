package dev.mtmux.audiotest

import android.app.Application
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Text is stored only in this dedicated test app's private files and explicit user exports. */
data class RowResult(
    val id: String, val title: String, val raw: String = "", val text: String = "", val status: String,
    val loadMs: Long = 0, val decodeMs: Long = 0, val punctuationMs: Long = 0, val totalMs: Long = 0,
    val modelBytes: Long = 0, val error: String? = null, val language: String = "",
    val asrSourceId: String = id, val asrReused: Boolean = false, val asrTotalMs: Long = 0,
    val observedRowMs: Long = 0
) {
    fun json() = JSONObject().put("id", id).put("title", title).put("raw", raw).put("text", text).put("status", status)
        .put("loadMs", loadMs).put("decodeMs", decodeMs).put("punctuationMs", punctuationMs).put("totalMs", totalMs)
        .put("modelBytes", modelBytes).put("error", error ?: JSONObject.NULL).put("language", language)
        .put("asrSourceId", asrSourceId).put("asrReused", asrReused).put("asrTotalMs", asrTotalMs)
        .put("observedRowMs", observedRowMs).put("totalTimeKind", if (asrReused) "asr_plus_postprocess" else "measured")
}
private data class MeasuredRecognition(val decoded: Decoded, val totalMs: Long)
class LabViewModel(app: Application) : AndroidViewModel(app) {
    val catalog = Catalog.load(app)
    val store = ModelStore(app, catalog)
    val focusSchemes = catalog.schemes.filter { it.focused }
    var selected by mutableStateOf(focusSchemes.map { it.id }.toSet()); private set
    var ready by mutableStateOf(emptySet<String>()); private set
    var busy by mutableStateOf(false); private set
    var phase by mutableStateOf("准备模型后，按住说话，松开比较"); private set
    var recording by mutableStateOf(false); private set
    var preparingMic by mutableStateOf(false); private set
    var rows by mutableStateOf(listOf<RowResult>()); private set
    var audioSeconds by mutableDoubleStateOf(0.0); private set
    var language by mutableStateOf("zh")
    var reference by mutableStateOf("")
    var rotateOrder by mutableStateOf(false)
    var elapsedRecording by mutableIntStateOf(0); private set
    private var audio: FloatArray? = null
    private val recorder = VoiceRecorder()
    private val recorderMutex = Mutex()
    @Volatile private var held = false
    private var clock: Job? = null
    private var work: Job? = null
    private val stop = AtomicBoolean(false)
    private var round = 0
    private var session = JSONObject()
    val apkBytes = File(app.applicationInfo.sourceDir).length()
    var storedModelBytes by mutableLongStateOf(0L); private set
    val canRecord get() = !busy && !recording && !preparingMic && selected.isNotEmpty() && ready.containsAll(selected)
    val canReplay get() = canRecord && audio != null
    val chosen get() = catalog.schemes.filter { it.id in selected }
    val chosenBytes get() = chosen.flatMap { catalog.required(it) }.distinctBy { it.path }.sumOf { it.bytes }

    init { refreshReady() }
    private fun refreshReady() {
        ready = catalog.schemes.filter { store.ready(it) }.map { it.id }.toSet()
        storedModelBytes = store.root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
    fun select(id: String) { if (!busy && !recording && !preparingMic) selected = if (id in selected) selected - id else selected + id }
    fun selectFocus() { if (!busy && !recording && !preparingMic) selected = focusSchemes.map { it.id }.toSet() }
    fun message(text: String) { phase = text }
    fun prepare() = startWork {
        store.prepare(chosen) { postPhase(it) }
        updatePhase("模型已就绪，可以按住说话")
    }
    fun importModels(input: () -> InputStream?) = startWork {
        store.importPack(requireNotNull(input()) { "无法打开文件" }) { postPhase(it) }
        updatePhase("模型导入完成")
    }
    fun importWav(input: () -> InputStream?) = startWork {
        val pcm = Wav.read(requireNotNull(input()) { "无法打开 WAV" })
        withContext(Dispatchers.Main) { audio = pcm; audioSeconds = pcm.size / 16000.0; rows = emptyList(); session = JSONObject() }
        saveWav(pcm)
        updatePhase("已导入 %.2f 秒录音，点击重跑开始比较".format(pcm.size / 16000.0))
    }
    private fun startWork(block: suspend () -> Unit) {
        if (busy || recording || preparingMic) return
        busy = true; stop.set(false)
        work = viewModelScope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch (e: CancellationException) { phase = "已停止" }
            catch (e: Exception) { phase = "${e.javaClass.simpleName}: ${e.message}" }
            finally { busy = false; work = null; refreshReady() }
        }
    }
    private suspend fun updatePhase(value: String) = withContext(Dispatchers.Main) { phase = value }
    // ModelStore callbacks are synchronous and run on IO. Compose state writes are posted to main.
    private fun postPhase(value: String) { viewModelScope.launch(Dispatchers.Main) { phase = value } }

    fun press() {
        if (!canRecord) return
        held = true; preparingMic = true; phase = "准备麦克风…"
        viewModelScope.launch {
            try {
                val started = withContext(Dispatchers.IO) { recorderMutex.withLock {
                    recorder.discard()
                    if (!held) false else { recorder.start { release() }; true }
                } }
                preparingMic = false
                if (!held) {
                    withContext(Dispatchers.IO) { recorderMutex.withLock { recorder.discard() } }
                    phase = "已取消录音"; return@launch
                }
                if (started) {
                    recording = true; phase = "录音中 · 松开开始比较 · 最长 60 秒"; elapsedRecording = 0
                    clock = viewModelScope.launch { while (recording) { delay(1000); elapsedRecording++ } }
                }
            } catch (e: Exception) { preparingMic = false; held = false; recording = false; phase = "麦克风失败：${e.message}" }
        }
    }
    fun release(cancel: Boolean = false) {
        if (!held) return
        held = false; clock?.cancel(); elapsedRecording = 0
        if (preparingMic) return
        if (!recording) return
        recording = false; busy = true
        work = viewModelScope.launch {
            try {
                val pcm = withContext(Dispatchers.IO) { recorderMutex.withLock { if (cancel) { recorder.discard(); null } else recorder.stop() } }
                if (pcm == null) { phase = "已取消录音"; return@launch }
                audio = pcm; audioSeconds = pcm.size / 16000.0
                withContext(Dispatchers.IO) { saveWav(pcm) }
                busy = false; compare()
            } catch (e: Exception) { phase = "录音失败：${e.message}"; busy = false }
            finally { if (cancel) busy = false }
        }
    }
    fun replay() { if (canReplay) compare() }
    fun background() { if (held) release(cancel = true) }
    fun stopWork() {
        stop.set(true)
        // Native ASR finishes its current call; do not free a recognizer while JNI is using it.
        if (rows.any { it.status == "运行中" }) phase = "停止后续模型，等待当前解码结束…"
        else work?.cancel()
    }
    private fun compare() {
        val pcm = audio ?: return
        val list = chosen
        if (list.isEmpty()) return
        stop.set(false); busy = true
        val lang = language; val ref = reference
        val shift = if (rotateOrder) round++ % list.size else 0
        val order = list.drop(shift) + list.take(shift)
        rows = order.map { RowResult(it.id, it.title, status = "等待", modelBytes = catalog.bytes(it)) }
        val startedAt = System.currentTimeMillis()
        val recognitionCache = RecognitionCache<MeasuredRecognition>()
        val verifiedWeights = mutableSetOf<String>()
        work = viewModelScope.launch {
            try {
                for ((index, scheme) in order.withIndex()) {
                    if (stop.get()) break
                    phase = "${index + 1}/${order.size} · ${scheme.title}"
                    setRow(index, rows[index].copy(status = "运行中"))
                    val result = withContext(Dispatchers.IO) {
                        try {
                            // Integrity checks and downloads are outside the measured interval.
                            val unchecked = catalog.required(scheme).filter { it.path !in verifiedWeights }
                            store.verifyWeights(unchecked)
                            verifiedWeights.addAll(unchecked.map { it.path })
                            val started = SystemClock.elapsedRealtime()
                            val recognition = recognitionCache.getOrRun("${scheme.engine}:${scheme.group}:$lang", scheme.id) {
                                val asrStarted = SystemClock.elapsedRealtime()
                                val decoded = Engines(store.root, catalog).decode(scheme, pcm, lang, stop)
                                MeasuredRecognition(decoded, SystemClock.elapsedRealtime() - asrStarted)
                            }
                            val raw = recognition.value.decoded
                            val punct = if (scheme.punctuation != "none" && !stop.get())
                                PunctuationRestorer(store.root, catalog, scheme.punctuation, scheme.punctuationWindow).restore(raw.text) else null
                            val observed = SystemClock.elapsedRealtime() - started
                            val equivalentTotal = observed + if (recognition.reused) recognition.value.totalMs else 0
                            RowResult(scheme.id, scheme.title, raw.text, punct?.text ?: raw.text,
                                status = if (stop.get()) "已停止" else if (punct?.failed == true) "标点失败" else "完成",
                                raw.loadMs, raw.decodeMs, punct?.elapsedMs ?: 0, equivalentTotal,
                                catalog.bytes(scheme), punct?.error, raw.language,
                                recognition.origin, recognition.reused, recognition.value.totalMs, observed)
                        } catch (e: Exception) {
                            RowResult(scheme.id, scheme.title, status = if (stop.get()) "已停止" else "失败", modelBytes = catalog.bytes(scheme), error = "${e.javaClass.simpleName}: ${e.message}")
                        } catch (e: LinkageError) {
                            RowResult(scheme.id, scheme.title, status = "失败", modelBytes = catalog.bytes(scheme), error = "${e.javaClass.simpleName}: ${e.message}")
                        }
                    }
                    setRow(index, result)
                    persist(pcm, lang, ref, startedAt, order)
                }
                rows = rows.map { if (it.status == "等待") it.copy(status = "未运行") else it }
                persist(pcm, lang, ref, startedAt, order)
                phase = if (stop.get()) "比较已停止" else "比较完成 · 可重跑同一录音或导出"
            } catch (e: Exception) {
                rows = rows.map { if (it.status == "运行中" || it.status == "等待") it.copy(status = "未完成") else it }
                phase = "比较中断：${e.javaClass.simpleName}: ${e.message}"
            } finally { busy = false; work = null; refreshReady() }
        }
    }
    private fun setRow(index: Int, row: RowResult) { rows = rows.toMutableList().also { it[index] = row } }
    private suspend fun persist(pcm: FloatArray, lang: String, ref: String, at: Long, order: List<Scheme>) {
        session = JSONObject().put("formatVersion", 2).put("appVersion", BuildConfig.VERSION_NAME)
            .put("runStartedAtMs", at).put("sampleRate", 16000).put("samples", pcm.size).put("durationSeconds", pcm.size / 16000.0)
            .put("language", lang).put("reference", ref).put("asrThreads", 4).put("punctuationThreads", 1)
            .put("apkBytes", apkBytes).put("storedModelBytes", storedModelBytes)
            .put("audioSha256", withContext(Dispatchers.IO) { File(getApplication<Application>().filesDir, "last-audio.wav").sha256() })
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("sdk", Build.VERSION.SDK_INT)
            .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList())).put("modelOrder", JSONArray(order.map { it.id }))
            .put("timingPolicy", "One ASR run per engine/model/language per recording; punctuation variants share identical raw text. Reused rows totalMs = original ASR total + measured postprocess row time, not a separately measured end-to-end decode. File checks excluded; OS cache retained; online tail silence 1s.")
            .put("schemeSettings", JSONArray(order.map { JSONObject().put("id", it.id).put("punctuation", it.punctuation).put("punctuationWindow", it.punctuationWindow) }))
            .put("rows", JSONArray(rows.map { it.json() }))
        val json = session.toString(2)
        withContext(Dispatchers.IO) { File(getApplication<Application>().filesDir, "last-results.json").writeText(json) }
    }
    private fun saveWav(pcm: FloatArray) { File(getApplication<Application>().filesDir, "last-audio.wav").outputStream().use { Wav.write(it, pcm) } }
    fun export(output: () -> OutputStream?) {
        val json = session.toString(2); val pcm = audio?.copyOf(); val manifest = getApplication<Application>().assets.open("models.json").bufferedReader().use { it.readText() }
        startWork {
            ZipOutputStream(requireNotNull(output()) { "无法创建导出文件" }).use { zip ->
                zip.putNextEntry(ZipEntry("results.json")); zip.write(json.toByteArray()); zip.closeEntry()
                zip.putNextEntry(ZipEntry("models.json")); zip.write(manifest.toByteArray()); zip.closeEntry()
                if (pcm != null) { zip.putNextEntry(ZipEntry("audio.wav")); Wav.write(zip, pcm); zip.closeEntry() }
            }
            updatePhase("已导出录音、结果和模型清单")
        }
    }
    override fun onCleared() { held = false; stop.set(true); recorder.close(); super.onCleared() }
}
