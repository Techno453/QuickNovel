package com.lagradost.quicknovel.translator

import com.lagradost.quicknovel.imported.TxtChapterSplitter
import com.lagradost.quicknovel.translator.db.GlossaryTerm

/**
 * Deterministic clean-up of the terms a model reports, before they reach the Story Bible: an
 * "Analyze novel" extraction, and the `===TERMS===` tail of every translated passage. The
 * prompts ask for proper nouns and setting-specific terms only; this is the safety net for what
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
 *    비둘기 "pigeon", 환생 "reincarnation"); in translation ([From.TRANSLATION]) any
 *    non-character entry rendered all in lowercase ("fish knife", "city guards", "black bread"):
 *    the model itself treated it as ordinary English.
 * 5. [Reason.PHRASE]: a saying or a list rather than a term: the source has clause punctuation
 *    (印堂發黑，必有血光之災; 詭異，邪惡異形).
 * 6. [Reason.CHAPTER_TITLE]: the source is a chapter heading itself (第16章 好奇) or occurs only
 *    in the chapter heading lines of the excerpt (霧氣 from "第3章 霧氣").
 * 7. [Reason.AUTHOR_NOTE]: the source occurs only inside author's-note blocks of the excerpt
 *    ([AuthorNotes]), e.g. the pen name in "土豆求月票", a reader thanked for a reward.
 * 8. [Reason.MERGED]: entries with the same source are merged (highest confidence wins), then
 *    entries of the same category with the same English rendering (case, whitespace, hyphens
 *    and a leading "the" ignored) become one entry: the analysis keeps the other forms as
 *    aliases; translation drops them, because a form one passage used for an entity can be an
 *    ordinary word elsewhere (黃金 "Gold" for the 金之民 people).
 *
 * Confidence keeps its meaning (the model's own value; a merged entry takes the maximum).
 */
object AnalysisFilter {
    enum class Reason { NOT_PROPER, GENERIC_ROLE, COMMON_NOUN, PHRASE, CHAPTER_TITLE, AUTHOR_NOTE, MERGED }

    /**
     * Where the terms come from. [TRANSLATION] (a passage's `===TERMS===` tail) is stricter than
     * the [ANALYSIS] pre-pass, whose prompt sees whole chapters and asks for names only: it drops
     * all-lowercase renderings and same-English duplicates instead of merging them as aliases.
     */
    enum class From { ANALYSIS, TRANSLATION }

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

    /** Clause punctuation: a source containing it is a saying or a list, not a term. */
    private val CLAUSE_PUNCTUATION = Regex("""[，。！？；,!?;]""")

    /** True if [source] is a sentence, saying or list rather than a single term ([Reason.PHRASE]). */
    fun isPhrase(source: String): Boolean = CLAUSE_PUNCTUATION.containsMatchIn(source)

