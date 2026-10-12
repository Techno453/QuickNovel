package com.lagradost.quicknovel.translator

import com.lagradost.quicknovel.translator.db.GlossaryTerm

/**
 * Deterministic clean-up of an "Analyze novel" extraction before it is saved. The analysis
 * prompt asks for proper nouns and setting-specific terms only; this is the safety net for what
 * models still return (see docs/translation-pipeline.md). In order:
 *
 * 1. Protected terms: an entry whose source (or alias) is already in the Story Bible passes
 *    through untouched and is never merged (locked and existing terms are never dropped here).
 * 2. [Reason.NOT_PROPER]: the model itself marked the entry `"proper": false`.
 * 3. [Reason.GENERIC_ROLE]: an unnamed role: the source is one of the few role words in
 *    [GENERIC_ROLES] or a demonstrative/descriptive phrase ([GENERIC_ROLE_PATTERNS]: 那个…,
 *    先輩の…, 그 …) in any category, or a "character" whose English is not a name
 *    ("the minister", "examiner": an article or no capital letter).
 * 4. [Reason.COMMON_NOUN]: a non-character entry with a very short source (1–2 Han/kana
 *    characters or Hangul syllables) that does not look like a proper noun ([looksProper]) and
 *    has low confidence (< [SHORT_TERM_MIN_CONFIDENCE]) or an all-lowercase English rendering
 *    (毒 "poison", 麦畑 "wheat fields", 신 "god", 低级 "low level"); or any non-character entry
 *    the model rendered as one lowercase English word ([isPlainWord]: 두루마리 "scroll",
 *    비둘기 "pigeon", 환생 "reincarnation").
 * 5. [Reason.AUTHOR_NOTE]: the source occurs only inside author's-note blocks of the excerpt
 *    ([AuthorNotes]), e.g. the pen name in "土豆求月票".
 * 6. [Reason.MERGED]: entries with the same source are merged (highest confidence wins), then
 *    entries of the same category with the same English rendering (case, whitespace, hyphens
 *    and a leading "the" ignored) are merged into one entry whose aliases hold the other forms.
 *
 * Confidence keeps its meaning (the model's own value; a merged entry takes the maximum).
 */
object AnalysisFilter {
    enum class Reason { NOT_PROPER, GENERIC_ROLE, COMMON_NOUN, AUTHOR_NOTE, MERGED }

    /** A removed entry; [into] is the source of the entry it was merged into ([Reason.MERGED]). */
    data class Dropped(val term: DiscoveredTerm, val reason: Reason, val into: String? = null)

    data class Result(val terms: List<DiscoveredTerm>, val dropped: List<Dropped>) {
        fun droppedFor(reason: Reason): List<DiscoveredTerm> = dropped.filter { it.reason == reason }.map { it.term }
    }

    private const val PARAGRAPH_BREAK = "\n\n"

    /** Below this confidence a very short non-character term is treated as a common noun. */
    const val SHORT_TERM_MIN_CONFIDENCE = 0.8f

    /**
     * Unnamed roles and descriptions that models list as "characters". Deliberately small: the
     * prompt does most of the work. Language-independent (the scripts barely overlap).
     */
    val GENERIC_ROLES: Set<String> = setOf(
        // Chinese
        "中年男子", "中年人", "中年男人", "中年女子", "中年妇人", "老者", "老人", "老头", "少年", "少女", "青年",
        "男子", "女子", "男人", "女人", "测验人", "测验员", "黑衣人", "刺客", "大臣", "侍女", "护卫", "掌柜",
        "店小二", "小二", "众人",
        // Japanese
        "暗殺者", "黒幕", "中年男性", "中年の男", "老人", "男", "女", "侍女", "近衛侍女", "産婆", "医者",
        // Korean
        "그 여자", "그 남자", "그녀", "그년", "그놈", "남자", "여자", "소년", "소녀", "노인", "암살자", "청년", "사내",
    )

    /** Demonstrative or descriptive phrases ("that man", "my senior's …") that are not names. */
    val GENERIC_ROLE_PATTERNS: List<Regex> = listOf(
        Regex("""^(?:那个|这个|那位|这位|一个|一位|某个|某位)"""),
        Regex("""^(?:その|あの|この|例の|先輩の|後輩の)"""),
        Regex("""^(?:그|저|이)\s+\S"""),
    )

