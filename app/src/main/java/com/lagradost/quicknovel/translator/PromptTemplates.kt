package com.lagradost.quicknovel.translator

import com.lagradost.quicknovel.translator.db.GlossaryTerm

/**
 * Prompts for the chapter translator. Everything is keyed by the provider's source language
 * (see [TranslationLanguages]); unknown languages fall back to the Chinese prompt.
 *
 * Structure of a chapter request:
 * - system: language conventions + consistency rules + the ===TERMS=== / ===SUMMARY=== output protocol
 * - user: story context (premise, previous chapter, preceding English) → Story Bible →
 *   translation style → source text with its paragraph count → task line
 */
object PromptTemplates {

    private fun rules(languageName: String) = """
Rules:
1. Translate EVERY paragraph. Do not summarize, skip, merge, split or reorder paragraphs: output exactly one English paragraph per source paragraph, separated by blank lines.
2. Use the Story Bible renderings EXACTLY, every time the name or term appears.
3. Use each character's stated gender for every pronoun (he/she/they) and gendered word.
4. $languageName often omits subjects and pronouns. When the source omits them, infer who is speaking or acting from the Story Bible and the recent context; never guess a gender the bible contradicts.
5. Never leave $languageName text in the translation. Romanize or translate every name, sound effect and word.
6. Translate naturally and idiomatically; avoid stiff, overly literal phrasing, but do not add or omit content. No translator notes.
7. Keep system messages, status windows and notices in square brackets [ ] if the source uses brackets.
"""

    private val OUTPUT_PROTOCOL = """
Output format (exactly this, nothing before the translation: no preamble, headings or code fences):
<the English translation>
${TranslationProtocol.TERMS_MARKER}
[{"source":"<name exactly as written in the source>","english":"<your rendering>","category":"character|location|sect|rank|technique|artifact|creature|title|other","gender":"male|female|neutral|unknown","notes":"<role, relationships, speech style>","confidence":<0.0-1.0>}]
${TranslationProtocol.SUMMARY_MARKER}
<2-4 sentences summarizing what happens in this passage>
Characters: <source-language names of the characters present, comma-separated>

In ${TranslationProtocol.TERMS_MARKER} list only names and recurring terms (people, places, sects, ranks, techniques, items, creatures, titles) that appear in this passage and are NOT already in the Story Bible; write [] if there are none. confidence (0-1) is how sure you are of the rendering and the gender.
""".trim()

    private fun system(intro: String, conventions: String, languageName: String) =
        "$intro\nConventions:\n${conventions.trim()}\n${rules(languageName)}"

    /** Number of rules in [rules]; profile rules are numbered after them. */
    const val BASE_RULE_COUNT = 7

    private val CHINESE_SYSTEM = system(
        "You are an expert literary translator of Chinese web novels (xianxia, wuxia, xuanhuan, urban fantasy) into fluent English.",
        """
- Keep established genre terms where readers expect them: qi, dantian, meridians, cultivation realms, sect, Dao, jianghu.
- Render cultivation ranks and techniques as English names consistent with the Story Bible (e.g. Foundation Establishment, Golden Core).
- Translate four-character idioms (chengyu) by meaning, not word for word.
- Names, forms of address and honorifics follow the "Translation style" section of each request.
""",
        "Chinese"
    )

    private val JAPANESE_SYSTEM = system(
        "You are an expert literary translator of Japanese light novels and web novels (narou, isekai, fantasy, romcom) into fluent English.",
        """
- Romanize names with Hepburn, without macrons; name order and honorific suffixes (-san, -kun, -chan, -sama, -senpai, -sensei) follow the "Translation style" section; never invent honorifics that are not in the source.
- Use ruby/furigana readings (e.g. 漢字（かんじ）) for names and special terms when present; do not output the brackets.
- Convert 「」 and 『』 dialogue into normal English quotation marks; 『』 inside dialogue becomes single quotes.
- Render sentence-final particles and speech quirks through tone rather than literally.
- Translate onomatopoeia into natural English sound effects or descriptions.
- Keep game-like terms (skills, status, levels) in [ ] when the source brackets them.
""",
        "Japanese"
    )

