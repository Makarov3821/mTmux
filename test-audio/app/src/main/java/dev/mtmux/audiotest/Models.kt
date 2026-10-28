package dev.mtmux.audiotest

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** The manifest pins deployment files, not training checkpoints or estimates. */
data class Weight(val path: String, val bytes: Long, val sha256: String, val member: String) {
    fun file(root: File) = File(root, path)
}
data class Source(val id: String, val url: String, val bytes: Long, val sha256: String, val archive: Boolean, val files: List<Weight>)
data class Scheme(val id: String, val title: String, val engine: String, val group: String, val punctuation: String,
    val note: String, val focused: Boolean = false, val punctuationWindow: Int = 128)
class Catalog(val sources: List<Source>, val schemes: List<Scheme>) {
    val weights = sources.flatMap { it.files }.associateBy { it.path }
    fun group(id: String) = sources.single { it.id == id }
    fun groups(scheme: Scheme) = listOf(scheme.group) + if (scheme.punctuation == "none") emptyList() else if (scheme.punctuation == "srf") listOf("srf", "srf-vocab") else listOf(scheme.punctuation)
    fun required(scheme: Scheme) = groups(scheme).flatMap { group(it).files }.distinctBy { it.path }
    fun bytes(scheme: Scheme) = required(scheme).sumOf { it.bytes }
    companion object {
        fun load(context: Context): Catalog = parse(context.assets.open("models.json").bufferedReader().use { it.readText() })
        fun parse(json: String): Catalog {
            val root = JSONObject(json)
            val sources = root.getJSONArray("sources").let { a -> (0 until a.length()).map { i ->
                val s = a.getJSONObject(i)
                Source(s.getString("id"), s.getString("url"), s.getLong("bytes"), s.getString("sha256"), s.getBoolean("archive"),
                    s.getJSONArray("files").let { fs -> (0 until fs.length()).map { j -> val f = fs.getJSONObject(j)
                        Weight(f.getString("path"), f.getLong("bytes"), f.getString("sha256"), f.optString("member"))
                    } })
            } }
            val schemes = root.getJSONArray("schemes").let { a -> (0 until a.length()).map { i -> val s = a.getJSONObject(i)
                Scheme(s.getString("id"), s.getString("title"), s.getString("engine"), s.getString("group"), s.getString("punctuation"), s.getString("note"),
                    s.optBoolean("focused", false), s.optInt("punctuationWindow", 128))
            } }
            return Catalog(sources, schemes).also { c ->
                require(schemes.isNotEmpty() && schemes.map { it.id }.distinct().size == schemes.size)
                require(sources.map { it.id }.distinct().size == sources.size)
                require(c.weights.size == sources.sumOf { it.files.size })
                c.weights.values.forEach { require(it.path.matches(Regex("[a-zA-Z0-9_./-]+")) && !it.path.contains("..") && !it.path.startsWith('/')) }
                schemes.forEach {
                    require(it.punctuationWindow in setOf(128, 512))
                    require(it.punctuation != "srf" || it.punctuationWindow == 128)
                    c.required(it)
                }
            }
        }
    }
}
fun File.sha256(): String = inputStream().buffered().use { input ->
    val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(1024 * 1024)
    while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
    digest.digest().joinToString("") { "%02x".format(it) }
}
fun mib(bytes: Long) = "%.1f MiB".format(java.util.Locale.ROOT, bytes / 1048576.0)
