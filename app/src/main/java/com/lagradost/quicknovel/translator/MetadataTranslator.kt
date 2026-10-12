package com.lagradost.quicknovel.translator

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.quicknovel.translator.db.NovelMetadataTranslation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Which engine produced a cached title or synopsis translation (`*_engine` columns). */
enum class MetadataEngine(val id: String) {
    AI("ai"),
    GOOGLE("google");

    companion object {
        fun fromId(id: String?): MetadataEngine? = entries.firstOrNull { it.id == id }
    }
}

/**
 * What may translate metadata right now. [ai]: the AI setting is on, an API key is set and the
 * monthly cap is not reached. [google]: the free Google path may be used (the "Google-translate
 * titles" option, or as the fallback when an AI call fails).
 */
data class MetadataPolicy(val ai: Boolean, val google: Boolean) {
    val any: Boolean get() = ai || google

    companion object {
        val NONE = MetadataPolicy(ai = false, google = false)
    }
}

/** Persistent cache (the translator database in the app, a map in tests). */
interface MetadataStore {
    suspend fun load(ids: List<String>): List<NovelMetadataTranslation>
    suspend fun save(rows: List<NovelMetadataTranslation>)
}

/** The engines. AI calls throw on failure; [google] returns null on failure. */
interface MetadataBackend {
    suspend fun policy(): MetadataPolicy

    /** Model id recorded with AI translations. */
    fun aiModel(): String

    /** One call for all [titles]; same size, null where the model gave nothing usable. */
    suspend fun aiTitles(lang: String, titles: List<String>): List<String?>

    /** One call for a novel's title and synopsis. */
    suspend fun aiNovel(lang: String, novelId: String, title: String, synopsis: String): Pair<String?, String?>

    suspend fun google(lang: String, text: String): String?
}

/**
 * Translates novel titles and synopses for listings, the preview sheet and the result page, with
 * a permanent cache (docs/browse.md):
 *
 * - [title]: callers ask for one title each (a poster becoming visible); requests are collected
 *   for [batchWindowMs] per source language and sent as one AI call of at most [maxBatch] titles,
 *   so a page of posters is one request. Concurrent requests for the same title share one result.
 * - [novel]: title + synopsis of one novel in one AI call (preview sheet, result page).
 * - Rows are re-translated only when the hash of the original text changes, or once per session
 *   to replace a free Google translation by an AI one when AI becomes available.
 * - AI failures fall back to Google; a text that failed with every engine is not retried in this
 *   session. Nothing here throws (except cancellation of the caller).
 */