    private val KOREAN_SYSTEM = system(
        "You are an expert literary translator of Korean web novels (munpia, KakaoPage, Naver Series: regression, hunter/system, murim, romance fantasy) into fluent English.",
        """
- Names use Revised Romanization (Kim Dokja, Park Minjun); name order follows the "Translation style" section.
- Relationship terms such as hyung, noona, oppa, unnie, sunbae and the -nim / -ssi suffixes follow the honorific instruction in the "Translation style" section.
- Titles and ranks (S-rank Hunter, Guild Master, Patriarch, Heavenly Demon) follow the Story Bible; murim terms use the usual wuxia English (sect, inner energy, martial arts).
- Convey formal vs. casual speech levels (jondaenmal/banmal) through tone and word choice.
- Keep system windows, notifications and quest messages in [ ] as in the source.
""",
        "Korean"
    )

    /** Back-compat alias for the Chinese system prompt. */
    val SYSTEM_PROMPT: String get() = systemPrompt(TranslationLanguages.CHINESE)

    /**
     * System prompt for [sourceLang]: conventions, the numbered rules, then [profile]'s extra rules
     * (numbered after [BASE_RULE_COUNT]; see [PromptProfile]) and the output protocol. The default
     * profile adds nothing.
     */
    fun systemPrompt(sourceLang: String?, profile: PromptProfile = PromptProfiles.DEFAULT): String {
        val head = when (TranslationLanguages.normalize(sourceLang)) {
            TranslationLanguages.JAPANESE -> JAPANESE_SYSTEM
            TranslationLanguages.KOREAN -> KOREAN_SYSTEM
            else -> CHINESE_SYSTEM
        }
        val extra = profile.rulesFor(sourceLang).mapIndexed { i, rule -> "${BASE_RULE_COUNT + i + 1}. ${rule.trim()}\n" }
        return head + extra.joinToString("") + "\n" + OUTPUT_PROTOCOL
    }

    // ------------------------------------------------------------------------------------------
    // Story Bible
    // ------------------------------------------------------------------------------------------

    private val CATEGORY_HEADINGS = linkedMapOf(
        GlossaryTerm.CATEGORY_CHARACTER to "Characters",
        "location" to "Locations",
        "sect" to "Sects & factions",
        "rank" to "Ranks & realms",
        "technique" to "Techniques",
        "artifact" to "Items & artifacts",
        "creature" to "Creatures",
        "title" to "Titles & forms of address",
        GlossaryTerm.CATEGORY_OTHER to "Other terms",
    )

    private fun oneLine(text: String, max: Int): String {
        val s = text.replace(Regex("""\s+"""), " ").trim()
        return if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"
    }

    /** `源名 → English (gender; notes) [also: alias, alias]` for characters, `源名 → English (notes)` otherwise. */
    fun renderBibleEntry(term: GlossaryTerm): String {
        val sb = StringBuilder("- ${term.source} → ${term.english}")
        val notes = oneLine(term.effectiveNotes, 160)
        val details = buildList {
            if (term.isCharacter) {
                add(if (term.gender == GlossaryTerm.GENDER_UNKNOWN) "gender unknown" else term.gender)
            }
            if (notes.isNotEmpty()) add(notes)
        }
        if (details.isNotEmpty()) sb.append(" (").append(details.joinToString("; ")).append(")")
        val aliases = TermAliases.parse(term.aliases).filter { it != term.source }
        if (aliases.isNotEmpty()) sb.append(" [also written: ").append(aliases.joinToString(", ")).append("]")
        return sb.toString()
    }

