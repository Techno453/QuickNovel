package com.lagradost.quicknovel.translator.batch

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.quicknovel.translator.CostEstimator
import com.lagradost.quicknovel.translator.EngineConfig
import com.lagradost.quicknovel.translator.PromptProfiles
import com.lagradost.quicknovel.translator.PromptTemplates
import com.lagradost.quicknovel.translator.QualityChecks
import com.lagradost.quicknovel.translator.SourceHash
import com.lagradost.quicknovel.translator.SpendPricing
import com.lagradost.quicknovel.translator.StoryContext
import com.lagradost.quicknovel.translator.TokenUsage
import com.lagradost.quicknovel.translator.TokensPerChar
import com.lagradost.quicknovel.translator.TranslationEngine
import com.lagradost.quicknovel.translator.db.GlossaryTerm

/** A chapter to put into a batch: index, title and source (HTML or plain text). */
data class BatchChapterInput(val chapterNum: Int, val chapterName: String, val source: String)

/** A chapter as submitted: its chunks are stored so results can be reassembled and checked. */
data class BuiltChapter(
    val chapterNum: Int,
    val chapterName: String,
    val sourceHash: String,
    val chunks: List<String>,
) {
    val chars: Int get() = chunks.sumOf { it.length }
}

/** One JSONL request line. */
data class BatchLine(val customId: String, val body: Map<String, Any>)

data class BuiltBatch(val chapters: List<BuiltChapter>, val lines: List<BatchLine>) {
    val requestCount: Int get() = lines.size
}

/**
 * Builds the Batch API input for a range of chapters.
 *
 * The live pipeline is sequential (each chunk sees the terms found in earlier chunks and the
 * previous chunk's English); a batch cannot be. So every request gets: the same system prompt as
 * live translation, the bible entries of a snapshot taken before submission
 * ([TranslationEngine.selectPromptTerms] with the chunk's own terms, plus characters that appeared
 * in earlier chunks of the same chapter), the premise and style, and no previous-English tail.
 *
 * Chunking is the live chunker ([TranslationEngine.splitIntoChunks], version [CHUNKER_VERSION]);
 * the chunk texts are stored with the job, so reassembly never re-derives them.
 */
class BatchRequestBuilder(
    private val model: String,
    private val sourceLang: String,
    private val promptProfile: String = PromptProfiles.DEFAULT_ID,
    private val extraBody: Map<String, Any?> = emptyMap(),
    private val sendTemperature: Boolean = true,
) {
    constructor(config: EngineConfig, sourceLang: String) : this(
        config.model, sourceLang, config.promptProfile, config.extraBody, config.sendTemperature,
    )

    companion object {
        /** Version of the chunking used for batch requests (the live chunker, unchanged). */
        const val CHUNKER_VERSION = 1
        const val ENDPOINT = "/v1/chat/completions"

        private val mapper = jacksonObjectMapper()

        /** Chunks of a chapter source exactly as the live engine splits it. */
        fun chunksOf(source: String, sourceLang: String): List<String> =
            TranslationEngine.splitIntoChunks(TranslationEngine.htmlToPlainText(source), TranslationEngine.chunkSizeFor(sourceLang))

        /** One line per request, `{"custom_id","method","url","body"}`. */
        fun toJsonl(lines: List<BatchLine>): String = buildString {
            for (line in lines) {
                append(
                    mapper.writeValueAsString(
                        linkedMapOf("custom_id" to line.customId, "method" to "POST", "url" to ENDPOINT, "body" to line.body)
                    )
                )
                append('\n')
            }
        }
    }

    fun build(
        novelId: String,
        chapters: List<BatchChapterInput>,
        bibleSnapshot: List<GlossaryTerm>,
        context: StoryContext,
    ): BuiltBatch {
        val system = PromptTemplates.systemPrompt(sourceLang, PromptProfiles.byId(promptProfile))
        // Batch requests have no previous-chunk English and no previous-chapter summary.
        val chunkContext = context.copy(previousEnglishTail = "", previousChapterSummary = "", recentCharacters = emptyList())
        val built = mutableListOf<BuiltChapter>()
        val lines = mutableListOf<BatchLine>()
        for (chapter in chapters.sortedBy { it.chapterNum }) {
            val chunks = chunksOf(chapter.source, sourceLang)
            if (chunks.isEmpty()) continue
            built += BuiltChapter(chapter.chapterNum, chapter.chapterName, SourceHash.of(chapter.source), chunks)
            val seenCharacters = LinkedHashSet<String>()
            chunks.forEachIndexed { index, chunk ->
                val inChunk = QualityChecks.termsInSource(chunk, bibleSnapshot)
                val promptTerms = TranslationEngine.selectPromptTerms(inChunk, bibleSnapshot, seenCharacters)
                inChunk.filter { it.isCharacter }.forEach { seenCharacters += it.source }
                val user = PromptTemplates.buildUserPrompt(chunk, promptTerms, sourceLang, chunkContext, "")
                lines += BatchLine(
                    BatchCustomId(novelId, chapter.chapterNum, index).toString(),
                    TranslationEngine.buildChatBody(model, system, user, TranslationEngine.TEMPERATURE, sendTemperature, extraBody),
                )
            }
        }
        return BuiltBatch(built, lines)
    }
}