class MetadataTranslator(
    private val store: MetadataStore,
    private val backend: MetadataBackend,
    private val scope: CoroutineScope,
    private val batchWindowMs: Long = DEFAULT_BATCH_WINDOW_MS,
    private val maxBatch: Int = MAX_TITLES_PER_CALL,
    private val now: () -> Long = System::currentTimeMillis,
    private val hash: (String) -> String = SourceHash::of,
) {
    data class TitleRequest(val novelId: String, val lang: String, val original: String)

    data class Translated(val title: String?, val synopsis: String?)

    private val memory = ConcurrentHashMap<String, NovelMetadataTranslation>()
    private val hashes = ConcurrentHashMap<String, String>()
    private val upgradeTried: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val failed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val lock = Mutex()
    private val pending = LinkedHashMap<String, MutableList<Pair<TitleRequest, CompletableDeferred<String?>>>>()
    private val inFlight = HashMap<String, CompletableDeferred<String?>>()
    private val flushJobs = HashMap<String, Job>()

    /** Languages whose flush job is still in its [batchWindowMs] delay (guarded by [lock]). */
    private val windowWaiting = HashSet<String>()
    private val novelLocks = ConcurrentHashMap<String, Mutex>()

    /** Number of AI title calls made (for tests and the docs' "one call per page" claim). */
    @Volatile
    var aiTitleCalls = 0
        private set

    private fun hashOf(text: String): String = hashes.getOrPut(text) { hash(text) }

    /** The cached translation of [original] if it is in memory, without suspending. */
    fun cachedTitle(novelId: String, original: String): String? =
        memory[novelId]?.takeIf { it.titleHash == hashOf(original) }?.translatedTitle

    /** The cached synopsis translation if it is in memory, without suspending. */
    fun cachedSynopsis(novelId: String, original: String): String? =
        memory[novelId]?.takeIf { it.synopsisHash == hashOf(original) }?.translatedSynopsis

    /** English title for [request], from the cache or a batched call; null if unavailable. */
    suspend fun title(request: TitleRequest): String? {
        if (request.original.isBlank()) return null
        cachedTitle(request.novelId, request.original)?.let { cached ->
            val row = memory[request.novelId]
            if (row == null || !wantsUpgrade(row.titleEngine, "t:" + request.novelId, peekOnly = true)) return cached
        }
        val key = request.novelId + "|" + hashOf(request.original)
        val deferred = lock.withLock {
            inFlight[key]?.let { return@withLock it }
            val created = CompletableDeferred<String?>()
            inFlight[key] = created
            val queue = pending.getOrPut(request.lang) { mutableListOf() }
            queue += request to created
            if (queue.size >= maxBatch) {
                // A full batch skips the rest of the window. Only a flush still waiting out the
                // window is replaced: cancelling a running one would cancel the batch in flight
                // (its callers would get nothing); a running flush takes the queue next anyway.
                if (flushJobs[request.lang] == null || request.lang in windowWaiting) {
                    windowWaiting -= request.lang
                    flushJobs.remove(request.lang)?.cancel()
                    flushJobs[request.lang] = scope.launch { flush(request.lang) }
                }
            } else if (flushJobs[request.lang] == null) {
                windowWaiting += request.lang
                flushJobs[request.lang] = scope.launch {
                    delay(batchWindowMs)
                    lock.withLock { windowWaiting -= request.lang }
                    flush(request.lang)
                }
            }
            created
        }
        return deferred.await()
    }

    private suspend fun flush(lang: String) {
        while (true) {
            val batch = lock.withLock {
                val queue = pending[lang]
                if (queue.isNullOrEmpty()) {
                    pending.remove(lang)
                    flushJobs.remove(lang)
                    return
                }
                val take = queue.take(maxBatch)
                repeat(take.size) { queue.removeAt(0) }
                take
            }
            val results = try {
                translateTitles(lang, batch.map { it.first })
            } catch (e: CancellationException) {
                batch.forEach { it.second.cancel() }
                throw e
            } catch (e: Throwable) {
                emptyMap()
            }
            lock.withLock {
                batch.forEach { (request, deferred) ->
                    inFlight.remove(request.novelId + "|" + hashOf(request.original))
                    deferred.complete(results[request.novelId])
                }
            }
        }
    }

    /** One batch: cache lookup, one AI call for the misses, Google for what is still missing. */
    internal suspend fun translateTitles(lang: String, requests: List<TitleRequest>): Map<String, String> {
        val unique = requests.distinctBy { it.novelId }
        val out = HashMap<String, String>()
        val stored = loadRows(unique.map { it.novelId })
        val policy = backend.policy()
        val todo = ArrayList<TitleRequest>()
        for (request in unique) {
            val row = stored[request.novelId]
            if (row != null && row.titleHash == hashOf(request.original)) {
                out[request.novelId] = row.translatedTitle
                val upgradeKey = "t:" + request.novelId
                if (!policy.ai) {
                    // No AI this session: serve this row from memory from now on.
                    upgradeTried += upgradeKey
                    continue
                }
                if (!wantsUpgrade(row.titleEngine, upgradeKey)) continue
            }
            if (request.novelId + "|" + hashOf(request.original) in failed && !out.containsKey(request.novelId)) continue
            todo += request
        }
        if (todo.isEmpty() || !policy.any) return out

        val fresh = HashMap<String, Pair<String, MetadataEngine>>()
        if (policy.ai) {
            for (chunk in todo.chunked(maxBatch)) {
                val translated = try {
                    aiTitleCalls++
                    backend.aiTitles(lang, chunk.map { it.original })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    emptyList()
                }
                chunk.forEachIndexed { i, request ->
                    usable(translated.getOrNull(i), request.original)?.let { fresh[request.novelId] = it to MetadataEngine.AI }
                }
            }
        }
        // The free endpoint takes one text per request: a few at a time, not one after the other.
        val googleTodo = todo.filter { !fresh.containsKey(it.novelId) && !out.containsKey(it.novelId) }
        val googled = coroutineScope {
            val gate = Semaphore(GOOGLE_PARALLEL)
            googleTodo.map { request ->
                async { gate.withPermit { usable(safeGoogle(lang, request.original), request.original) } }
            }.awaitAll()
        }
        googleTodo.forEachIndexed { i, request ->
            val google = googled[i]
            if (google != null) fresh[request.novelId] = google to MetadataEngine.GOOGLE
            else failed += request.novelId + "|" + hashOf(request.original)
        }
        if (fresh.isEmpty()) return out

        val model = backend.aiModel()
        val rows = todo.mapNotNull { request ->
            val (text, engine) = fresh[request.novelId] ?: return@mapNotNull null
            out[request.novelId] = text
            val previous = stored[request.novelId]
            (previous ?: NovelMetadataTranslation(
                novelId = request.novelId, sourceLang = lang, titleHash = "", translatedTitle = "",
                titleEngine = engine.id, updatedAt = 0,
            )).copy(
                sourceLang = lang,
                titleHash = hashOf(request.original),
                translatedTitle = text,
                titleEngine = engine.id,
                model = if (engine == MetadataEngine.AI) model else previous?.model,
                updatedAt = now(),
            )
        }
        saveRows(rows)
        return out
    }

    /**
     * English title and synopsis of one novel (preview sheet, result page). Missing parts are
     * translated together in one AI call; concurrent calls for the same novel share the work.
     */
    suspend fun novel(novelId: String, lang: String, title: String, synopsis: String?): Translated {
        val mutex = novelLocks.getOrPut(novelId) { Mutex() }
        return mutex.withLock { translateNovel(novelId, lang, title, synopsis?.takeIf { it.isNotBlank() }) }
    }

    private suspend fun translateNovel(novelId: String, lang: String, title: String, synopsis: String?): Translated {
        val row = memory[novelId] ?: loadRows(listOf(novelId))[novelId]
        val titleHash = hashOf(title)
        val synopsisHash = synopsis?.let { hashOf(it) }
        var newTitle = row?.translatedTitle?.takeIf { row.titleHash == titleHash }
        var newSynopsis = row?.translatedSynopsis?.takeIf { synopsisHash != null && row.synopsisHash == synopsisHash }
        val policy = backend.policy()

        val titleUpgrade = newTitle != null && policy.ai && wantsUpgrade(row?.titleEngine, "t:$novelId")
        val synopsisUpgrade = newSynopsis != null && policy.ai && wantsUpgrade(row?.synopsisEngine, "s:$novelId")
        var needTitle = (newTitle == null && "$novelId|$titleHash" !in failed) || titleUpgrade
        var needSynopsis = synopsis != null &&
                ((newSynopsis == null && "$novelId|$synopsisHash" !in failed) || synopsisUpgrade)
        if ((!needTitle && !needSynopsis) || !policy.any) return Translated(newTitle, newSynopsis)

        var titleEngine = row?.titleEngine?.takeIf { newTitle != null }
        var synopsisEngine = row?.synopsisEngine?.takeIf { newSynopsis != null }
        var usedAi = false
        if (policy.ai) {
            val (t, s) = try {
                backend.aiNovel(lang, novelId, title, synopsis.orEmpty())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                null to null
            }
            usable(t, title)?.let { if (needTitle) { newTitle = it; titleEngine = MetadataEngine.AI.id; needTitle = false; usedAi = true } }
            if (synopsis != null) usable(s, synopsis)?.let {
                if (needSynopsis) { newSynopsis = it; synopsisEngine = MetadataEngine.AI.id; needSynopsis = false; usedAi = true }
            }
        }
        if (needTitle && (newTitle == null)) {
            usable(safeGoogle(lang, title), title)?.let { newTitle = it; titleEngine = MetadataEngine.GOOGLE.id }
                ?: failed.add("$novelId|$titleHash")
        }
        if (needSynopsis && newSynopsis == null && synopsis != null) {
            usable(safeGoogle(lang, synopsis), synopsis)?.let { newSynopsis = it; synopsisEngine = MetadataEngine.GOOGLE.id }
                ?: failed.add("$novelId|$synopsisHash")
        }

        val finalTitle = newTitle
        if (finalTitle != null) {
            saveRows(
                listOf(
                    NovelMetadataTranslation(
                        novelId = novelId,
                        sourceLang = lang,
                        titleHash = titleHash,
                        translatedTitle = finalTitle,
                        titleEngine = titleEngine ?: MetadataEngine.GOOGLE.id,
                        synopsisHash = if (newSynopsis != null) synopsisHash else null,
                        translatedSynopsis = newSynopsis,
                        synopsisEngine = if (newSynopsis != null) synopsisEngine else null,
                        model = if (usedAi) backend.aiModel() else row?.model,
                        updatedAt = now(),
                    )
                )
            )
        }
        return Translated(newTitle, newSynopsis)
    }

    /**
     * Whether a cached [engine] translation should be redone with AI: Google rows are upgraded
     * once per session per novel ([key]). With [peekOnly] nothing is recorded.
     */
    private fun wantsUpgrade(engine: String?, key: String, peekOnly: Boolean = false): Boolean {
        if (MetadataEngine.fromId(engine) != MetadataEngine.GOOGLE) return false
        return if (peekOnly) key !in upgradeTried else upgradeTried.add(key)
    }

    private suspend fun loadRows(ids: List<String>): Map<String, NovelMetadataTranslation> {
        val missing = ids.filter { !memory.containsKey(it) }
        if (missing.isNotEmpty()) {
            val rows = try {
                store.load(missing)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                emptyList()
            }
            rows.forEach { memory[it.novelId] = it }
        }
        return ids.mapNotNull { id -> memory[id]?.let { id to it } }.toMap()
    }

    private suspend fun saveRows(rows: List<NovelMetadataTranslation>) {
        if (rows.isEmpty()) return
        rows.forEach { memory[it.novelId] = it }
        try {
            store.save(rows)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Kept in memory for this session; persisting is best effort.
        }
    }

    private suspend fun safeGoogle(lang: String, text: String): String? = try {
        backend.google(lang, text)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        null
    }

    /** A translation worth keeping: not blank and not just the original echoed back. */
    private fun usable(translated: String?, original: String): String? =
        translated?.trim()?.takeIf { it.isNotEmpty() && it != original.trim() }

    companion object {
        const val DEFAULT_BATCH_WINDOW_MS = 150L
        const val MAX_TITLES_PER_CALL = 20

        /** Free Google requests of one batch running at the same time. */
        const val GOOGLE_PARALLEL = 4
    }
}