    /** The Story Bible block: characters first, then the other categories in a fixed order. */
    fun renderBible(terms: List<GlossaryTerm>): String {
        if (terms.isEmpty()) return ""
        val sb = StringBuilder("## Story Bible (use these EXACT English renderings)\n")
        val grouped = terms.groupBy { GlossaryTerm.normalizeCategory(it.category) }
        for ((category, heading) in CATEGORY_HEADINGS) {
            val group = grouped[category] ?: continue
            sb.append("### ").append(heading).append('\n')
            group.forEach { sb.append(renderBibleEntry(it)).append('\n') }
        }
        return sb.toString().trimEnd()
    }

    // ------------------------------------------------------------------------------------------
    // Translation style
    // ------------------------------------------------------------------------------------------

    fun nameStyleInstruction(lang: String?, nameStyle: String): String = when (TranslationLanguages.normalize(lang)) {
        TranslationLanguages.JAPANESE -> when (nameStyle) {
            TranslationStyle.NAME_GIVEN_FAMILY ->
                "Names: Hepburn romanization without macrons, given name first (Western order): 佐藤悠真 → Yuuma Sato. Apply the same order everywhere."
            else ->
                "Names: Hepburn romanization without macrons, family name first (Japanese order): 佐藤悠真 → Sato Yuuma. Apply the same order everywhere."
        }
        TranslationLanguages.KOREAN -> when (nameStyle) {
            TranslationStyle.NAME_GIVEN_FAMILY ->
                "Names: Revised Romanization, given name first (Western order): 김독자 → Dokja Kim."
            else ->
                "Names: Revised Romanization, family name first (Korean order): 김독자 → Kim Dokja."
        }
        else -> when (nameStyle) {
            TranslationStyle.NAME_MEANING ->
                "Names: translate the meaning of places, sects, techniques, titles, epithets and nicknames (青云宗 → Azure Cloud Sect, 老鬼 → Old Ghost); actual personal names stay in Hanyu Pinyin, family name first, no tone marks (林风 → Lin Feng)."
            TranslationStyle.NAME_MIXED ->
                "Names: Hanyu Pinyin for people and places, family name first, no tone marks (林风 → Lin Feng, 天水城 → Tianshui City); translate titles, sects, techniques and items by meaning (青云宗 → Azure Cloud Sect, 太虚剑诀 → Great Void Sword Art)."
            else ->
                "Names: Hanyu Pinyin for all proper nouns — people, places, sects and named techniques/items — family name first, no tone marks, given name as one word (林小雨 → Lin Xiaoyu, 青云宗 → Qingyun Sect). Generic ranks and genre terms follow the Story Bible."
        }
    }

    fun honorificInstruction(lang: String?, honorificStyle: String): String = when (TranslationLanguages.normalize(lang)) {
        TranslationLanguages.JAPANESE -> when (honorificStyle) {
            TranslationStyle.HONORIFICS_DROP -> "Honorifics: drop suffixes (-san, -kun, -sama, -senpai…) and convey politeness through tone; use Senpai/Sensei only as standalone forms of address."
            TranslationStyle.HONORIFICS_ADAPT -> "Honorifics: replace suffixes with natural English (Mr./Ms., Lord, Lady, Master, Teacher) or drop them; do not keep Japanese suffixes."
            else -> "Honorifics: keep suffixes exactly as in the source, attached with a hyphen (Sato-san, Yuuma-kun, Ojou-sama); do not invent ones that are not there."
        }
        TranslationLanguages.KOREAN -> when (honorificStyle) {
            TranslationStyle.HONORIFICS_DROP -> "Honorifics: drop -ssi/-nim and relationship terms (hyung, noona, oppa, unnie, sunbae); use names and convey respect through tone."
            TranslationStyle.HONORIFICS_ADAPT -> "Honorifics: render -nim with English titles (Young Master, Elder, Guild Master), relationship terms as natural English (brother, senior), drop -ssi."
            else -> "Honorifics: keep hyung, noona, oppa, unnie and sunbae, and keep -nim attached to titles (Guild Master-nim); drop -ssi."
        }
        else -> when (honorificStyle) {
            TranslationStyle.HONORIFICS_KEEP -> "Forms of address: keep them in Pinyin (shixiong, shijie, shifu, qianbei, gongzi)."
            TranslationStyle.HONORIFICS_DROP -> "Forms of address: use plain names where natural and convey respect through tone; drop titles such as shixiong/qianbei unless needed for meaning."
            else -> "Forms of address: translate into natural English (Senior Brother, Senior Sister, Master, Senior, Young Master)."
        }
    }

