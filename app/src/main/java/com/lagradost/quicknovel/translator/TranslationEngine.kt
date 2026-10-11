package com.lagradost.quicknovel.translator

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.quicknovel.translator.db.GlossaryTerm
import com.lagradost.quicknovel.translator.db.PendingTerm
import com.lagradost.quicknovel.translator.db.TranslatedChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class TranslationResult(
    val englishText: String,
    val pendingTerms: List<PendingTerm>,
    val chunkCount: Int
)

class TranslationApiException(message: String) : Exception(message)

class TranslationEngine(private val apiKey: String, private val model: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mapper = jacksonObjectMapper()

    // Matches [NEW: 中文 → suggested] patterns from model output
    private val newTermPattern = Regex("""\[NEW:\s*(.+?)\s*→\s*(.+?)\]""")

    // Chinese sentence-ending punctuation for chunk splitting
    private val chineseSentenceEnd = Regex("""[。！？；…]""")

    companion object {
        private const val CHUNK_SIZE = 2000
        private const val API_URL = "https://api.openai.com/v1/chat/completions"
    }

    suspend fun translateChapter(
        chineseText: String,
        novelId: String,
        chapterNum: Int,
        glossaryTerms: List<GlossaryTerm>,
        onProgress: (Float) -> Unit
    ): TranslationResult = withContext(Dispatchers.IO) {
        val chunks = splitIntoChunks(chineseText)
        val translatedParts = mutableListOf<String>()
        val allPendingTerms = mutableListOf<PendingTerm>()

        chunks.forEachIndexed { index, chunk ->
            onProgress(index.toFloat() / chunks.size)

            val relevantTerms = glossaryTerms.filter { term ->
                chunk.contains(term.chinese) || parseAliases(term.aliases).any { alias ->
                    chunk.contains(alias)
                }
            }

            val prompt = PromptTemplates.buildUserPrompt(chunk, relevantTerms)
            val rawTranslation = callOpenAI(prompt)

            // Extract new terms
            val pendingInChunk = newTermPattern.findAll(rawTranslation).map { match ->
                PendingTerm(
                    novelId = novelId,
                    chinese = match.groupValues[1].trim(),
                    suggestedEnglish = match.groupValues[2].trim(),
                    chapterNum = chapterNum,
                    context = chunk.take(200)
                )
            }.toList()
            allPendingTerms.addAll(pendingInChunk)

            // Clean the [NEW: ...] markers, keep just the English translation
            val cleanedTranslation = rawTranslation.replace(newTermPattern) { matchResult ->
                matchResult.groupValues[2].trim()
            }

            translatedParts.add(cleanedTranslation.trim())
        }

        onProgress(1f)

        // Regex to strip "End of Chapter" boilerplate lines that Chinese novel sites append.
        val endOfChapterRegex = Regex(
            """^\(?(\*\s*)?([Ee]nd\s+[Oo]f\s+[Cc]hapter|[Cc]hapter\s+\d*\s+[Ee]nd)\s*(\d+)?\s*(\*\s*)?\)?$""",
            RegexOption.IGNORE_CASE
        )

        // Wrap each paragraph in <p> tags so the HTML rendering pipeline
        // (Jsoup → Markwon) preserves paragraph spacing rather than collapsing newlines.
        val htmlText = translatedParts
            .joinToString("\n\n")
            .split("\n\n")
            .filter { it.isNotBlank() && !endOfChapterRegex.matches(it.trim()) }
            .joinToString("\n") { "<p>${it.trim()}</p>" }

        TranslationResult(
            englishText = htmlText,
            pendingTerms = allPendingTerms,
            chunkCount = chunks.size
        )
    }

    private fun splitIntoChunks(text: String): List<String> {
        val paragraphs = text.split("\n\n")
        val chunks = mutableListOf<String>()
        val currentChunk = StringBuilder()

        for (paragraph in paragraphs) {
            if (paragraph.length > CHUNK_SIZE) {
                // Flush current chunk first
                if (currentChunk.isNotEmpty()) {
                    chunks.add(currentChunk.toString().trim())
                    currentChunk.clear()
                }
                // Split long paragraph at sentence boundaries
                splitLongParagraph(paragraph, chunks)
            } else if (currentChunk.length + paragraph.length + 2 > CHUNK_SIZE) {
                chunks.add(currentChunk.toString().trim())
                currentChunk.clear()
                currentChunk.append(paragraph)
            } else {
                if (currentChunk.isNotEmpty()) currentChunk.append("\n\n")
                currentChunk.append(paragraph)
            }
        }

        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toString().trim())
        }

        return chunks.filter { it.isNotBlank() }
    }

    private fun splitLongParagraph(paragraph: String, output: MutableList<String>) {
        val current = StringBuilder()
        var lastSentenceEnd = 0

        for (i in paragraph.indices) {
            current.append(paragraph[i])
            if (chineseSentenceEnd.containsMatchIn(paragraph[i].toString())) {
                lastSentenceEnd = current.length
                if (current.length >= CHUNK_SIZE) {
                    output.add(current.substring(0, lastSentenceEnd).trim())
                    val remaining = current.substring(lastSentenceEnd)
                    current.clear()
                    current.append(remaining)
                    lastSentenceEnd = 0
                }
            }
        }

        if (current.isNotEmpty()) {
            output.add(current.toString().trim())
        }
    }

    private suspend fun callOpenAI(userPrompt: String): String {
        var retryDelay = 1000L
        repeat(3) { attempt ->
            val requestBody = buildRequestBody(userPrompt)
            val request = Request.Builder()
                .url(API_URL)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            when (response.code) {
                200 -> {
                    return parseOpenAIResponse(responseBody)
                }
                401 -> {
                    throw TranslationApiException("Invalid API key. Please check your OpenAI API key in Settings.")
                }
                429 -> {
                    if (attempt < 2) {
                        delay(retryDelay)
                        retryDelay *= 2
                    } else {
                        throw TranslationApiException("Rate limit exceeded. Please try again later.")
                    }
                }
                else -> {
                    val errorMsg = try {
                        val errorJson = mapper.readTree(responseBody)
                        errorJson.path("error").path("message").asText("Unknown error")
                    } catch (e: Exception) {
                        "HTTP ${response.code}"
                    }
                    throw TranslationApiException("OpenAI API error: $errorMsg")
                }
            }
        }
        throw TranslationApiException("Failed after 3 retries")
    }

    private fun buildRequestBody(userPrompt: String): String {
        val messages = listOf(
            mapOf("role" to "system", "content" to PromptTemplates.SYSTEM_PROMPT),
            mapOf("role" to "user", "content" to userPrompt)
        )
        val body = mapOf(
            "model" to model,
            "messages" to messages,
            "temperature" to 0.3
        )
        return mapper.writeValueAsString(body)
    }

    private fun parseOpenAIResponse(json: String): String {
        return try {
            val root = mapper.readTree(json)
            root.path("choices")
                .path(0)
                .path("message")
                .path("content")
                .asText("")
        } catch (e: Exception) {
            throw TranslationApiException("Failed to parse API response: ${e.message}")
        }
    }

    private fun parseAliases(aliasesJson: String): List<String> {
        return try {
            mapper.readValue(aliasesJson, List::class.java)
                .filterIsInstance<String>()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Make a minimal test call to verify the API key is valid.
     */
    suspend fun testApiKey(): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = mapOf(
                "model" to model,
                "messages" to listOf(mapOf("role" to "user", "content" to "Hi")),
                "max_tokens" to 5
            )
            val request = Request.Builder()
                .url(API_URL)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(mapper.writeValueAsString(body).toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            response.code == 200
        } catch (e: Exception) {
            false
        }
    }
}