/** Cost arithmetic for batches. */
object BatchCost {
    /** Estimate for [chapters] at batch price: the live per-chapter estimate × [SpendPricing.BATCH_DISCOUNT]. */
    fun estimate(
        chapters: List<BuiltChapter>,
        pricing: CostEstimator.ModelPricing?,
        tokensPerChar: TokensPerChar,
        sourceLang: String,
        bibleTerms: Int,
    ): Double? {
        pricing ?: return null
        return chapters.sumOf {
            CostEstimator.estimateChapter(it.chars, pricing, tokensPerChar, sourceLang, strictMode = false, bibleTerms = bibleTerms).usd ?: 0.0
        } * SpendPricing.BATCH_DISCOUNT
    }

    /** Estimate for [count] chapters of the average length, before the sources are fetched. */
    fun estimateForChapters(config: EngineConfig, count: Int, sourceLang: String, bibleTerms: Int): Double? =
        CostEstimator.estimateForConfig(config, count, sourceLang, strictMode = false, bibleTerms = bibleTerms)
            .usd?.times(SpendPricing.BATCH_DISCOUNT)

    /** What OpenAI bills for [usage] of batch requests. */
    fun actual(usage: TokenUsage, pricing: CostEstimator.ModelPricing?): Double =
        SpendPricing.usd(usage, pricing, SpendPricing.BATCH_DISCOUNT)
}

/** Parses batch output / error files (JSONL). */
object BatchOutputParser {
    private val mapper = jacksonObjectMapper()

    fun parse(jsonl: String?): List<BatchLineResult> {
        if (jsonl.isNullOrBlank()) return emptyList()
        return jsonl.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { line ->
            val node = try {
                mapper.readTree(line)
            } catch (e: Exception) {
                return@mapNotNull null
            }
            parseLine(node)
        }.toList()
    }

    private fun parseLine(node: JsonNode): BatchLineResult? {
        val customId = node.path("custom_id").asText("").takeIf { it.isNotEmpty() } ?: return null
        val response = node.path("response")
        val status = response.path("status_code").takeIf { it.isNumber }?.asInt()
        val body = response.path("body")
        val errorNode = node.path("error")
        var error = when {
            errorNode.isObject -> errorNode.path("message").asText("").ifBlank { errorNode.path("code").asText("error") }
            errorNode.isTextual -> errorNode.asText()
            else -> null
        }
        if (error == null && status != null && status != 200) {
            error = body.path("error").path("message").asText("").ifBlank { "HTTP $status" }
        }
        val choice = body.path("choices").path(0)
        val message = choice.path("message")
        val content = message.path("content").takeIf { it.isTextual }?.asText()
        val finish = choice.path("finish_reason").takeIf { it.isTextual }?.asText()
        if (error == null && status == 200 && content == null) {
            // Chat completions carry `message.refusal` (null normally); a refusal has no content.
            val refusal = message.path("refusal").takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
            error = if (refusal != null) "refused: ${refusal.take(200)}" else "no content in the reply"
        }
        val usage = if (body.isObject) TranslationEngine.parseUsage(body) else null
        return BatchLineResult(customId, status, content, finish, usage, error)
    }
}

/** Reassembles batch results per chapter, in chunk order. */
object BatchAssembler {
    /**
     * @param chapters the chapters as submitted (chunk counts come from them)
     * @param results output-file and error-file lines (any order; lines of other novels are ignored)
     */
    fun assemble(novelId: String, chapters: List<BuiltChapter>, results: List<BatchLineResult>): List<AssembledChapter> {
        val byKey = HashMap<Pair<Int, Int>, BatchLineResult>()
        for (r in results) {
            val id = BatchCustomId.parse(r.customId) ?: continue
            if (id.novelId != novelId) continue
            val key = id.chapter to id.chunkIndex
            val existing = byKey[key]
            // A success wins over an error line for the same request.
            if (existing == null || (!existing.succeeded && r.succeeded)) byKey[key] = r
        }
        return chapters.sortedBy { it.chapterNum }.map { chapter ->
            var usage = TokenUsage.ZERO
            val outcomes = chapter.chunks.indices.map { index ->
                val r = byKey[chapter.chapterNum to index] ?: return@map ChunkOutcome.Missing
                r.usage?.let { usage += it }
                classify(r)
            }
            AssembledChapter(chapter.chapterNum, outcomes, usage)
        }
    }

    private fun classify(r: BatchLineResult): ChunkOutcome {
        if (!r.succeeded) return ChunkOutcome.Failed(r.error ?: "HTTP ${r.statusCode ?: "?"}")
        val content = r.content.orEmpty()
        return when {
            r.finishReason == "length" -> ChunkOutcome.Failed("output cut off (finish_reason=length)")
            TranslationEngine.isContentFilterFinish(r.finishReason) -> ChunkOutcome.Failed("blocked by the content filter")
            content.isBlank() -> ChunkOutcome.Failed("empty reply")
            else -> ChunkOutcome.Ok(content, r.finishReason)
        }
    }
}