    fun renderStyle(lang: String?, style: TranslationStyle?): String {
        val s = style ?: TranslationStyle.defaultFor(lang)
        val sb = StringBuilder("## Translation style\n")
        sb.append("- ").append(nameStyleInstruction(lang, s.nameStyle)).append('\n')
        sb.append("- ").append(honorificInstruction(lang, s.honorificStyle)).append('\n')
        sb.append("- Story Bible entries override these conventions.\n")
        if (s.styleNotes.isNotBlank()) {
            sb.append("- Reader's instructions: ").append(s.styleNotes.trim()).append('\n')
        }
        return sb.toString().trimEnd()
    }

    // ------------------------------------------------------------------------------------------
    // Chapter request
    // ------------------------------------------------------------------------------------------

    /** Characters of preceding English text passed as continuity context. */
    const val CONTEXT_TAIL_CHARS = 500

    fun renderContext(context: StoryContext?, precedingEnglish: String): String {
        val sb = StringBuilder()
        if (context != null && context.premise.isNotBlank()) {
            sb.append("## Story so far\n").append(context.premise.trim()).append("\n\n")
        }
        if (context != null && context.previousChapterSummary.isNotBlank()) {
            sb.append("## Previous chapter\n").append(context.previousChapterSummary.trim()).append("\n\n")
        }
        val tail = precedingEnglish.trim().takeLast(CONTEXT_TAIL_CHARS)
        if (tail.isNotEmpty()) {
            sb.append("## Preceding English text (context only; do not translate or repeat it)\n")
            if (tail.length < precedingEnglish.trim().length) sb.append('…')
            sb.append(tail).append("\n\n")
        }
        return sb.toString().trimEnd()
    }

    /**
     * @param bibleTerms the bible entries to show (terms in this chunk + recently present characters)
     * @param context continuity context; null (or carry-context off) renders none
     * @param precedingEnglish English output immediately before this chunk ("" for none)
     * @param reminder optional strict-mode reminder from [buildReminder]
     */
    fun buildUserPrompt(
        chunk: String,
        bibleTerms: List<GlossaryTerm>,
        sourceLang: String? = TranslationLanguages.DEFAULT,
        context: StoryContext? = null,
        precedingEnglish: String = "",
        reminder: String? = null,
    ): String {
        val languageName = TranslationLanguages.displayName(sourceLang)
        val paragraphs = QualityChecks.countParagraphs(chunk)
        val sections = listOf(
            renderContext(context, precedingEnglish),
            renderBible(bibleTerms),
            renderStyle(sourceLang, context?.style),
            "## $languageName Text: ($paragraphs paragraph${if (paragraphs == 1) "" else "s"})\n$chunk",
            "## Task\nTranslate all $paragraphs paragraph${if (paragraphs == 1) "" else "s"} above into English " +
                "(the same number of paragraphs, separated by blank lines), then write the " +
                "${TranslationProtocol.TERMS_MARKER} and ${TranslationProtocol.SUMMARY_MARKER} sections.",
            reminder.orEmpty(),
        )
        return sections.filter { it.isNotBlank() }.joinToString("\n\n")
    }

