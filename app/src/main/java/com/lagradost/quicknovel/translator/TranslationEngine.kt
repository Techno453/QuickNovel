package com.lagradost.quicknovel.translator

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.quicknovel.translator.db.GlossaryTerm
import com.lagradost.quicknovel.translator.db.PendingTerm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.min

data class TranslationResult(
    /** Chapter HTML (<p> per paragraph). */
    val englishText: String,
    /** Terms for manual review: low confidence, conflicts, or auto-add disabled. */
    val pendingTerms: List<PendingTerm>,
    val chunkCount: Int,
    /** Confident new terms (auto_added = true, id = 0); already used for the rest of this chapter. */
    val autoAddedTerms: List<GlossaryTerm> = emptyList(),
    /** Existing unlocked auto-added terms that gained a gender / notes (keep their ids). */
    val updatedTerms: List<GlossaryTerm> = emptyList(),
    /** Chapter summary for the next chapter's context ("" if the model gave none). */
    val chapterSummary: String = "",
    /** Source names of the characters present in this chapter. */
    val charactersPresent: List<String> = emptyList(),
    /** Bible terms whose rendering was missing from the kept output (summed over chunks). */
    val glossaryViolations: Int = 0,
    /** Untranslated CJK runs left in the kept output. */
    val untranslatedFragments: Int = 0,
    /** Chunks whose kept output had fewer than 70% of the input paragraphs. */
    val paragraphWarnings: Int = 0,
    /** Strict-mode re-requests made. */
    val retries: Int = 0,
    /** "源 → English" for each violated term (distinct), for logs and UI. */
    val violatedTerms: List<String> = emptyList(),
    val leakedFragments: List<String> = emptyList(),
    /** Chunks re-translated by the fallback model (content filter or refusal). */
    val fallbackChunks: Int = 0,
    /** Model that produced the text: the primary, the fallback, or "primary + fallback" when mixed. */
    val modelUsed: String = "",
) {
    val usedFallback: Boolean get() = fallbackChunks > 0
}

class TranslationApiException(
    message: String,
    /** True for transient failures (rate limit, 5xx) that are worth retrying. */
    val retryable: Boolean = false,
    /** True when the provider's content filter blocked the request (never retryable with the same model). */
    val contentFiltered: Boolean = false,
) : Exception(message)

/**
 * Translates chapters through an OpenAI-compatible chat completions endpoint.
 *
 * @param baseUrl e.g. https://api.openai.com/v1 (the "/chat/completions" path is appended)
 * @param sourceLang ISO 639-1 source language (zh/ja/ko) — selects prompts and chunking.
 * @param extraBody extra top-level request fields merged into every request body (e.g.
 *   `reasoning_effort`, OpenRouter's `reasoning`); a null value removes that field (e.g.
 *   `"temperature" to null` for models that reject it). See [LlmConfig.parseExtraBody].
 * @param sendTemperature false for models that must not get a `temperature` (Gemini 3, Claude).
 * @param headers extra HTTP headers (e.g. OpenRouter attribution).
 * @param fallback engine that re-translates a chunk blocked by a content filter or refused by
 *   this model (once, no further fallback); null = no fallback.
 */
