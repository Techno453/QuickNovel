package com.lagradost.quicknovel.bench

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File

/** Bench-owned story bible; [BenchEngineAdapter] maps it onto whatever the engine accepts. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class BibleEntry(
    val source: String,
    val english: String,
    /** character | location | technique | rank | sect | artifact | creature | title | other */
    val category: String = "other",
    /** male | female | unknown (characters only) */
    val gender: String = "unknown",
    val role: String = "",
    val aliases: List<String> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Bible(
    val sampleId: String,
    val premise: String = "",
    val entries: List<BibleEntry> = emptyList(),
    val seededBy: String = "",
    val usd: Double = 0.0,
) {
    val characters: List<BibleEntry> get() = entries.filter { it.category == "character" }
}

/** One pre-pass call per sample (mirrors the app's upcoming "Analyze" step), cached so every model and run uses the same bible. */
class BibleSeeder(private val api: BenchOpenAi?, private val model: ModelSpec, private val cacheDir: File) {

    fun cacheFile(sampleId: String) = File(cacheDir, "bible-$sampleId.json")

    fun cached(sampleId: String): Bible? = cacheFile(sampleId).takeIf { it.isFile }
        ?.let { runCatching { benchMapper.readValue<Bible>(it) }.getOrNull() }

    fun seed(sample: Sample): Pair<Bible, String?> {
        cached(sample.id)?.let { return it to null }
        val api = api ?: return Bible(sample.id, seededBy = "none (dry run)") to null
        val romanization = when (sample.lang) {
            "ja" -> "Hepburn romanization, given name first (Western order); use established English names for katakana foreign names (e.g. ルーデウス -> Rudeus)"
            "ko" -> "Revised Romanization, family name first (e.g. Kim Dokja)"
            else -> "Hanyu Pinyin without tone marks, family name first (e.g. Lin Feng)"
        }
        val system = "You build translation glossaries (story bibles) for web novels. Reply with a single JSON object only."
        val user = """
Read this ${langName(sample.lang)} web-novel excerpt and list its proper nouns for an English translation glossary.

Return JSON: {"premise": "<one sentence>", "entries": [{"source": "<exact string as it appears in the text>", "english": "<English rendering>", "category": "character|location|technique|rank|sect|artifact|creature|title|other", "gender": "male|female|unknown", "role": "<one short line>", "aliases": ["<other exact source spellings in the text>"]}]}

Rules:
- Characters: render names with $romanization. "english" must be the form a translation should use in running text (the name as it is normally used, e.g. given name only if the text uses only the given name).
- Include every named character (gender from context; "unknown" if unclear), plus places, organizations, techniques, ranks/titles, items. At most 25 entries.
- "source" must be copied exactly from the text. Do not include common nouns.

Text:
${sample.text}
""".trimIndent()
        val reply = api.chat(model, "bible", sample.id, system, user, maxCompletionTokens = 3000, jsonObject = true)
        if (reply.error != null) return Bible(sample.id, seededBy = "failed: ${reply.error}") to reply.error
        val parsed = runCatching { benchMapper.readTree(reply.content) }.getOrNull()
            ?: return Bible(sample.id, seededBy = "unparseable") to "bible JSON unparseable"
        val entries = parsed.path("entries").mapNotNull { n ->
            val source = n.path("source").asText("").trim()
            val english = n.path("english").asText("").trim()
            if (source.isEmpty() || english.isEmpty()) return@mapNotNull null
            BibleEntry(
                source = source,
                english = english,
                category = n.path("category").asText("other").trim().lowercase(),
                gender = n.path("gender").asText("unknown").trim().lowercase(),
                role = n.path("role").asText("").trim(),
                aliases = n.path("aliases").mapNotNull { a -> a.asText("").trim().takeIf { it.isNotEmpty() && it != source } },
            )
        }.distinctBy { it.source }
        val bible = Bible(sample.id, parsed.path("premise").asText(""), entries, model.id, reply.call?.usd ?: 0.0)
        cacheDir.mkdirs()
        benchMapper.writeValue(cacheFile(sample.id), bible)
        return bible to null
    }

    companion object {
        fun langName(lang: String) = when (lang) {
            "ja" -> "Japanese"; "ko" -> "Korean"; else -> "Chinese"
        }
    }
}