    private val ARTICLE = Regex("""^(?:the|a|an)\s+""", RegexOption.IGNORE_CASE)

    private val HAN_OR_KANA = Regex("""[\p{IsHan}\p{IsHiragana}\p{IsKatakana}]""")
    private val HANGUL_SYLLABLE = Regex("""[가-힣]""")
    private val KATAKANA_ONLY = Regex("""^[\p{IsKatakana}ー・=＝]+$""")

    /** Place / organisation suffixes that make even a two-character Chinese or Japanese source a proper noun. */
    private const val PROPER_SUFFIXES = "城国宗门門派山谷州府村镇鎮殿阁閣帮幫家族界域洲岛島峰湖海河江領町"

    /** True if [source] is one of the [GENERIC_ROLES] or matches a [GENERIC_ROLE_PATTERNS] phrase. */
    fun isGenericRoleSource(source: String): Boolean {
        val s = source.trim()
        return s in GENERIC_ROLES || GENERIC_ROLE_PATTERNS.any { it.containsMatchIn(s) }
    }

    /** True if a "character" entry is an unnamed role or description rather than a name. */
    fun isGenericRole(source: String, english: String): Boolean {
        if (isGenericRoleSource(source)) return true
        val e = english.trim()
        if (e.isEmpty()) return false
        // Names are capitalised and have no article: "the minister", "examiner", "that woman" are roles.
        return ARTICLE.containsMatchIn(e) || e == e.lowercase()
    }

    /**
     * 1–2 Han/kana characters (zh/ja) or 1–2 Hangul syllables (ko), nothing else. (Two Hangul
     * syllables carry far less than two Han characters: 차원 "dimension", 식칼 "kitchen knife".)
     */
    fun isVeryShort(source: String): Boolean {
        val s = source.trim()
        if (s.isEmpty()) return false
        val hangul = HANGUL_SYLLABLE.findAll(s).count()
        if (hangul > 0) return hangul in 1..2 && hangul == s.length
        val cjk = HAN_OR_KANA.findAll(s).count()
        return cjk in 1..2 && cjk == s.length
    }

    private val SINGLE_LOWERCASE_WORD = Regex("""^[a-z]+(?:-[a-z]+)*$""")

    /**
     * A single lowercase English word ("scroll", "pigeon", "reincarnation", "dojo"): the model
     * itself rendered the term as an ordinary dictionary word, not a name or coined term.
     */
    fun isPlainWord(english: String): Boolean = SINGLE_LOWERCASE_WORD.matches(english.trim())

    /** Short sources that are still proper nouns: katakana names, place / sect / family suffixes. */
    fun looksProper(source: String): Boolean {
        val s = source.trim()
        if (s.length >= 2 && KATAKANA_ONLY.matches(s)) return true
        return s.length >= 2 && s.last() in PROPER_SUFFIXES
    }

    private fun isAllLowercase(english: String): Boolean {
        val letters = english.filter { it.isLetter() }
        return letters.isNotEmpty() && letters == letters.lowercase()
    }

    /** Key for "same English rendering": case, whitespace, hyphens/apostrophes and a leading article ignored. */
    fun englishKey(english: String): String =
        english.trim().replace(ARTICLE, "").lowercase()
            .replace(Regex("""[\s\-‐–_]+"""), " ")
            .replace(Regex("""['’`]"""), "")
            .trim()