/** Prompts and reply parsing for the AI metadata calls (pure, unit-tested). */
object MetadataPrompts {
    private val mapper = jacksonObjectMapper()

    /** Longest synopsis sent to the model (characters); web-novel blurbs are far shorter. */
    const val MAX_SYNOPSIS_CHARS = 4000

    fun titlesSystemPrompt(lang: String): String {
        val name = TranslationLanguages.displayName(lang)
        return "You translate the titles of $name web novels into natural English, the way an English " +
                "publisher would title the book. Romanize personal names, keep the meaning and tone, do not " +
                "add explanations. You receive a JSON array of titles. Reply with JSON only, exactly " +
                "{\"titles\": [...]} with one English title per input title, in the same order."
    }

    fun titlesUserPrompt(titles: List<String>): String = mapper.writeValueAsString(titles)

    fun novelSystemPrompt(lang: String): String {
        val name = TranslationLanguages.displayName(lang)
        return "You translate the title and the synopsis of a $name web novel into natural English for a " +
                "reader deciding whether to read it. Romanize personal names, keep paragraph breaks as \\n, " +
                "keep hashtags and warnings, do not add commentary. You receive JSON {\"title\", \"synopsis\"}. " +
                "Reply with JSON only, exactly {\"title\": \"...\", \"synopsis\": \"...\"}."
    }

