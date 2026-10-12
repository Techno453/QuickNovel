package com.lagradost.quicknovel.translator

/**
 * Extra system-prompt rules for one model (or family of models), appended after the standard
 * rules of [PromptTemplates.systemPrompt] and numbered after them.
 *
 * Profiles target model-specific sloppiness the base prompt does not prevent (glossary tags
 * copied into prose, softened profanity, literal idioms, dropped author's notes...). Which preset
 * uses which profile is decided by the translation benchmark; see docs/translation-bench.md
 * ("Prompt profiles") and docs/translation-models.md.
 *
 * @param rules appended for every source language
 * @param languageRules appended only for that source language (key: zh / ja / ko), after [rules]
 */
data class PromptProfile(
    val id: String,
    val label: String,
    val description: String = "",
    val rules: List<String> = emptyList(),
    val languageRules: Map<String, List<String>> = emptyMap(),
) {
    /** Rules for [sourceLang] (unknown languages get the Chinese ones, like the base prompt). */
    fun rulesFor(sourceLang: String?): List<String> {
        val lang = TranslationLanguages.normalize(sourceLang) ?: TranslationLanguages.DEFAULT
        return rules + languageRules[lang].orEmpty()
    }

    val isDefault: Boolean get() = rules.isEmpty() && languageRules.isEmpty()
}

object PromptProfiles {
    const val DEFAULT_ID = "default"
    const val GENERAL_ID = "general"
    const val LUNA_ID = "luna"
    const val GEMINI_ID = "gemini"

    /** The base prompt only (the behaviour before profiles existed). */
    val DEFAULT = PromptProfile(
        id = DEFAULT_ID,
        label = "Standard",
        description = "The standard prompt only.",
    )

    // Examples in the rules deliberately avoid the benchmark's own sentences.
    private const val NO_TAGS =
        "Write names and terms as plain prose. Never put a category, brackets or any other glossary field after a name " +
            "(write \"Lin Feng\", never \"Lin Feng [character]\" or \"Qingyun City [location]\"); categories belong only in the " +
            "${TranslationProtocol.TERMS_MARKER} JSON."
    private const val IMPLIED_SUBJECTS =
        "When a sentence omits its subject, keep the subject of the surrounding sentences. A person mentioned only inside a " +
            "reason or time clause (\"because her brother was ill, …\") is not automatically the one acting or feeling in the main clause."
    private const val REGISTER =
        "Keep the register: profanity, insults, slang and crude humour stay as strong in English as in the source. Never soften, censor or tone them down."
    private const val IDIOMS =
        "Translate idioms, slang, gestures and set phrases by meaning or with the natural English equivalent, never word for word " +
            "(猫の手も借りたい → \"desperate for any help\"). If the literal reading of an object or image makes no sense in context, " +
            "it is an idiom or slang: translate what it means (a short clarifying word is allowed)."
    private const val AUTHOR_NOTES =
        "Author's notes, afterwords and asides are part of the text: translate them in full, in place. A pen name is a name: " +
            "choose one rendering and use exactly that form every time; never write a title twice in two forms."
    private const val UNITS =
        "Pick one English word for each rank, level or unit term (级, 阶, 段, 층, 段階…) and use it everywhere in the chapter: " +
            "announcements, dialogue and explanatory narration alike."
    private const val SEPARATORS =
        "Scene breaks (***, ---, ◇◇◇) and one-line paragraphs stay as their own paragraphs, unchanged; never merge lines."

    private val GENERAL_RULES = listOf(NO_TAGS, IMPLIED_SUBJECTS, REGISTER, IDIOMS, AUTHOR_NOTES, UNITS, SEPARATORS)

    /** Rules for the failure modes of the 2026-10-11 benchmark (docs/translation-models.md). */
    val GENERAL = PromptProfile(
        id = GENERAL_ID,
        label = "Tuned",
        description = "Adds rules against glossary tags in the prose, misread implied subjects, softened profanity, " +
            "literal idioms and slang, dropped author's notes, inconsistent units and merged lines.",
        rules = GENERAL_RULES,
    )

    /** [GENERAL] plus GPT-6 Luna's remaining misses: scope of degree/frequency words and stiff literal sentences. */
    val LUNA = PromptProfile(
        id = LUNA_ID,
        label = "Tuned + careful reading",
        description = "The tuned rules plus checks on what adverbs modify and a final re-read for natural English.",
        rules = GENERAL_RULES + listOf(
            "Keep what degree and frequency words modify: a word marking one moment as unusual (罕见地, 难得, 珍しく, 웬일로) " +
                "means \"for once\" or \"unusually\" for that moment, not \"rarely\" or \"seldom\" for the action in general.",
            "Before answering, re-read every English sentence and rewrite any that is ungrammatical or only makes sense as a " +
                "word-for-word rendering of the source.",
        ),
    )

    /** [GENERAL] plus Gemini Flash-Lite's misses: swearing toned down, Japanese topic subjects, dropped lines. */
    val GEMINI = PromptProfile(
        id = GEMINI_ID,
        label = "Tuned + strict register",
        description = "The tuned rules plus stricter swearing, subject and paragraph-count rules.",
        rules = GENERAL_RULES + listOf(
            "Swearing (씨발, 존나, くそ, 他妈的…) becomes English swearing of the same strength (fuck, fucking, shit), not mild words like \"darn\" or \"damn it\".",
            "Output exactly one paragraph per source paragraph: dialogue lines, one-word lines and scene breaks each count as one; count before answering.",
        ),
        languageRules = mapOf(
            TranslationLanguages.JAPANESE to listOf(
                "A Japanese sentence without a subject (often ending in らしい, ようだ or ということで) is about the current topic, " +
                    "the person marked with は in the preceding sentences, not a person named inside its own clause.",
            ),
        ),
    )

    /** Every profile, in picker order. */
    val ALL: List<PromptProfile> = listOf(DEFAULT, GENERAL, LUNA, GEMINI)

    /** Profile with this id; unknown or null ids get [DEFAULT]. */
    fun byId(id: String?): PromptProfile = ALL.firstOrNull { it.id == id } ?: DEFAULT

    fun exists(id: String?): Boolean = ALL.any { it.id == id }
}
