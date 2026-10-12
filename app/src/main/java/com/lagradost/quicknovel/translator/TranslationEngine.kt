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
    val englishText: String,
    val pendingTerms: List<PendingTerm>,
    val chunkCount: Int
)

class TranslationApiException(
    message: String,
    /** True for transient failures (rate limit, 5xx) that are worth retrying. */
    val retryable: Boolean = false
) : Exception(message)

/**
 * Translates chapters through an OpenAI-compatible chat completions endpoint.
 *
 * @param baseUrl e.g. https://api.openai.com/v1 (the "/chat/completions" path is appended)
 * @param sourceLang ISO 639-1 source language (zh/ja/ko) — selects prompts and chunking.
 * @param extraBody extra top-level request fields merged into every request body (e.g.
 *   `reasoning_effort`, OpenRouter's `reasoning`); a null value removes that field (e.g.
 *   `"temperature" to null` for models that reject it). See [LlmConfig.parseExtraBody].
 */
class TranslationEngine(
    private val apiKey: String,
    private val model: String,
    baseUrl: String = LlmConfig.DEFAULT_BASE_URL,
    private val sourceLang: String = TranslationLanguages.DEFAULT,
    private val client: OkHttpClient = defaultClient,
    private val extraBody: Map<String, Any?> = emptyMap(),
) {
    private val endpoint = LlmConfig.chatCompletionsUrl(baseUrl)
    private val mapper = jacksonObjectMapper()

    /** Some models (reasoning models) reject a non-default temperature; we drop it once seen. */
    @Volatile
    private var sendTemperature = true

    internal data class ChatResult(val content: String, val finishReason: String?)

    companion object {
        private const val DEFAULT_CHUNK_SIZE = 2000
        private const val KOREAN_CHUNK_SIZE = 2500
        private const val MAX_RETRIES = 4
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val MAX_LENGTH_SPLIT_DEPTH = 3
        private const val MIN_SPLIT_CHARS = 200
        private const val TEMPERATURE = 0.3

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

        /** Matches [NEW: source → suggestion] with →, ->, =>, ⇒, ＞ arrows and full-width brackets/colons. */
        internal val NEW_TERM_PATTERN = Regex(
            """[\[［]\s*NEW\s*[:：]\s*(.+?)\s*(?:→|->|－>|-＞|－＞|=>|⇒|＞|>)\s*(.+?)\s*[\]］]"""
        )

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
            NEW_TERM_PATTERN.findAll(text).map { m ->
                m.groupValues[1].trim() to m.groupValues[2].trim()
            }.filter { it.first.isNotEmpty() && it.second.isNotEmpty() }.toList()

        /** Replaces [NEW: source → suggestion] markers with just the suggestion. */
        internal fun stripNewTermMarkers(text: String): String =
            text.replace(NEW_TERM_PATTERN) { it.groupValues[2].trim() }

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

    /**
     * Translates a whole chapter.
     *
     * @param sourceText chapter text in [sourceLang] (plain text or HTML)
     * @param onProgress fraction of chunks done, 0..1
     */
    suspend fun translateChapter(
        sourceText: String,
        novelId: String,
        chapterNum: Int,
        glossaryTerms: List<GlossaryTerm>,
        onProgress: (Float) -> Unit = {}
    ): TranslationResult = withContext(Dispatchers.IO) {
        val chunks = splitIntoChunks(htmlToPlainText(sourceText), chunkSizeFor(sourceLang))
        val aliases = glossaryTerms.associateWith { parseAliases(it.aliases) }
        val translatedParts = mutableListOf<String>()
        val allPendingTerms = mutableListOf<PendingTerm>()

        chunks.forEachIndexed { index, chunk ->
            coroutineContext.ensureActive()
            onProgress(index.toFloat() / chunks.size)

            val raw = translateChunk(chunk, aliases, depth = 0)

            extractNewTerms(raw).mapTo(allPendingTerms) { (source, suggestion) ->
                PendingTerm(
                    novelId = novelId,
                    source = source,
                    suggestedEnglish = suggestion,
                    chapterNum = chapterNum,
                    context = chunk.take(200)
                )
            }
            translatedParts.add(stripNewTermMarkers(raw).trim())
        }

        onProgress(1f)

        TranslationResult(
            englishText = buildParagraphHtml(translatedParts),
            pendingTerms = allPendingTerms.distinctBy { it.source },
            chunkCount = chunks.size
        )
    }

    private fun relevantTerms(chunk: String, aliases: Map<GlossaryTerm, List<String>>): List<GlossaryTerm> =
        aliases.filter { (term, alts) ->
            (term.source.isNotEmpty() && chunk.contains(term.source)) || alts.any { it.isNotEmpty() && chunk.contains(it) }
        }.keys.toList()

    /** Translates one chunk; if the model runs out of output tokens, splits it and retries. */
    private suspend fun translateChunk(
        chunk: String,
        aliases: Map<GlossaryTerm, List<String>>,
        depth: Int
    ): String {
        val prompt = PromptTemplates.buildUserPrompt(chunk, relevantTerms(chunk, aliases), sourceLang)
        val result = chat(PromptTemplates.systemPrompt(sourceLang), prompt)
        if (result.finishReason == "length" && depth < MAX_LENGTH_SPLIT_DEPTH && chunk.length >= MIN_SPLIT_CHARS) {
            val (first, second) = splitInHalf(chunk)
            if (first.isNotBlank() && second.isNotBlank()) {
                return translateChunk(first, aliases, depth + 1) + "\n\n" +
                        translateChunk(second, aliases, depth + 1)
            }
        }
        return cleanModelOutput(result.content)
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
        val messages = buildList {
            if (!system.isNullOrBlank()) add(mapOf("role" to "system", "content" to system))
            add(mapOf("role" to "user", "content" to user))
        }
        val body = buildMap<String, Any> {
            put("model", model)
            put("messages", messages)
            if (sendTemperature) put("temperature", temperature)
            if (maxTokens != null) put("max_tokens", maxTokens)
            for ((key, value) in extraBody) if (value == null) remove(key) else put(key, value)
        }
        return Request.Builder()
            .url(endpoint)
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
            .header("Content-Type", "application/json")
            .post(mapper.writeValueAsString(body).toRequestBody(JSON))
            .build()
    }

    private suspend fun executeOnce(system: String?, user: String, temperature: Double): ChatResult {
        val response = client.newCall(buildRequest(system, user, temperature)).await()
        val (code, body, retryAfter) = response.use {
            Triple(it.code, it.body?.string().orEmpty(), it.header("Retry-After"))
        }
        if (code in 200..299) return parseChatResponse(body)

        val message = errorMessage(body) ?: "HTTP $code"
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

    private fun parseChatResponse(json: String): ChatResult {
        return try {
            val choice = mapper.readTree(json).path("choices").path(0)
            ChatResult(
                content = choice.path("message").path("content").asText(""),
                finishReason = choice.path("finish_reason").takeIf { !it.isMissingNode && !it.isNull }?.asText()
            )
        } catch (e: Exception) {
            throw TranslationApiException("Failed to parse API response: ${e.message}")
        }
    }

    private fun parseAliases(aliasesJson: String): List<String> {
        return try {
            mapper.readValue(aliasesJson, List::class.java).filterIsInstance<String>()
        } catch (e: Exception) {
            emptyList()
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
