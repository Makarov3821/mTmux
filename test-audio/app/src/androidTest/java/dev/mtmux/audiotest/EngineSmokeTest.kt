package dev.mtmux.audiotest

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** adb push models ZIP + a short WAV into the target app's external files directory before running. */
@RunWith(AndroidJUnit4::class)
class EngineSmokeTest {
    @Test fun selectedSchemesDecodeIdenticalPcmAndReleaseTheirSessions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val catalog = Catalog.load(context); val store = ModelStore(context, catalog)
        val filter = InstrumentationRegistry.getArguments().getString("engineFilter")
        val schemes = when (filter) {
            "sherpa" -> catalog.schemes.filter { it.engine != "whisper" }
            "focus" -> catalog.schemes.filter { it.focused }
            else -> catalog.schemes
        }
        val external = requireNotNull(context.getExternalFilesDir(null))
        val pack = File(external, "audio-lab-models.zip")
        val preinstalled = InstrumentationRegistry.getArguments().getString("preinstalledModels") == "true"
        if (preinstalled) {
            require(schemes.all { store.ready(it) }) { "Preinstalled weights are incomplete" }
        } else {
            require(pack.isFile) { "Missing ${pack.path}" }
            store.importPack(pack.inputStream()) { }
        }
        val pcm = Wav.read(File(external, "smoke.wav").inputStream())
        val threads = InstrumentationRegistry.getArguments().getString("asrThreads")?.toInt() ?: 4
        val raw = mutableMapOf<String, String>()
        val records = org.json.JSONArray()
        for (s in schemes) {
            if (!preinstalled) store.verify(s)
            android.util.Log.i("AudioLabSmoke", "START ${s.id}")
            val result = Engines(store.root, catalog, threads).decode(s, pcm, "zh", AtomicBoolean(false))
            assertTrue("empty ASR: ${s.id}", result.text.isNotBlank())
            assertTrue(result.loadMs >= 0); assertTrue(result.decodeMs >= 0)
            raw[s.id] = result.text
            val p = if (s.punctuation != "none") PunctuationRestorer(store.root, catalog, s.punctuation, s.punctuationWindow).restore(result.text) else null
            assertFalse("punctuation failed: ${s.id} ${p?.error}", p?.failed ?: false)
            android.util.Log.i("AudioLabSmoke", "DONE ${s.id}")
            records.put(org.json.JSONObject().put("id",s.id).put("raw",result.text).put("text",p?.text ?: result.text)
                .put("loadMs",result.loadMs).put("decodeMs",result.decodeMs).put("punctuationMs",p?.elapsedMs ?: 0))
            File(external,"smoke-results.json").writeText(records.toString(2))
        }
        if ("baseline-ct" in raw) {
            assertEquals(raw["baseline-ct"], raw["baseline-srf"])
            assertEquals(raw["baseline-ct"], raw["whisper-q8"])
        }
        File(external,"smoke-results.json").writeText(records.toString(2))
    }
}