    /** Strict-mode reminder appended to the re-request after a failed check. */
    fun buildReminder(quality: ChunkQuality, sourceLang: String?): String {
        val languageName = TranslationLanguages.displayName(sourceLang)
        val sb = StringBuilder("## IMPORTANT — the previous attempt had problems; fix them in this attempt\n")
        if (quality.violatedTerms.isNotEmpty()) {
            sb.append("- These names/terms were not rendered as required. Use exactly: ")
                .append(quality.violatedTerms.joinToString("; ") { "${it.source} → ${it.english}" })
                .append('\n')
        }
        if (quality.leakedFragments.isNotEmpty()) {
            sb.append("- Untranslated $languageName text was left in the output: ")
                .append(quality.leakedFragments.distinct().take(10).joinToString(", ") { "「$it」" })
                .append(". Translate or romanize every fragment; the translation must contain no $languageName characters.\n")
        }
        if (quality.paragraphShortfall) {
            sb.append("- Only ${quality.outputParagraphs} of ${quality.inputParagraphs} paragraphs were translated. ")
                .append("Translate ALL ${quality.inputParagraphs} paragraphs; do not summarize, merge or skip any.\n")
        }
        return sb.toString().trimEnd()
    }

    /** Used only when a multi-chunk chapter's merged summary is long (and story context is on). */
    const val SUMMARY_MERGE_SYSTEM =
        "You merge consecutive passage summaries of one novel chapter into a single summary of 3-5 sentences. " +
            "Keep character names exactly as written, keep who did what to whom, and output only the summary."

    // ------------------------------------------------------------------------------------------
    // "Analyze novel" pre-pass
    // ------------------------------------------------------------------------------------------

    fun analysisSystemPrompt(sourceLang: String?): String {
        val languageName = TranslationLanguages.displayName(sourceLang)
        return """
You build a translator's Story Bible for a $languageName web novel. You do NOT translate the text.
Read the excerpt and extract:
- every named character: English name, gender, role, relationships to other characters (use their English names), speech style or verbal tics, other names/nicknames they are called by;
- places, sects/factions/organizations, cultivation ranks/levels, techniques/skills, notable items, creatures, titles.
Gender: use explicit evidence only (pronouns such as 他/她, 彼/彼女, 그/그녀, kinship terms, titles, gendered descriptions); otherwise "unknown".
Output ONLY one JSON object, no prose and no code fences:
{"premise":"<3-5 sentences: setting, protagonist, central conflict so far>","terms":[{"source":"<exactly as written in the source>","english":"<rendering>","category":"character|location|sect|rank|technique|artifact|creature|title|other","gender":"male|female|neutral|unknown","notes":"<role; relationships; speech style>","aliases":["<other source-language forms of the same name>"],"confidence":<0.0-1.0>}]}
List characters first. Skip anything in the "Already known" list.
""".trim()
    }

    fun buildAnalysisPrompt(
        excerpt: String,
        sourceLang: String?,
        knownSources: Collection<String>,
        previousPremise: String,
        style: TranslationStyle?,
        chapterLabel: String,
    ): String {
        val languageName = TranslationLanguages.displayName(sourceLang)
        val sb = StringBuilder()
        if (knownSources.isNotEmpty()) {
            sb.append("## Already known (skip these)\n").append(knownSources.take(300).joinToString(", ")).append("\n\n")
        }
        if (previousPremise.isNotBlank()) {
            sb.append("## Premise so far (extend it with this excerpt)\n").append(previousPremise.trim()).append("\n\n")
        }
        sb.append(renderStyle(sourceLang, style)).append("\n\n")
        sb.append("## $languageName excerpt ($chapterLabel)\n").append(excerpt.trim()).append("\n\n")
        sb.append("## Task\nOutput the JSON object described in the instructions.")
        return sb.toString()
    }

    /** System prompt for translating a search query into the provider's language. */
    fun searchQuerySystemPrompt(targetLang: String): String {
        val name = TranslationLanguages.displayName(targetLang)
        return """
You translate novel search queries into $name so they can be typed into a $name web-novel site's search box.
- If the query is a known novel title, author or character, output its original $name name.
- Otherwise translate the meaning concisely using the wording a $name reader would search for.
${if (TranslationLanguages.normalize(targetLang) == TranslationLanguages.CHINESE) "- Use Simplified Chinese." else ""}
- Output ONLY the query text on one line: no quotes, no explanations, no romanization.
""".trimIndent()
    }
}