    /**
     * @param chapterTexts the raw plain text of each chapter in the excerpt (with author's notes,
     *   if any); used for the author's-note check, which is done per chapter because a note
     *   block runs to the end of its chapter, and for the chapter-title check
     * @param protectedSources every source form already in the Story Bible
     */
    fun filter(
        terms: List<DiscoveredTerm>,
        chapterTexts: List<String>,
        protectedSources: Set<String> = emptySet(),
        from: From = From.ANALYSIS,
    ): Result {
        val dropped = mutableListOf<Dropped>()
        val kept = mutableListOf<DiscoveredTerm>()
        val protectedTerms = mutableListOf<DiscoveredTerm>()
        val splits = chapterTexts.map { AuthorNotes.split(it) }
        val noteText = splits.map { it.notes }.filter { it.isNotEmpty() }.joinToString(PARAGRAPH_BREAK)
        val storyText = splits.joinToString(PARAGRAPH_BREAK) { it.story }
        val (headingText, bodyText) = storyText.split(PARAGRAPH_BREAK)
            .partition { TxtChapterSplitter.isHeading(it) }
            .let { (h, b) -> h.joinToString(PARAGRAPH_BREAK) to b.joinToString(PARAGRAPH_BREAK) }

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
                from == From.TRANSLATION && category != GlossaryTerm.CATEGORY_CHARACTER && isAllLowercase(t.english) ->
                    Reason.COMMON_NOUN
                isPhrase(t.source) -> Reason.PHRASE
                TxtChapterSplitter.isHeading(t.source) -> Reason.CHAPTER_TITLE
                headingText.isNotEmpty() && isOnlyIn(t, headingText, bodyText) -> Reason.CHAPTER_TITLE
                noteText.isNotEmpty() && isOnlyIn(t, noteText, storyText) -> Reason.AUTHOR_NOTE
                else -> null
            }
            if (reason != null) dropped += Dropped(raw, reason) else kept += t
        }

        val merged = mergeDuplicates(kept, dropped, sameEnglishAsAlias = from == From.ANALYSIS)
        return Result(protectedTerms + merged, dropped)
    }

    /** [filter] for a single-chapter (or already joined) excerpt. */
    fun filter(
        terms: List<DiscoveredTerm>,
        excerpt: String,
        protectedSources: Set<String> = emptySet(),
        from: From = From.ANALYSIS,
    ): Result = filter(terms, listOf(excerpt), protectedSources, from)

    /** True if some form of [t] occurs in [inText] and none occurs in [elsewhere]. */
    private fun isOnlyIn(t: DiscoveredTerm, inText: String, elsewhere: String): Boolean {
        val forms = (listOf(t.source) + t.aliases).map { it.trim() }.filter { it.isNotEmpty() }
        return forms.any { inText.contains(it) } && forms.none { elsewhere.contains(it) }
    }

    private val WORD_SEPARATORS = Regex("""[\s\-‐–_]+""")
    private val POSSESSIVE = Regex("""['’]s?$""")

    private fun englishWords(english: String): List<String> =
        english.trim().replace(ARTICLE, "").split(WORD_SEPARATORS).filter { it.isNotEmpty() }

    private fun sameWord(a: String, b: String): Boolean {
        val x = a.lowercase().replace(POSSESSIVE, "")
        val y = b.lowercase().replace(POSSESSIVE, "")
        return x == y || x == y + "s" || x == y + "es" || y == x + "s" || y == x + "es"
    }

    /**
     * True if [english] is [base] with only ordinary (lowercase) words added: "redwood marsh",
     * "Redwood people" over "Redwood"; "old Viscount Grant" over "Viscount Grant"; "fifth energy
     * level" over "energy level"; "the Viscount" over "viscount". A capitalised addition makes a
     * new name ("Redwood Forest", "Ascendant Bloodline", "Viscount Grant" over "Grant").
     */
    fun addsOnlyOrdinaryWords(english: String, base: String): Boolean {
        val words = englishWords(english)
        val baseWords = englishWords(base)
        if (baseWords.isEmpty() || baseWords.size > words.size) return false
        for (i in 0..words.size - baseWords.size) {
            if (baseWords.indices.all { sameWord(words[i + it], baseWords[it]) }) {
                val rest = words.subList(0, i) + words.subList(i + baseWords.size, words.size)
                return rest.all { it == it.lowercase() }
            }
        }
        return false
    }

    /**
     * The entry of [known] that [source] / [english] merely extends: [source] contains one of its
     * forms (a shorter one) and [english] is its rendering plus ordinary words
     * ([addsOnlyOrdinaryWords]): 紅杉沼澤 "redwood marsh" over 紅杉 → Redwood, 黑菇提取物
     * "Black Mushroom extract" over 黑菇 → Black Mushroom. Such a term needs no entry of its own:
     * the known part is in the prompt whenever the longer one occurs. null when there is none.
     */
    fun compoundOf(source: String, english: String, known: List<GlossaryTerm>): GlossaryTerm? =
        known.firstOrNull { k ->
            TermAliases.formsOf(k).any { it.length < source.length && source.contains(it) } &&
                addsOnlyOrdinaryWords(english, k.english)
        }

    private fun mergeDuplicates(
        terms: List<DiscoveredTerm>,
        dropped: MutableList<Dropped>,
        sameEnglishAsAlias: Boolean,
    ): List<DiscoveredTerm> {
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

        // 2. Same category and English rendering: one entry, the other sources become aliases (or are dropped).
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
                if (sameEnglishAsAlias) primary = absorb(primary, other, addSourceAsAlias = true)
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