class TranslationEngine(
    private val apiKey: String,
    private val model: String,
    baseUrl: String = LlmConfig.DEFAULT_BASE_URL,
    private val sourceLang: String = TranslationLanguages.DEFAULT,
    private val client: OkHttpClient = defaultClient,
    private val extraBody: Map<String, Any?> = emptyMap(),
    sendTemperature: Boolean = true,
    private val headers: Map<String, String> = emptyMap(),
    private val fallback: TranslationEngine? = null,
    /** [PromptProfile] id for this model's system prompt (overridable per call via [TranslationOptions.promptProfile]). */
    private val promptProfile: String = PromptProfiles.DEFAULT_ID,
    /** Price per token for the spend ledger; null = unknown (recorded as unpriced). */
    private val pricing: CostEstimator.ModelPricing? = CostEstimator.pricingFor(model),
    /** Provider id for the spend ledger. */
    private val providerId: String = LlmProviders.forBaseUrl(baseUrl).id,
    /** Receives the usage of every successful call; null = [UsageSinks.global]. */
    private val usageSink: UsageSink? = null,
) {
    /** Engine for [config], optionally with a [fallback] config for blocked or refused chunks. */
    constructor(
        config: EngineConfig,
        sourceLang: String = TranslationLanguages.DEFAULT,
        client: OkHttpClient = defaultClient,
        fallback: EngineConfig? = null,
    ) : this(
        apiKey = config.apiKey,
        model = config.model,
        baseUrl = config.baseUrl,
        sourceLang = sourceLang,
        client = client,
        extraBody = config.extraBody,
        sendTemperature = config.sendTemperature,
        headers = config.headers,
        fallback = fallback?.takeUnless { it.sameModelAs(config) }?.let { TranslationEngine(it, sourceLang, client, null) },
        promptProfile = config.promptProfile,
        pricing = SpendPricing.pricingFor(config),
        providerId = config.providerId,
    )

    private val endpoint = LlmConfig.chatCompletionsUrl(baseUrl)
    private val mapper = jacksonObjectMapper()
    private val isGemini = endpoint.contains("generativelanguage.googleapis.com", ignoreCase = true)

    /** Model id requests are sent with. */
    val modelId: String get() = model

    /** Prompt profile id this engine uses when [TranslationOptions.promptProfile] is null. */
    val promptProfileId: String get() = promptProfile

    /** Model id of the fallback engine, or null. */
    val fallbackModelId: String? get() = fallback?.model

    /**
     * Whether `temperature` is sent. Off for models configured without it; also switched off
     * once a model rejects a custom temperature (HTTP 400 mentioning temperature).
     */
    @Volatile
    private var sendTemperature = sendTemperature

    internal data class ChatResult(val content: String, val finishReason: String?, val usage: TokenUsage? = null)

    companion object {
        private const val DEFAULT_CHUNK_SIZE = 2000
        private const val KOREAN_CHUNK_SIZE = 2500
        private const val MAX_RETRIES = 4
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val MAX_LENGTH_SPLIT_DEPTH = 3
        private const val MIN_SPLIT_CHARS = 200
        internal const val TEMPERATURE = 0.3

        private val JSON = "application/json".toMediaType()

        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        // ---------------------------------------------------------------------------------
        // Pure helpers (internal for unit tests)
        // ---------------------------------------------------------------------------------

        /** Sentence terminators that always end a sentence (CJK + full-width forms). */
        private const val CJK_TERMINATORS = "。！？；…‥｡．"

        /** Latin terminators: only a boundary when followed by whitespace / end / non-ASCII. */
        private const val LATIN_TERMINATORS = ".!?"

        /** Closing quotes/brackets that belong to the sentence they close. */
        private const val CLOSERS = "」』”’\"'）)】》〉］]｝}〕"

        /** Legacy inline marker pattern (see [TranslationProtocol.NEW_TERM_PATTERN]). */
        internal val NEW_TERM_PATTERN: Regex get() = TranslationProtocol.NEW_TERM_PATTERN

        private const val MAX_PROMPT_TERMS = 120
        private const val MAX_RECENT_CHARACTERS = 20
        private const val SUMMARY_MERGE_THRESHOLD = 900
        private const val MAX_SUMMARY_CHARS = 1000

        /** Bible entries for the prompt: terms in this chunk, plus recently present characters (pronoun context). */
        internal fun selectPromptTerms(
            inChunk: List<GlossaryTerm>,
            bible: List<GlossaryTerm>,
            recentCharacters: Set<String>,
        ): List<GlossaryTerm> {
            val inChunkSet = inChunk.toHashSet()
            val recent = bible.filter { it.isCharacter && it !in inChunkSet && it.source in recentCharacters }
                .take(MAX_RECENT_CHARACTERS)
            return (inChunk + recent).take(MAX_PROMPT_TERMS)
        }

        /**
         * Chat completions request body (also used for Batch API lines): model, messages,
         * temperature unless [sendTemperature] is off, then [extraBody] (a null value removes a field).
         */
        internal fun buildChatBody(
            model: String,
            system: String?,
            user: String,
            temperature: Double,
            sendTemperature: Boolean,
            extraBody: Map<String, Any?>,
            maxTokens: Int? = null,
        ): Map<String, Any> {
            val messages = buildList {
                if (!system.isNullOrBlank()) add(mapOf("role" to "system", "content" to system))
                add(mapOf("role" to "user", "content" to user))
            }
            return buildMap {
                put("model", model)
                put("messages", messages)
                if (sendTemperature) put("temperature", temperature)
                if (maxTokens != null) put("max_tokens", maxTokens)
                for ((key, value) in extraBody) if (value == null) remove(key) else put(key, value)
            }
        }

        /** `usage.prompt_tokens` / `usage.completion_tokens` of a chat completion, or null if absent. */
        internal fun parseUsage(node: com.fasterxml.jackson.databind.JsonNode): TokenUsage? {
            val usage = node.path("usage")
            if (!usage.isObject) return null
            val prompt = usage.path("prompt_tokens").takeIf { it.isNumber } ?: usage.path("input_tokens")
            val completion = usage.path("completion_tokens").takeIf { it.isNumber } ?: usage.path("output_tokens")
            if (!prompt.isNumber && !completion.isNumber) return null
            return TokenUsage(prompt.asInt(0), completion.asInt(0))
        }

        /** "End of Chapter" boilerplate lines that source sites append. */
        private val END_OF_CHAPTER = Regex(
            """^\(?(\*\s*)?([Ee]nd\s+[Oo]f\s+[Cc]hapter|[Cc]hapter\s+\d*\s+[Ee]nd)\s*(\d+)?\s*(\*\s*)?\)?$""",
            RegexOption.IGNORE_CASE
        )

        private val CODE_FENCE_LINE = Regex("""^\s*```[\w-]*\s*$""")
        private val LEADING_HEADING_ECHO = Regex(
            """^\s*#{1,6}\s*\**\s*(?:English\s+)?Translation\s*\**\s*[:：]?\s*\**\s*(?:\n|$)""",
            RegexOption.IGNORE_CASE
        )
        private val LEADING_LABEL_ECHO = Regex(
            """^\s*\**\s*(?:English\s+)?Translation\s*\**\s*[:：]\s*\**\s*""",
            RegexOption.IGNORE_CASE
        )
        private val HTML_HINT = Regex("""<\s*(p|br|div|span|h[1-6]|li|section|article)\b""", RegexOption.IGNORE_CASE)

        internal fun chunkSizeFor(lang: String?): Int =
            if (TranslationLanguages.normalize(lang) == TranslationLanguages.KOREAN) KOREAN_CHUNK_SIZE
            else DEFAULT_CHUNK_SIZE

        private fun isAsciiAlnum(c: Char) = c.code < 0x80 && c.isLetterOrDigit()

        /**
         * End offsets (exclusive) of the sentences in [text]; the last offset is text.length.
         * Trailing whitespace is attached to the preceding sentence, so consecutive slices
         * reproduce the input exactly.
         *
         * Handles Chinese/Japanese terminators (。！？；…), closing quotes after them (。」),
         * back-to-back Japanese dialogue (」「), and Korean/Latin punctuation (. ! ?) which only
         * ends a sentence before whitespace, the end of text or a non-ASCII character.
         */
        internal fun sentenceBoundaries(text: String): List<Int> {
            val out = mutableListOf<Int>()
            val n = text.length
            var i = 0
            while (i < n) {
                val c = text[i]
                var boundary = -1
                if (c in CJK_TERMINATORS) {
                    var j = i + 1
                    while (j < n && (text[j] in CJK_TERMINATORS || text[j] in LATIN_TERMINATORS || text[j] in CLOSERS)) j++
                    boundary = j
                } else if (c in LATIN_TERMINATORS) {
                    var j = i + 1
                    while (j < n && (text[j] in LATIN_TERMINATORS || text[j] in CJK_TERMINATORS || text[j] in CLOSERS)) j++
                    if (j >= n || text[j].isWhitespace() || !isAsciiAlnum(text[j])) boundary = j
                } else if ((c == '」' || c == '』') && i + 1 < n && (text[i + 1] == '「' || text[i + 1] == '『')) {
                    boundary = i + 1
                }
                if (boundary > 0) {
                    var k = boundary
                    while (k < n && text[k].isWhitespace()) k++
                    if (k < n) out.add(k)
                    i = k
                } else {
                    i++
                }
            }
            if (n > 0) out.add(n)
            return out
        }

        internal fun splitSentences(text: String): List<String> {
            var start = 0
            return sentenceBoundaries(text).map { end -> text.substring(start, end).also { start = end } }
        }

        /** Splits a run of text with no usable sentence boundary into pieces of at most [maxChars]. */
        internal fun hardSplit(text: String, maxChars: Int): List<String> {
            if (text.length <= maxChars) return listOf(text)
            val out = mutableListOf<String>()
            var rest = text
            while (rest.length > maxChars) {
                val window = rest.substring(0, maxChars)
                var cut = window.indexOfLast { it.isWhitespace() || it in "，、,：:" } + 1
                if (cut < maxChars / 2) cut = maxChars
                if (cut < rest.length && Character.isLowSurrogate(rest[cut])) cut--
                out.add(rest.substring(0, cut))
                rest = rest.substring(cut)
            }
            if (rest.isNotEmpty()) out.add(rest)
            return out
        }

        /**
         * Splits source text into chunks of roughly [maxChars] characters. Paragraphs
         * (separated by one or more newlines) are kept together and joined by blank lines;
         * paragraphs longer than [maxChars] are split at sentence boundaries.
         */
        internal fun splitIntoChunks(text: String, maxChars: Int = DEFAULT_CHUNK_SIZE): List<String> {
            val paragraphs = text.replace("\r\n", "\n").split('\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val chunks = mutableListOf<String>()
            val current = StringBuilder()

            fun flush() {
                if (current.isNotBlank()) chunks.add(current.toString().trim())
                current.clear()
            }

            for (paragraph in paragraphs) {
                if (paragraph.length > maxChars) {
                    flush()
                    val piece = StringBuilder()
                    for (sentence in splitSentences(paragraph).flatMap { hardSplit(it, maxChars) }) {
                        if (piece.isNotEmpty() && piece.length + sentence.length > maxChars) {
                            chunks.add(piece.toString().trim())
                            piece.clear()
                        }
                        piece.append(sentence)
                    }
                    if (piece.isNotBlank()) chunks.add(piece.toString().trim())
                } else {
                    if (current.isNotEmpty() && current.length + paragraph.length + 2 > maxChars) flush()
                    if (current.isNotEmpty()) current.append("\n\n")
                    current.append(paragraph)
                }
            }
            flush()
            return chunks
        }

        /**
         * Splits a chunk into two halves of similar size, preferring a paragraph break near the
         * middle, then a sentence boundary, then whitespace. Used when the model hits its
         * output limit (finish_reason == "length").
         */
        internal fun splitInHalf(chunk: String): Pair<String, String> {
            val text = chunk.trim()
            if (text.length < 2) return text to ""
            val mid = text.length / 2
            val lo = text.length / 4
            val hi = text.length - lo

            val paragraphCuts = Regex("""\n\s*\n""").findAll(text).map { it.range.last + 1 }.toList()
            val sentenceCuts = sentenceBoundaries(text).filter { it < text.length }

            val cut = paragraphCuts.filter { it in lo..hi }.minByOrNull { abs(it - mid) }
                ?: sentenceCuts.filter { it in 1 until text.length }.minByOrNull { abs(it - mid) }
                ?: paragraphCuts.filter { it in 1 until text.length }.minByOrNull { abs(it - mid) }
                ?: run {
                    var c = text.lastIndexOf(' ', mid).takeIf { it > lo } ?: mid
                    if (Character.isLowSurrogate(text[c])) c--
                    c
                }
            return text.substring(0, cut).trim() to text.substring(cut).trim()
        }

        /** Extracts (source, suggestion) pairs from [NEW: …] markers. */
        internal fun extractNewTerms(text: String): List<Pair<String, String>> =
            TranslationProtocol.extractNewTerms(text)

        /** Replaces [NEW: source → suggestion] markers with just the suggestion. */
        internal fun stripNewTermMarkers(text: String): String =
            TranslationProtocol.stripNewTermMarkers(text)

        /** Removes markdown code fences and an echoed "## Translation:" heading from model output. */
        internal fun cleanModelOutput(raw: String): String {
            var s = raw.replace("\r\n", "\n")
            s = s.lines().filterNot { CODE_FENCE_LINE.matches(it) }.joinToString("\n").trim()
            // The prompt ends with "## Translation:", which some models echo back.
            repeat(2) {
                s = s.replaceFirst(LEADING_HEADING_ECHO, "").trimStart()
                s = s.replaceFirst(LEADING_LABEL_ECHO, "").trimStart()
            }
            return s.trim()
        }

        /**
         * Wraps translated paragraphs in <p> tags so the reader's HTML pipeline keeps paragraph
         * spacing. Any newline (single or blank line) separates paragraphs, since models are
         * inconsistent about it.
         */
        internal fun buildParagraphHtml(parts: List<String>): String =
            parts.joinToString("\n\n")
                .split(Regex("""\r?\n"""))
                .map { it.trim() }
                .filter { it.isNotEmpty() && !END_OF_CHAPTER.matches(it) }
                .joinToString("\n") { "<p>$it</p>" }

        /**
         * Providers may return chapter HTML; convert it to plain paragraphs (blank-line separated)
         * so we don't pay for tags. Plain text passes through unchanged.
         */
        internal fun htmlToPlainText(input: String): String {
            if (!HTML_HINT.containsMatchIn(input)) return input
            val body = Jsoup.parseBodyFragment(input).body()
            body.select("script, style, noscript").remove()
            val sb = StringBuilder()
            NodeTraversor.traverse(object : NodeVisitor {
                override fun head(node: Node, depth: Int) {
                    when {
                        node is TextNode -> sb.append(node.text())
                        node is Element && node.normalName() == "br" -> sb.append('\n')
                        node is Element && node.isBlock && sb.isNotEmpty() && sb.last() != '\n' -> sb.append('\n')
                    }
                }

                override fun tail(node: Node, depth: Int) {
                    if (node is Element && node.isBlock) sb.append('\n')
                }
            }, body)
            return sb.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
        }

        /** finish_reason values that mean the provider's safety filter cut the output. */
        private val FILTER_FINISH_REASONS = setOf(
            "content_filter", "sensitive", "safety", "prohibited_content", "blocklist", "spii", "recitation",
        )

        /** finish_reason values that mean the model finished normally (or ran out of tokens). */
        private val NORMAL_FINISH_REASONS = setOf("stop", "length", "tool_calls", "function_call", "end_turn", "max_tokens")

        /**
         * True if [finishReason] means the output was blocked: OpenAI / Kimi `content_filter`,
         * Z.ai `sensitive`, Gemini safety reasons. On Gemini ([gemini]) anything other than a
         * normal stop or length limit counts as blocked.
         */
        internal fun isContentFilterFinish(finishReason: String?, gemini: Boolean = false): Boolean {
            val reason = finishReason?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
            if (reason in FILTER_FINISH_REASONS) return true
            return gemini && reason !in NORMAL_FINISH_REASONS
        }

        /** Substrings (lower case) of provider error bodies that mean a content-filter block. */
        private val FILTER_ERROR_MARKERS = listOf(
            "content exists risk", // DeepSeek
            "data_inspection_failed", // Qwen (OpenAI-compatible mode)
            "datainspectionfailed", // Qwen (native error code)
            "content_filter", // Kimi, Azure OpenAI
            "content filter",
            "prohibited_content", // Gemini
            "content_policy_violation", // OpenAI
            "\"finish_reason\":\"sensitive\"", // Z.ai
        )

        private val ZAI_FILTER_CODE = Regex(""""code"\s*:\s*"?1301"?(?![0-9])""")

        /**
         * True if an HTTP error response is a provider content-filter block (DeepSeek "Content
         * Exists Risk", Qwen data_inspection_failed, Kimi content_filter, Z.ai code 1301, Gemini
         * PROHIBITED_CONTENT, OpenAI content_policy_violation). Retrying with the same model never helps.
         */
        internal fun isContentFilterError(code: Int, body: String): Boolean {
            if (code !in 400..499 || code == 401 || code == 404 || code == 429) return false
            val lower = body.lowercase()
            return FILTER_ERROR_MARKERS.any { it in lower } || ZAI_FILTER_CODE.containsMatchIn(body)
        }

        /** Await an OkHttp call, cancelling it if the coroutine is cancelled. */
        private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { runCatching { cancel() } }
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) cont.resume(response) else response.close()
                }
            })
        }
    }

    /** Output of one source chunk (possibly assembled from split halves). */
    internal data class ChunkOutput(
        val translation: String,
        val terms: List<DiscoveredTerm>,
        val summaries: List<String>,
        val summaryCharacters: List<String>,
        val violatedTerms: List<GlossaryTerm>,
        val leakedFragments: List<String>,
        val paragraphWarnings: Int,
        val retries: Int,
        /** Issue score of the kept attempt (lower is better). */
        val issueCount: Int,
        val outputParagraphs: Int,
        /** Source chunks re-translated by the fallback model. */
        val fallbackChunks: Int = 0,
    ) {
        operator fun plus(other: ChunkOutput) = ChunkOutput(
            translation = listOf(translation, other.translation).filter { it.isNotBlank() }.joinToString("\n\n"),
            terms = terms + other.terms,
            summaries = summaries + other.summaries,
            summaryCharacters = summaryCharacters + other.summaryCharacters,
            violatedTerms = violatedTerms + other.violatedTerms,
            leakedFragments = leakedFragments + other.leakedFragments,
            paragraphWarnings = paragraphWarnings + other.paragraphWarnings,
            retries = retries + other.retries,
            issueCount = issueCount + other.issueCount,
            outputParagraphs = outputParagraphs + other.outputParagraphs,
            fallbackChunks = fallbackChunks + other.fallbackChunks,
        )
    }

    /**
     * Translates a whole chapter with the Story Bible protocol.
     *
     * Pure JVM entry point (no Android dependencies) so benchmarks can call it directly with an
     * explicit bible, context and options.
     *
     * @param sourceText chapter text in [sourceLang] (plain text or HTML)
     * @param glossaryTerms the Story Bible (all terms of the novel)
     * @param context continuity context and translation style
     * @param options strict-mode retries, auto-add, carry-context switches
     * @param onProgress fraction of chunks done, 0..1
     */
    suspend fun translateChapter(
        sourceText: String,
        novelId: String,
        chapterNum: Int,
        glossaryTerms: List<GlossaryTerm>,
        context: StoryContext = StoryContext(),
        options: TranslationOptions = TranslationOptions(),
        onProgress: (Float) -> Unit = {}
    ): TranslationResult = withContext(Dispatchers.IO) {
        val plainSource = htmlToPlainText(sourceText)
        val chunks = splitIntoChunks(plainSource, chunkSizeFor(sourceLang))
        assembleChapter(chunks, novelId, chapterNum, glossaryTerms, context, options, onProgress) { request ->
            translateChunkLive(request, options)
        }
    }

    /** One chunk's inputs, as [assembleChapter] passes them to its chunk producer. */
    internal data class ChunkRequest(
        val index: Int,
        val chunk: String,
        val promptTerms: List<GlossaryTerm>,
        /** Bible terms present in the chunk (for the checks). */
        val checkTerms: List<GlossaryTerm>,
        val context: StoryContext,
        /** English of the previous chunk ("" for the first chunk or with story context off). */
        val preceding: String,
    )

    /**
     * The chapter loop shared by live translation and Batch API results: for each chunk, select
     * the bible entries, get the chunk's output from [produce], fold discovered terms into the
     * in-memory bible (so later chunks see them), then build the [TranslationResult].
     */
    internal suspend fun assembleChapter(
        chunks: List<String>,
        novelId: String,
        chapterNum: Int,
        glossaryTerms: List<GlossaryTerm>,
        context: StoryContext,
        options: TranslationOptions,
        onProgress: (Float) -> Unit = {},
        produce: suspend (ChunkRequest) -> ChunkOutput,
    ): TranslationResult {
        val plainSource = chunks.joinToString("\n\n")
        val chunkContext = if (options.carryStoryContext) context else StoryContext(style = context.style)

        val bible = glossaryTerms.toMutableList()
        val autoAdded = LinkedHashMap<Long, GlossaryTerm>() // temporary negative id → new term
        val updated = LinkedHashMap<Long, GlossaryTerm>() // existing DB id → updated term
        val pending = mutableListOf<PendingTerm>()
        var nextTempId = -1L

        val recentCharacters = LinkedHashSet(chunkContext.recentCharacters)
        var preceding = chunkContext.previousEnglishTail
        val parts = mutableListOf<String>()
        val summaries = mutableListOf<String>()
        val summaryCharacters = mutableListOf<String>()
        val violated = mutableListOf<GlossaryTerm>()
        val leaks = mutableListOf<String>()
        var paragraphWarnings = 0
        var retries = 0
        var fallbackChunks = 0
        var chunksWithFallback = 0

        chunks.forEachIndexed { index, chunk ->
            coroutineContext.ensureActive()
            onProgress(index.toFloat() / chunks.size)

            val inChunk = QualityChecks.termsInSource(chunk, bible)
            val promptTerms = selectPromptTerms(inChunk, bible, recentCharacters)
            val out = produce(ChunkRequest(index, chunk, promptTerms, inChunk, chunkContext, preceding))

            // Fold discovered terms into the in-memory bible so the next chunk already uses them.
            val decisions = BibleMerger.decide(out.terms, bible, novelId, chapterNum, chunk, options)
            for (term in decisions.autoAdd) {
                val withId = term.copy(id = nextTempId--)
                bible += withId
                autoAdded[withId.id] = withId
                if (withId.isCharacter) recentCharacters += withId.source
            }
            for (term in decisions.updates) {
                val i = bible.indexOfFirst { it.id == term.id }
                if (i >= 0) bible[i] = term
                if (term.id < 0) autoAdded[term.id] = term else updated[term.id] = term
            }
            pending += decisions.pending
            inChunk.filter { it.isCharacter }.forEach { recentCharacters += it.source }

            parts += out.translation
            if (options.carryStoryContext) preceding = out.translation
            summaries += out.summaries
            summaryCharacters += out.summaryCharacters
            violated += out.violatedTerms
            leaks += out.leakedFragments
            paragraphWarnings += out.paragraphWarnings
            retries += out.retries
            fallbackChunks += out.fallbackChunks
            if (out.fallbackChunks > 0) chunksWithFallback++
        }

        val summary = buildChapterSummary(summaries, options)
        val present = (QualityChecks.termsInSource(plainSource, bible).filter { it.isCharacter }.map { it.source } +
            summaryCharacters.filter { BibleMerger.containsCjk(it) }).distinct()

        onProgress(1f)

        return TranslationResult(
            englishText = buildParagraphHtml(parts),
            pendingTerms = pending.distinctBy { it.source to it.suggestedEnglish.lowercase() },
            chunkCount = chunks.size,
            autoAddedTerms = autoAdded.values.map { it.copy(id = 0) },
            updatedTerms = updated.values.toList(),
            chapterSummary = summary,
            charactersPresent = present,
            glossaryViolations = violated.size,
            untranslatedFragments = leaks.size,
            paragraphWarnings = paragraphWarnings,
            retries = retries,
            violatedTerms = violated.map { "${it.source} → ${it.english}" }.distinct(),
            leakedFragments = leaks.distinct(),
            fallbackChunks = fallbackChunks,
            modelUsed = when {
                chunksWithFallback == 0 || fallback == null -> model
                chunksWithFallback >= chunks.size -> fallback?.model ?: model
                else -> "$model + ${fallback?.model}"
            },
        )
    }

    /**
     * Live translation of one chunk with the normal path (length splitting, fallback, strict
     * retry). Also used for batch chunks that came back missing or failed the checks.
     */
    internal suspend fun translateChunkLive(request: ChunkRequest, options: TranslationOptions): ChunkOutput =
        translateChunk(request.chunk, request.promptTerms, request.checkTerms, request.context, request.preceding, options, depth = 0)

    /** Parses and checks a model reply for [chunk] exactly like a live reply. */
    internal fun evaluateReply(chunk: String, raw: String, checkTerms: List<GlossaryTerm>): ChunkOutput =
        evaluate(chunk, raw, checkTerms)

    /** True if [candidate] has fewer issues (or as many with more paragraphs) than [current]. */
    internal fun isBetterOutput(candidate: ChunkOutput, current: ChunkOutput): Boolean = isBetter(candidate, current)

    /** Merges chunk summaries; only long multi-chunk summaries get a (cheap) merge call, and only with story context on. */
    private suspend fun buildChapterSummary(summaries: List<String>, options: TranslationOptions): String {
        val parts = summaries.map { it.trim() }.filter { it.isNotEmpty() }
        val merged = parts.joinToString(" ")
        if (parts.size > 1 && merged.length > SUMMARY_MERGE_THRESHOLD && options.carryStoryContext) {
            try {
                val result = chat(PromptTemplates.SUMMARY_MERGE_SYSTEM, merged, temperature = 0.0, maxRetries = 1)
                cleanModelOutput(result.content).replace(Regex("""\s+"""), " ").trim()
                    .takeIf { it.isNotEmpty() }?.let { return it.take(MAX_SUMMARY_CHARS) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // fall back to the plain merge
            }
        }
        return if (merged.length <= MAX_SUMMARY_CHARS) merged else merged.take(MAX_SUMMARY_CHARS).substringBeforeLast(' ') + "…"
    }

    /**
     * Translates one chunk; if the model runs out of output tokens, splits it and retries.
     * In strict mode a chunk that fails the checks is re-requested once with a reminder and the
     * attempt with fewer issues is kept.
     */
    private suspend fun translateChunk(
        chunk: String,
        promptTerms: List<GlossaryTerm>,
        checkTerms: List<GlossaryTerm>,
        context: StoryContext,
        preceding: String,
        options: TranslationOptions,
        depth: Int
    ): ChunkOutput {
        val system = PromptTemplates.systemPrompt(sourceLang, PromptProfiles.byId(options.promptProfile ?: promptProfile))
        val result = try {
            chat(system, PromptTemplates.buildUserPrompt(chunk, promptTerms, sourceLang, context, preceding))
        } catch (e: TranslationApiException) {
            if (e.contentFiltered && fallback != null) {
                return runFallback(chunk, promptTerms, checkTerms, context, preceding, options, depth) ?: throw e
            }
            throw e
        }
        if (fallback != null && isContentFilterFinish(result.finishReason, isGemini)) {
            runFallback(chunk, promptTerms, checkTerms, context, preceding, options, depth)?.let { return it }
        }
        if (result.finishReason == "length" && depth < MAX_LENGTH_SPLIT_DEPTH && chunk.length >= MIN_SPLIT_CHARS) {
            val (first, second) = splitInHalf(chunk)
            if (first.isNotBlank() && second.isNotBlank()) {
                val a = translateChunk(
                    first, promptTerms, QualityChecks.termsInSource(first, checkTerms), context, preceding, options, depth + 1
                )
                val b = translateChunk(
                    second, promptTerms, QualityChecks.termsInSource(second, checkTerms), context,
                    if (options.carryStoryContext) a.translation else "", options, depth + 1
                )
                return a + b
            }
        }

        var best = evaluate(chunk, result.content, checkTerms)
        if (fallback != null && QualityChecks.looksLikeRefusal(chunk, best.translation)) {
            runFallback(chunk, promptTerms, checkTerms, context, preceding, options, depth)?.let { return it }
        }
        if (!options.strictMode || best.issueCount == 0) return best
        return strictRetry(chunk, promptTerms, checkTerms, context, preceding, options, best)
    }

    /**
     * Strict mode: re-requests [chunk] once with a reminder of what [first] got wrong and keeps the
     * attempt with fewer issues. A failed retry keeps [first].
     */
    private suspend fun strictRetry(
        chunk: String,
        promptTerms: List<GlossaryTerm>,
        checkTerms: List<GlossaryTerm>,
        context: StoryContext,
        preceding: String,
        options: TranslationOptions,
        first: ChunkOutput,
    ): ChunkOutput {
        var best = first
        val system = PromptTemplates.systemPrompt(sourceLang, PromptProfiles.byId(options.promptProfile ?: promptProfile))
        val reminder = PromptTemplates.buildReminder(
            ChunkQuality(
                violatedTerms = best.violatedTerms,
                leakedFragments = best.leakedFragments,
                inputParagraphs = QualityChecks.countParagraphs(chunk),
                outputParagraphs = best.outputParagraphs,
            ),
            sourceLang
        )
        try {
            val retry = chat(system, PromptTemplates.buildUserPrompt(chunk, promptTerms, sourceLang, context, preceding, reminder))
            val second = evaluate(chunk, retry.content, checkTerms)
            if (second.translation.isNotBlank() && isBetter(second, best)) best = second
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep the first attempt; a failed retry must not fail the chapter.
        }
        return best.copy(retries = best.retries + 1)
    }

    /** The strict-mode re-request (with reminder) for a chunk whose batch reply failed the checks. */
    internal suspend fun strictRetryLive(request: ChunkRequest, first: ChunkOutput, options: TranslationOptions): ChunkOutput =
        strictRetry(request.chunk, request.promptTerms, request.checkTerms, request.context, request.preceding, options, first)

    /**
     * Re-translates a chunk with the fallback engine (which has no fallback of its own). Returns
     * null when there is no fallback or it failed too, so the caller keeps its own outcome.
     */
    private suspend fun runFallback(
        chunk: String,
        promptTerms: List<GlossaryTerm>,
        checkTerms: List<GlossaryTerm>,
        context: StoryContext,
        preceding: String,
        options: TranslationOptions,
        depth: Int,
    ): ChunkOutput? {
        val engine = fallback ?: return null
        return try {
            // The fallback model uses its own prompt profile, never the primary's override.
            engine.translateChunk(chunk, promptTerms, checkTerms, context, preceding, options.copy(promptProfile = null), depth)
                .takeIf { it.translation.isNotBlank() }
                ?.copy(fallbackChunks = 1)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun evaluate(chunk: String, raw: String, checkTerms: List<GlossaryTerm>): ChunkOutput {
        val parsed = TranslationProtocol.parse(raw)
        val quality = QualityChecks.check(chunk, parsed.translation, checkTerms)
        return ChunkOutput(
            translation = parsed.translation,
            terms = parsed.terms,
            summaries = listOf(parsed.summary).filter { it.isNotBlank() },
            summaryCharacters = parsed.summaryCharacters,
            violatedTerms = quality.violatedTerms,
            leakedFragments = quality.leakedFragments,
            paragraphWarnings = if (quality.paragraphShortfall) 1 else 0,
            retries = 0,
            issueCount = quality.issueCount,
            outputParagraphs = quality.outputParagraphs,
        )
    }

    private fun isBetter(candidate: ChunkOutput, current: ChunkOutput): Boolean =
        candidate.issueCount < current.issueCount ||
            (candidate.issueCount == current.issueCount && candidate.outputParagraphs > current.outputParagraphs)

    /**
     * "Analyze novel" pre-pass for one excerpt: extracts characters (gender, role, relationships,
     * speech style) and other terms plus a premise. No translation.
     */
    suspend fun extractStoryBible(
        excerpt: String,
        knownSources: Collection<String>,
        previousPremise: String,
        style: TranslationStyle?,
        chapterLabel: String,
    ): BibleExtraction = withContext(Dispatchers.IO) {
        val result = chat(
            PromptTemplates.analysisSystemPrompt(sourceLang),
            PromptTemplates.buildAnalysisPrompt(excerpt, sourceLang, knownSources, previousPremise, style, chapterLabel),
            temperature = 0.1
        )
        TranslationProtocol.parseAnalysis(result.content)
    }

    /**
     * Translates a short string (e.g. a search query) with a custom system prompt.
     * Returns the first non-blank line of the cleaned output.
     */
    suspend fun translateShort(text: String, systemPrompt: String): String = withContext(Dispatchers.IO) {
        val result = chat(systemPrompt, text, temperature = 0.0, maxRetries = 1)
        cleanModelOutput(result.content).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?.trim('"', '\'', '“', '”', '「', '」')
            ?.trim()
            .orEmpty()
    }

    /** Chat completion with retry/backoff on 429, 5xx and network errors. */
    internal suspend fun chat(
        system: String?,
        user: String,
        temperature: Double = TEMPERATURE,
        maxRetries: Int = MAX_RETRIES
    ): ChatResult = withContext(Dispatchers.IO) {
        var attempt = 0
        var backoff = INITIAL_BACKOFF_MS
        while (true) {
            coroutineContext.ensureActive()
            var waitMs = backoff
            try {
                return@withContext executeOnce(system, user, temperature)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RetryAfterException) {
                if (attempt >= maxRetries) throw TranslationApiException(e.message ?: "Rate limited")
                waitMs = maxOf(backoff, e.retryAfterMs)
            } catch (e: TranslationApiException) {
                if (!e.retryable || attempt >= maxRetries) throw e
            } catch (e: IOException) {
                coroutineContext.ensureActive() // an IOException from call.cancel() means we were cancelled
                if (attempt >= maxRetries) throw TranslationApiException("Network error: ${e.message}")
            }
            delay(min(waitMs, MAX_BACKOFF_MS))
            backoff = min(backoff * 2, MAX_BACKOFF_MS)
            attempt++
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException()
    }

    private class RetryAfterException(message: String, val retryAfterMs: Long) : Exception(message)

    private fun buildRequest(system: String?, user: String, temperature: Double, maxTokens: Int? = null): Request {
        val body = buildChatBody(model, system, user, temperature, sendTemperature, extraBody, maxTokens)
        return Request.Builder()
            .url(endpoint)
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .header("Content-Type", "application/json")
            .post(mapper.writeValueAsString(body).toRequestBody(JSON))
            .build()
    }

    private suspend fun executeOnce(system: String?, user: String, temperature: Double): ChatResult {
        val response = client.newCall(buildRequest(system, user, temperature)).await()
        val (code, body, retryAfter) = response.use {
            Triple(it.code, it.body?.string().orEmpty(), it.header("Retry-After"))
        }
        if (code in 200..299) return parseChatResponse(body).also { reportUsage(it.usage) }

        val message = errorMessage(body) ?: "HTTP $code"
        if (isContentFilterError(code, body)) {
            throw TranslationApiException("Blocked by the provider's content filter (HTTP $code): $message", contentFiltered = true)
        }
        if (code == 400 && sendTemperature && message.contains("temperature", ignoreCase = true)) {
            // Model doesn't support custom temperature; retry immediately without it.
            sendTemperature = false
            return executeOnce(system, user, temperature)
        }
        throw when {
            code == 401 || code == 403 ->
                TranslationApiException("Invalid API key or access denied (HTTP $code): $message")
            code == 404 ->
                TranslationApiException("Endpoint or model not found — check the base URL and model name ($message)")
            code == 429 -> {
                val ms = retryAfter?.trim()?.toLongOrNull()?.times(1000) ?: 0L
                if (message.contains("quota", ignoreCase = true)) {
                    TranslationApiException("Quota exceeded: $message")
                } else {
                    RetryAfterException("Rate limit exceeded. Please try again later.", ms)
                }
            }
            code == 408 || code == 409 || code == 425 || code in 500..599 ->
                TranslationApiException("Server error (HTTP $code): $message", retryable = true)
            else -> TranslationApiException("API error (HTTP $code): $message")
        }
    }

    private fun errorMessage(body: String): String? = try {
        val node = mapper.readTree(body)
        val err = node.path("error")
        (if (err.isTextual) err.asText() else err.path("message").asText(""))
            .takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    /** Records one successful call in the spend ledger (kind / novel / chapter from the coroutine's [SpendTag]). */
    private suspend fun reportUsage(usage: TokenUsage?) {
        val tag = coroutineContext[SpendTag]
        val tokens = usage ?: TokenUsage.ZERO
        val usd = SpendPricing.usd(tokens, pricing)
        tag?.meter?.add(usd)
        val sink = usageSink ?: UsageSinks.global ?: return
        try {
            sink.record(
                UsageEvent(
                    timestamp = System.currentTimeMillis(),
                    kind = tag?.kind ?: SpendKind.OTHER,
                    novelId = tag?.novelId,
                    chapterNum = tag?.chapterNum,
                    providerId = providerId,
                    model = model,
                    promptTokens = tokens.promptTokens,
                    completionTokens = tokens.completionTokens,
                    usd = usd,
                    priced = pricing != null && usage != null,
                    batchId = tag?.batchId,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The ledger must never fail a translation.
        }
    }

    private fun parseChatResponse(json: String): ChatResult {
        return try {
            val root = mapper.readTree(json)
            val choice = root.path("choices").path(0)
            ChatResult(
                content = choice.path("message").path("content").asText(""),
                finishReason = choice.path("finish_reason").takeIf { !it.isMissingNode && !it.isNull }?.asText(),
                usage = parseUsage(root),
            )
        } catch (e: Exception) {
            throw TranslationApiException("Failed to parse API response: ${e.message}")
        }
    }

    /**
     * Makes a minimal request against the configured endpoint.
     * @return null on success, otherwise a human-readable error.
     */
    suspend fun verify(): String? = withContext(Dispatchers.IO) {
        try {
            executeOnce(null, "Reply with OK.", 0.0)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: RetryAfterException) {
            null // rate limited means the key and endpoint work
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    /** Back-compat: true if [verify] succeeds. */
    suspend fun testApiKey(): Boolean = verify() == null
}
