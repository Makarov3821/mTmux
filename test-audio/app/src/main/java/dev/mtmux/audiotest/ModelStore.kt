package dev.mtmux.audiotest

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class ModelStore(context: Context, private val catalog: Catalog) {
    val root = File(context.noBackupFilesDir, "models").apply { mkdirs() }
    private val cache = File(context.cacheDir, "downloads").apply { mkdirs() }
    fun ready(scheme: Scheme) = catalog.required(scheme).all { it.file(root).length() == it.bytes }
    suspend fun verify(scheme: Scheme) = verifyWeights(catalog.required(scheme))
    suspend fun verifyWeights(weights: List<Weight>) {
        for (w in weights) {
            currentCoroutineContext().ensureActive()
            require(valid(w)) { "模型缺失或校验失败：${w.path}，请准备模型" }
        }
    }
    private fun valid(w: Weight) = w.file(root).isFile && w.file(root).length() == w.bytes && w.file(root).sha256() == w.sha256
    suspend fun prepare(selected: List<Scheme>, status: (String) -> Unit) {
        val needed = selected.flatMap { catalog.groups(it) }.distinct()
        for ((i, id) in needed.withIndex()) {
            currentCoroutineContext().ensureActive()
            val source = catalog.group(id)
            status("校验 ${i + 1}/${needed.size} · $id")
            if (source.files.all { valid(it) }) continue
            val requiredSpace = source.bytes + source.files.sumOf { it.bytes } + 32L * 1024 * 1024
            require(root.usableSpace > requiredSpace) { "空间不足，准备 $id 至少还需 ${mib(requiredSpace)}" }
            val download = File(cache, "$id.download")
            try {
                download(source, download) { n -> status("下载 ${i + 1}/${needed.size} · $id · ${mib(n)} / ${mib(source.bytes)}") }
                require(download.length() == source.bytes && download.sha256() == source.sha256) { "$id 下载校验失败，请重试" }
                status("解包并校验 · $id")
                if (source.archive) {
                    TarArchiveInputStream(BZip2CompressorInputStream(download.inputStream().buffered())).use { tar ->
                        val found = mutableSetOf<String>()
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val entry = tar.nextEntry ?: break
                            val w = source.files.singleOrNull { it.member == entry.name } ?: continue
                            require(entry.isFile && !entry.isSymbolicLink && entry.size == w.bytes && found.add(w.path)) { "模型包布局异常" }
                            install(tar, w)
                        }
                        require(found.size == source.files.size) { "$id 模型包文件不完整" }
                    }
                } else install(download.inputStream().buffered(), source.files.single(), close = true)
            } finally { download.delete() }
        }
    }
    /** Offline ZIP accepts only manifest-listed paths and verifies every installed byte. */
    suspend fun importPack(input: InputStream, status: (String) -> Unit) {
        ZipInputStream(input.buffered()).use { zip ->
            var count = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zip.nextEntry ?: break
                val w = catalog.weights[entry.name] ?: continue
                require(!entry.isDirectory) { "无效模型文件" }
                status("导入 ${++count} · ${w.path}")
                install(zip, w)
            }
            require(count > 0) { "ZIP 中没有本工具支持的模型" }
        }
    }
    private suspend fun install(input: InputStream, weight: Weight, close: Boolean = false) {
        val dest = weight.file(root); dest.parentFile!!.mkdirs()
        val temp = File(dest.path + ".part")
        try {
            temp.outputStream().buffered().use { out ->
                val b = ByteArray(128 * 1024); var size = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(b); if (n < 0) break
                    size += n; require(size <= weight.bytes) { "模型超过预期大小" }; out.write(b, 0, n)
                }
            }
            require(temp.length() == weight.bytes && temp.sha256() == weight.sha256) { "文件校验失败：${weight.path}" }
            require(temp.renameTo(dest)) { "无法安装模型文件" }
        } finally { temp.delete(); if (close) input.close() }
    }
    private suspend fun download(source: Source, target: File, progress: (Long) -> Unit) {
        val connection = URL(source.url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000; connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "mTmux-AudioLab/0.1")
        try {
            require(connection.responseCode == 200) { "下载 HTTP ${connection.responseCode}：${source.id}" }
            connection.inputStream.buffered().use { input -> target.outputStream().buffered().use { output ->
                val b = ByteArray(128 * 1024); var size = 0L; var last = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(b); if (n < 0) break
                    size += n; require(size <= source.bytes) { "下载超过预期大小" }; output.write(b, 0, n)
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - last > 150) { progress(size); last = now }
                }
            } }
        } finally { connection.disconnect() }
    }
}
