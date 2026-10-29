package dev.mtmux

import android.app.Application
import android.content.Context
import android.os.Build
import dev.mtmux.core.DiagnosticJournal
import dev.mtmux.core.ConnectProgress
import dev.mtmux.core.ConnectionTrace
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MtmuxApplication : Application() {
    override fun onCreate() { super.onCreate(); DebugLog.start(this) }
}

/** No arbitrary payload API: neither credentials nor terminal/clipboard/SSH text can enter logs. */
object DebugLog {
    enum class Event { START, RESUME, STOP, DESTROY, CONNECT, SSH_STAGE, CONNECTED, DISCONNECT,
        CONNECTION_FAILED, REFRESH, REFRESH_BATCH, REFRESH_DONE, REFRESH_FAILED, WEB_READY, WEB_RESIZE,
        VIEWPORT, FIRST_RENDER, OUTPUT_SUMMARY, WEB_LOAD_FAILED, WEB_SCRIPT_FAILED, INPUT_BLOCKED, INPUT_FAILED, KEY_FILE, KEY_PASTE,
        KEY_IMPORT_FAILED, EXPORT, CLEARED, CRASH, REMOTE_EXIT, VOICE_PERMISSION, VOICE_RECORD_START, VOICE_RECORD_STOP,
        VOICE_TRANSCRIBE, VOICE_RESULT, VOICE_CANCEL, VOICE_ERROR, REFRESH_LIST, CONNECTION_CANCELLED, REFRESH_CANCELLED,
        REFRESH_HANDOFF_REQUEST, REFRESH_HANDOFF, VOICE_PUNCTUATE, VOICE_PUNCTUATE_DONE, VOICE_PUNCTUATE_FAILED,
        SERVER_VERIFY, SERVER_VERIFY_DONE, SERVER_VERIFY_FAILED, SERVER_SAVED_UNVERIFIED,
        TMUX_CREATE, TMUX_CREATE_DONE, TMUX_CREATE_FAILED, DOCTOR, DOCTOR_DONE, DOCTOR_FAILED,
        DOCTOR_CONFIG, DOCTOR_CONFIG_DONE, DOCTOR_CONFIG_FAILED }
    enum class Owner { CONNECT, REFRESH, VERIFY, CREATE, DOCTOR }
    private val worker = ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(128))
    private val dropped = AtomicInteger()
    private val attempts = AtomicInteger()
    fun nextAttempt() = attempts.incrementAndGet()
    private var journal: DiagnosticJournal? = null
    private var header = ""
    @Synchronized fun start(context: Context) {
        if (journal != null) return
        val version = context.packageManager.getPackageInfo(context.packageName,0).versionName
        header = "mtmux diagnostics v2\nbuild=connection-perf-v3\napp=$version androidApi=${Build.VERSION.SDK_INT} webView=${runCatching { android.webkit.WebView.getCurrentWebViewPackage()?.versionName }.getOrNull() ?: "unknown"}\nprocessStartedAt=${java.time.Instant.now()}\nRetention: latest 10 MiB (older events may be overwritten). No terminal text or credentials.\n"
        journal = DiagnosticJournal(File(context.filesDir,"diagnostics"))
        event(Event.START)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Crash record is synchronous; do not wait for the worker during process teardown.
            runCatching { journal?.append(line(Event.CRASH,0,0,error)) }
            previous?.uncaughtException(thread,error)
        }
    }
    /** Stage codes (1 preparing, 2 authenticating) come from structured progress, independent of UI language. */
    fun stage(progress: ConnectProgress, correlation: Int) {
        val code = when (progress.stage) { ConnectProgress.Stage.PREPARING -> 1; ConnectProgress.Stage.AUTHENTICATING -> 2 }
        event(Event.SSH_STAGE,correlation,progress.hop.coerceIn(0,99)*10+code)
    }
    private fun line(event: Event, a: Int, b: Int, error: Throwable?): String {
        val failure = error?.let {
            // Fixed reason classifier, never Throwable.message or toString/printStackTrace.
            " type=${it.javaClass.simpleName.take(80)} reason=${diagnosticReason(it)} frames=" +
                it.stackTrace.take(5).joinToString(";") { frame -> "${frame.className}.${frame.methodName}:${frame.lineNumber}" }
        }.orEmpty()
        return "${java.time.Instant.now()} ${event.name} a=$a b=$b$failure"
    }
    fun event(event: Event, a: Int = 0, b: Int = 0, error: Throwable? = null) {
        enqueue(line(event,a,b,error))
    }
    fun connectionTrace(owner: Owner, correlation: Int, event: ConnectionTrace, attempt: Int = correlation) {
        enqueue("${java.time.Instant.now()} SSH_TRACE source=${owner.name} a=$correlation attempt=$attempt op=${event.operation} " +
            "parent=${event.parent} phase=${event.phase.name} result=${event.result.name} elapsedMs=${event.elapsedMs} hop=${event.hop} " +
            "bytes=${event.bytes} exit=${event.exitStatus?.toString() ?: "NA"} reason=${event.reason?.name ?: "NONE"} monoMs=${System.nanoTime()/1_000_000}")
    }
    private fun enqueue(entry: String) {
        try { worker.execute { runCatching { journal?.append(entry) }.onFailure { dropped.incrementAndGet() } } }
        catch (_: java.util.concurrent.RejectedExecutionException) { dropped.incrementAndGet() }
    }
    /** Call from IO; serial queue provides a snapshot cut at the export request. */
    fun snapshot(): ByteArray = worker.submit<ByteArray> {
        val log = checkNotNull(journal)
        log.append(line(Event.EXPORT,0,0,null))
        (header + "droppedEvents=${dropped.get()}\n\n" + log.snapshot()).toByteArray()
    }.get(10,TimeUnit.SECONDS)
    fun clear() = worker.submit {
        checkNotNull(journal).clear(); dropped.set(0)
        journal?.append(line(Event.CLEARED,0,0,null))
    }.get(10,TimeUnit.SECONDS)
}