    /**
     * @param chapterTexts the raw plain text of each chapter in the excerpt (with author's notes,
     *   if any); only used for the author's-note check, which is done per chapter because a note
     *   block runs to the end of its chapter
     * @param protectedSources every source form already in the Story Bible
     */
    fun filter(
        terms: List<DiscoveredTerm>,
        chapterTexts: List<String>,
        protectedSources: Set<String> = emptySet(),
    ): Result {
        val dropped = mutableListOf<Dropped>()
        val kept = mutableListOf<DiscoveredTerm>()
        val protectedTerms = mutableListOf<DiscoveredTerm>()
        val splits = chapterTexts.map { AuthorNotes.split(it) }
        val noteText = splits.map { it.notes }.filter { it.isNotEmpty() }.joinToString(PARAGRAPH_BREAK)
        val storyText = splits.joinToString(PARAGRAPH_BREAK) { it.story }

        for (raw in terms) {
            val t = raw.copy(source = raw.source.trim(), english = raw.english.trim())
            if (t.source in protectedSources || t.aliases.any { it.trim() in protectedSources }) {
                protectedTerms += t
                continue
            }
            val category = GlossaryTerm.normalizeCategory(t.category)
            val reason = when {
                t.proper == false -> Reason.NOT_PROPER
                isGenericRoleSource(t.source) -> Reason.GENERIC_ROLE
                category == GlossaryTerm.CATEGORY_CHARACTER && isGenericRole(t.source, t.english) -> Reason.GENERIC_ROLE
                category != GlossaryTerm.CATEGORY_CHARACTER && isVeryShort(t.source) && !looksProper(t.source) &&
                    (t.confidence < SHORT_TERM_MIN_CONFIDENCE || isAllLowercase(t.english)) -> Reason.COMMON_NOUN
                category != GlossaryTerm.CATEGORY_CHARACTER && isPlainWord(t.english) -> Reason.COMMON_NOUN
                noteText.isNotEmpty() && isOnlyInNotes(t, noteText, storyText) -> Reason.AUTHOR_NOTE
                else -> null
            }
            if (reason != null) dropped += Dropped(raw, reason) else kept += t
        }

        val merged = mergeDuplicates(kept, dropped)
        return Result(protectedTerms + merged, dropped)
    }

    /** [filter] for a single-chapter (or already joined) excerpt. */
    fun filter(terms: List<DiscoveredTerm>, excerpt: String, protectedSources: Set<String> = emptySet()): Result =
        filter(terms, listOf(excerpt), protectedSources)

    private fun isOnlyInNotes(t: DiscoveredTerm, noteText: String, storyText: String): Boolean {
        val forms = (listOf(t.source) + t.aliases).map { it.trim() }.filter { it.isNotEmpty() }
        return forms.any { noteText.contains(it) } && forms.none { storyText.contains(it) }
    }

    private fun mergeDuplicates(terms: List<DiscoveredTerm>, dropped: MutableList<Dropped>): List<DiscoveredTerm> {
        // 1. Same source: keep the most confident entry, fill in what it lacks from the others.
        val bySource = LinkedHashMap<String, DiscoveredTerm>()
        for (t in terms) {
            val current = bySource[t.source]
            if (current == null) {
                bySource[t.source] = t
                continue
            }
            val (winner, loser) = if (t.confidence > current.confidence) t to current else current to t
            bySource[t.source] = absorb(winner, loser, addSourceAsAlias = false)
            dropped += Dropped(loser, Reason.MERGED, into = winner.source)
        }

        // 2. Same category and English rendering: one entry, the other sources become aliases.
        val groups = LinkedHashMap<Pair<String, String>, MutableList<DiscoveredTerm>>()
        for (t in bySource.values) {
            val key = GlossaryTerm.normalizeCategory(t.category) to englishKey(t.english)
            groups.getOrPut(key) { mutableListOf() } += t
        }
        return groups.values.map { group ->
            if (group.size == 1) return@map group.single()
            // Most confident first; ties go to the longer (fuller) source form, e.g. a full name over a nickname.
            val ordered = group.sortedWith(compareByDescending<DiscoveredTerm> { it.confidence }.thenByDescending { it.source.length })
            var primary = ordered.first()
            for (other in ordered.drop(1)) {
                primary = absorb(primary, other, addSourceAsAlias = true)
                dropped += Dropped(other, Reason.MERGED, into = primary.source)
            }
            primary
        }
    }

    /** [primary] plus [other]'s aliases (and source), gender when unknown, notes when blank, max confidence. */
    private fun absorb(primary: DiscoveredTerm, other: DiscoveredTerm, addSourceAsAlias: Boolean): DiscoveredTerm {
        val aliases = (primary.aliases + (if (addSourceAsAlias) listOf(other.source) else emptyList()) + other.aliases)
            .map { it.trim() }.filter { it.isNotEmpty() && it != primary.source }.distinct()
        val unknown = GlossaryTerm.normalizeGender(primary.gender) == GlossaryTerm.GENDER_UNKNOWN
        return primary.copy(
            aliases = aliases,
            gender = if (unknown && GlossaryTerm.normalizeGender(other.gender) != GlossaryTerm.GENDER_UNKNOWN) other.gender else primary.gender,
            notes = primary.notes.ifBlank { other.notes },
            confidence = maxOf(primary.confidence, other.confidence),
            proper = primary.proper ?: other.proper,
        )
    }
}
