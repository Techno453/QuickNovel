package com.lagradost.quicknovel.translator

import com.lagradost.quicknovel.translator.db.GlossaryTerm

/**
 * Prompts for the chapter translator. Everything is keyed by the provider's source language
 * (see [TranslationLanguages]); unknown languages fall back to the Chinese prompt.
 */
object PromptTemplates {

    private const val COMMON_RULES = """
General rules:
- Output ONLY the English translation. No preamble, no notes, no headings, no code fences.
- Preserve the paragraph structure: one translated paragraph per source paragraph, separated by a blank line.
- Use glossary terms EXACTLY as given; consistency of names and terms is critical.
- Translate naturally and idiomatically; avoid stiff, overly literal phrasing, but do not omit or add content.
- Keep system messages, status windows and notices in square brackets [ ] if the source uses brackets.
"""

    private val CHINESE_SYSTEM = """
You are an expert literary translator of Chinese web novels (xianxia, wuxia, xuanhuan, urban fantasy) into fluent English.
Conventions:
- Keep established genre terms where readers expect them: qi, dantian, meridians, cultivation realms, sect, Dao, jianghu.
- Render cultivation ranks and techniques as English names consistent with the glossary (e.g. Foundation Establishment, Golden Core).
- Names use Hanyu Pinyin, family name first (Lin Feng), without tone marks.
- Forms of address: translate shixiong/shijie/shifu-style terms as Senior Brother / Senior Sister / Master unless the glossary says otherwise.
- Translate four-character idioms (chengyu) by meaning, not word for word.
$COMMON_RULES
""".trimIndent()

    private val JAPANESE_SYSTEM = """
You are an expert literary translator of Japanese light novels and web novels (narou, isekai, fantasy, romcom) into fluent English.
Conventions:
- Keep honorific suffixes as written (-san, -kun, -chan, -sama, -dono, -senpai, -sensei) attached with a hyphen; do not invent ones that are not in the source.
- Personal names: romanize with Hepburn, given name first (Western order) unless the glossary specifies otherwise; be consistent.
- Use ruby/furigana readings (e.g. 漢字（かんじ）) for names and special terms when present; do not output the brackets.
- Convert 「」 and 『』 dialogue into normal English quotation marks; 『』 inside dialogue becomes single quotes.
- Infer omitted subjects from context; render sentence-final particles and speech quirks through tone rather than literally.
- Translate onomatopoeia into natural English sound effects or descriptions.
- Keep game-like terms (skills, status, levels) in [ ] when the source brackets them.
$COMMON_RULES
""".trimIndent()

    private val KOREAN_SYSTEM = """
You are an expert literary translator of Korean web novels (munpia, KakaoPage, Naver Series: regression, hunter/system, murim, romance fantasy) into fluent English.
Conventions:
- Names use Revised Romanization, family name first (Kim Dokja, Park Minjun), unless the glossary specifies otherwise.
- Relationship terms: keep hyung, noona, oppa, unnie and sunbae when they carry nuance; translate -nim with titles (e.g. Young Master, Elder, Guild Master); drop -ssi or render naturally.
- Titles and ranks (S-rank Hunter, Guild Master, Patriarch, Heavenly Demon) follow the glossary; murim terms use the usual wuxia English (sect, inner energy, martial arts).
- Convey formal vs. casual speech levels (jondaenmal/banmal) through tone and word choice.
- Keep system windows, notifications and quest messages in [ ] as in the source.
$COMMON_RULES
""".trimIndent()

    /** Back-compat alias for the Chinese system prompt. */
    val SYSTEM_PROMPT: String get() = CHINESE_SYSTEM

    fun systemPrompt(sourceLang: String?): String = when (TranslationLanguages.normalize(sourceLang)) {
        TranslationLanguages.JAPANESE -> JAPANESE_SYSTEM
        TranslationLanguages.KOREAN -> KOREAN_SYSTEM
        else -> CHINESE_SYSTEM
    }

    private fun exampleSourceTerm(sourceLang: String?): String = when (TranslationLanguages.normalize(sourceLang)) {
        TranslationLanguages.JAPANESE -> "原文の用語"
        TranslationLanguages.KOREAN -> "원문 용어"
        else -> "中文原文"
    }

    fun buildUserPrompt(
        chunk: String,
        relevantTerms: List<GlossaryTerm>,
        sourceLang: String? = TranslationLanguages.DEFAULT
    ): String {
        val languageName = TranslationLanguages.displayName(sourceLang)
        val sb = StringBuilder()

        if (relevantTerms.isNotEmpty()) {
            sb.appendLine("## Required Terminology (use these EXACT translations):")
            for (term in relevantTerms) {
                sb.appendLine("「${term.source}」→ ${term.english} [${term.category}]")
            }
            sb.appendLine()
        }

        sb.appendLine("## Instructions:")
        sb.appendLine("- Use the glossary terms EXACTLY as specified — this is critical for consistency")
        sb.appendLine("- If you encounter a NEW proper noun (name, place, technique, title) not in the glossary, mark it inline as: [NEW: ${exampleSourceTerm(sourceLang)} → your suggested translation]")
        sb.appendLine("- Translate naturally, avoid overly literal translations")
        sb.appendLine("- Preserve paragraph structure (blank line between paragraphs)")
        sb.appendLine("- Output only the translation")
        sb.appendLine()
        sb.appendLine("## $languageName Text:")
        sb.appendLine(chunk)
        sb.appendLine()
        sb.append("## Translation:")

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
