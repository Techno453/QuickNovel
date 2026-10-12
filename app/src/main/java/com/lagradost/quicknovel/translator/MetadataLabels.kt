package com.lagradost.quicknovel.translator

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Short labels on the result page of a zh / ja / ko novel: the "Latest:" chapter name and the tag
 * chips. They go through the same batched, cached title path as listing titles
 * ([MetadataTranslator.title]): every label of a page lands in the same 150 ms batch, so a page
 * costs one small AI call (or the free Google fallback), and each label is cached forever in
 * `novel_metadata_translations` under a [labelId] that cannot collide with a novel id.
 */
object MetadataLabels {
    /** At most this many labels per page (tags beyond it stay untranslated). */
    const val MAX_LABELS = 30

    /** Longer labels are not sent (a "latest chapter" line is never this long). */
    const val MAX_LABEL_CHARS = 120

    /** Cache key of a label; novel ids are `apiName/name`, so the `label:` prefix keeps them apart. */
    fun labelId(lang: String, text: String): String = "label:$lang/${text.trim()}"

    private fun isCjk(c: Char): Boolean {
        val block = Character.UnicodeBlock.of(c) ?: return false
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                block == Character.UnicodeBlock.HIRAGANA ||
                block == Character.UnicodeBlock.KATAKANA ||
                block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS ||
                block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
                block == Character.UnicodeBlock.HANGUL_JAMO ||
                block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
    }

    /**
     * Whether [text] is worth translating: it has CJK letters and is not mostly Latin already
     * (provider genre labels such as "Fantasy (판타지)" are English with the original in brackets).
     */
    fun needsTranslation(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_LABEL_CHARS) return false
        var cjk = 0
        var latin = 0
        for (c in trimmed) {
            if (isCjk(c)) cjk++ else if (c in 'a'..'z' || c in 'A'..'Z') latin++
        }
        return cjk > 0 && latin < cjk
    }

    /**
     * The labels of one page to translate, in order: the latest chapter name first, then the tags.
     * Trimmed, de-duplicated, only those that [needsTranslation], at most [MAX_LABELS].
     */
    fun requests(lang: String?, latest: String?, tags: List<String>?): List<MetadataTranslator.TitleRequest> {
        if (lang == null) return emptyList()
        val texts = LinkedHashSet<String>()
        latest?.trim()?.takeIf { needsTranslation(it) }?.let { texts += it }
        tags.orEmpty().forEach { tag -> tag.trim().takeIf { needsTranslation(it) }?.let { texts += it } }
        return texts.take(MAX_LABELS).map { MetadataTranslator.TitleRequest(labelId(lang, it), lang, it) }
    }

    /** Translations already in memory (original → English), without suspending. */
    fun cached(lang: String?, latest: String?, tags: List<String>?): Map<String, String> =
        requests(lang, latest, tags).mapNotNull { r ->
            MetadataTranslations.translator.cachedTitle(r.novelId, r.original)?.let { r.original to it }
        }.toMap()

    /**
     * English versions of the page's labels (original → English; untranslatable or failed labels
     * are absent). The caller checks [MetadataTranslations.anyEnabled] first.
     */
    suspend fun translate(lang: String?, latest: String?, tags: List<String>?): Map<String, String> {
        val requests = requests(lang, latest, tags)
        if (requests.isEmpty()) return emptyMap()
        return translate(requests) { MetadataTranslations.translator.title(it) }
    }

    /**
     * Chapter names of a scrolling list ([ChapterNameTranslator]): same path and cache ids as the
     * labels, so the "Latest:" name and its row share one translation. Original → English.
     */
    suspend fun translateNames(lang: String, names: List<String>): Map<String, String> {
        val requests = names.map { it.trim() }.filter { needsTranslation(it) }.distinct()
            .map { MetadataTranslator.TitleRequest(labelId(lang, it), lang, it) }
        if (requests.isEmpty()) return emptyMap()
        return translate(requests) { MetadataTranslations.translator.title(it) }
    }

    /** A chapter name's translation if it is in memory, without suspending. */
    fun cachedName(lang: String, name: String): String? =
        name.trim().takeIf { needsTranslation(it) }?.let { MetadataTranslations.translator.cachedTitle(labelId(lang, it), it) }

    /** All [requests] at once, so they share one batch of [title]. */
    suspend fun translate(
        requests: List<MetadataTranslator.TitleRequest>,
        title: suspend (MetadataTranslator.TitleRequest) -> String?,
    ): Map<String, String> = coroutineScope {
        requests.map { r -> async { r.original to title(r) } }.awaitAll()
            .mapNotNull { (original, english) -> english?.let { original to it } }
            .toMap()
    }
}
