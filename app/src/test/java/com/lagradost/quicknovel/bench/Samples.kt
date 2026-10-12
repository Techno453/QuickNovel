package com.lagradost.quicknovel.bench

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.File
import java.util.concurrent.TimeUnit

/** A source chapter excerpt; [text] is plain text with one paragraph per line. */
data class Sample(
    val id: String,
    val lang: String,
    val title: String,
    val origin: String,
    val text: String,
    val fullChars: Int,
) {
    val chars: Int get() = text.length
    val paragraphs: List<String> get() = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * Fetches samples with plain OkHttp + Jsoup (the app's providers need Android classes) and
 * caches the raw page under app/build/translation-bench/cache/. Local files
 * bench/samples/<lang>-<name>.txt are picked up as extra samples.
 */
class SampleFetcher(private val cacheDir: File, private val config: BenchConfig) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    /** [SampleSpec.url] may list several comma-separated pages (consecutive episodes) that are concatenated. */
    fun load(spec: SampleSpec): Sample {
        snapshot(spec)?.let { return it }
        val urls = spec.url.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val paragraphs: List<String> = urls.flatMapIndexed { i, url ->
            val key = if (i == 0) spec.id else "${spec.id}-$i"
            when (spec.source.lowercase()) {
                "syosetu" -> htmlParagraphs(cached(key) { get(url) }, ".p-novel__body p, #novel_honbun p")
                "kakuyomu" -> htmlParagraphs(cached(key) { get(url) }, ".widget-episodeBody p")
                "quanben" -> quanben(cached(key) { get(url) })
                "novelpia" -> novelpia(cached(key) { novelpiaJson(url) })
                "file" -> File(url).readText().lines()
                else -> error("Unknown sample source '${spec.source}'")
            }
        }.map { it.trim() }.filter { it.isNotEmpty() }
        require(paragraphs.isNotEmpty()) { "Sample ${spec.id}: no text extracted from ${spec.url}" }
        val full = paragraphs.joinToString("\n")
        return Sample(spec.id, spec.lang, spec.title, spec.url, trim(paragraphs), full.length).also { saveSnapshot(it) }
    }

    /**
     * cache/sample-<id>.json: the exact excerpt that was translated, so later report/rejudge runs judge
     * the same text even if the site changes. Used while its origin still matches the configured url.
     */
    private fun snapshotFile(id: String) = File(cacheDir, "sample-$id.json")

    private fun snapshot(spec: SampleSpec): Sample? = snapshotFile(spec.id).takeIf { it.isFile }
        ?.let { runCatching { benchMapper.readValue(it, Sample::class.java) }.getOrNull() }
        ?.takeIf { it.origin == spec.url && it.lang == spec.lang && it.text.isNotBlank() }
        ?.copy(title = spec.title)

    private fun saveSnapshot(sample: Sample) {
        cacheDir.mkdirs()
        benchMapper.writeValue(snapshotFile(sample.id), mapOf(
            "id" to sample.id, "lang" to sample.lang, "title" to sample.title, "origin" to sample.origin,
            "text" to sample.text, "fullChars" to sample.fullChars,
        ))
    }

    /** bench/samples/<lang>-<name>.txt */
    fun localSamples(dir: File): List<Sample> {
        val files = dir.listFiles { f -> f.isFile && f.name.matches(Regex("""(zh|ja|ko)-[\w.-]+\.txt""")) } ?: return emptyList()
        return files.sortedBy { it.name }.map { f ->
            val lang = f.name.substringBefore('-')
            val paragraphs = f.readText(Charsets.UTF_8).lines().map { it.trim() }.filter { it.isNotEmpty() }
            Sample(f.nameWithoutExtension, lang, f.nameWithoutExtension, "local file bench/samples/${f.name}",
                trim(paragraphs), paragraphs.joinToString("\n").length)
        }
    }

    /** Keeps whole paragraphs up to roughly sampleTargetChars (never above sampleMaxChars unless one paragraph is longer). */
    internal fun trim(paragraphs: List<String>): String {
        val out = mutableListOf<String>()
        var len = 0
        for (p in paragraphs) {
            val add = p.length + if (out.isEmpty()) 0 else 1
            if (out.isNotEmpty() && len + add > config.sampleMaxChars) break
            if (out.isNotEmpty() && len >= config.sampleTargetChars) break
            out.add(p)
            len += add
        }
        return out.joinToString("\n")
    }

    private fun cached(key: String, fetch: () -> String): String {
        val file = File(cacheDir, "sample-$key.raw")
        if (file.isFile && file.length() > 0) return file.readText(Charsets.UTF_8)
        val body = fetch()
        cacheDir.mkdirs()
        file.writeText(body, Charsets.UTF_8)
        return body
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept-Language", "ja,ko;q=0.9,zh;q=0.8,en;q=0.5")
            .build()
        client.newCall(req).execute().use { r ->
            check(r.isSuccessful) { "GET $url -> HTTP ${r.code}" }
            return r.body?.string().orEmpty()
        }
    }

    private fun novelpiaJson(viewerUrl: String): String {
        val no = Regex("""/viewer/(\d+)""").find(viewerUrl)?.groupValues?.get(1) ?: error("Bad novelpia url $viewerUrl")
        val req = Request.Builder().url("https://novelpia.com/proc/viewer_data/$no")
            .header("User-Agent", UA)
            .header("Referer", "https://novelpia.com/viewer/$no")
            .header("X-Requested-With", "XMLHttpRequest")
            .post(FormBody.Builder().add("size", "14").build())
            .build()
        client.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            check(r.isSuccessful && body.isNotBlank()) { "Novelpia episode $no: HTTP ${r.code}, ${body.length} bytes (locked?)" }
            return body
        }
    }

    private fun htmlParagraphs(html: String, selector: String): List<String> =
        Jsoup.parse(html).select(selector).map { p ->
            p.select("rp, rt").remove() // drop furigana readings, keep the base text
            p.text()
        }

    private fun quanben(html: String): List<String> {
        val content: Element = Jsoup.parse(html).selectFirst("#contentbody, #content, .content") ?: return emptyList()
        content.select("script, style, ins, iframe, [class*=ads], [id*=ads], div[align=center]").remove()
        val ps = content.select("p")
        return if (ps.isNotEmpty()) ps.map { it.text() } else content.wholeText().lines()
    }

    /** Mirrors NovelpiaParser.parseViewerData: drops the cover block and the invisible watermark lines. */
    private fun novelpia(json: String): List<String> {
        val lines = benchMapper.readTree(json).path("s")
        return lines.mapNotNull { line ->
            val fragment = Jsoup.parseBodyFragment(line.path("text").asText(""))
            fragment.select(".cover-wrapper, script, style, img").remove()
            fragment.select("[style]").filter {
                val style = it.attr("style").replace(" ", "").lowercase()
                style.contains("height:0px") || style.contains("opacity:0") || style.contains("display:none")
            }.forEach { it.remove() }
            fragment.body().text().replace(' ', ' ').trim()
                .takeIf { it.isNotEmpty() && !it.contains("novelpia", ignoreCase = true) && !it.contains("노벨피아") }
        }
    }

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