    fun novelUserPrompt(title: String, synopsis: String): String =
        mapper.writeValueAsString(mapOf("title" to title, "synopsis" to synopsis.take(MAX_SYNOPSIS_CHARS)))

    /** The JSON object or array in [raw], tolerating code fences and text around it. */
    private fun jsonIn(raw: String): JsonNode? {
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val candidates = listOfNotNull(
            text,
            text.indexOf('{').takeIf { it >= 0 }?.let { s -> text.lastIndexOf('}').takeIf { it > s }?.let { text.substring(s, it + 1) } },
            text.indexOf('[').takeIf { it >= 0 }?.let { s -> text.lastIndexOf(']').takeIf { it > s }?.let { text.substring(s, it + 1) } },
        )
        for (candidate in candidates) {
            val node = runCatching { mapper.readTree(candidate) }.getOrNull()
            if (node != null && (node.isObject || node.isArray)) return node
        }
        return null
    }

    /** [expected] titles from a reply, or all null when the reply does not have exactly that many. */
    fun parseTitles(raw: String, expected: Int): List<String?> {
        val node = jsonIn(raw)
        val array = when {
            node == null -> null
            node.isArray -> node
            else -> node.path("titles").takeIf { it.isArray }
        }
        if (array == null || array.size() != expected) return List(expected) { null }
        return array.map { item -> item.takeIf { it.isTextual }?.asText()?.trim()?.ifEmpty { null } }
    }

    /** (title, synopsis) from a reply; null parts when missing. */
    fun parseNovel(raw: String): Pair<String?, String?> {
        val node = jsonIn(raw)?.takeIf { it.isObject } ?: return null to null
        fun field(name: String) = node.path(name).takeIf { it.isTextual }?.asText()?.trim()?.ifEmpty { null }
        return field("title") to field("synopsis")
    }
}
