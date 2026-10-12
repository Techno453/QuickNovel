package com.lagradost.quicknovel.translator

/**
 * The "Title lookup model" setting (docs/search.md): the model that resolves an English search
 * query to the novel's original title. It is separate from the translation model. The prompt is
 * a few lines per search and language, and the answer is cached, so the default is the strongest
 * OpenAI preset. On 2026-10-12 GPT-6 Sol answered "Above the sky" with 高天之上; GPT-6 Luna did
 * not. Pure, JVM-tested.
 */
object TitleLookupModel {
    /** Preset used when the user never chose one. */
    val DEFAULT_PRESET_ID: String = ModelPresets.GPT_6_SOL.id

    /** Stored value meaning "same as the translation model". */
    const val SAME_AS_TRANSLATION = "same"

    /**
     * Preset id to use for a stored setting value, or null for the translation model: absent →
     * [DEFAULT_PRESET_ID], [SAME_AS_TRANSLATION] → null, an unknown id → [DEFAULT_PRESET_ID].
     */
    fun presetId(stored: String?): String? = when (stored) {
        null -> DEFAULT_PRESET_ID
        SAME_AS_TRANSLATION -> null
        else -> stored.takeIf { ModelPresets.byId(it) != null } ?: DEFAULT_PRESET_ID
    }

    /** Value to store for a choice ([presetId] null = same as the translation model). */
    fun storedValue(presetId: String?): String =
        presetId?.takeIf { ModelPresets.byId(it) != null } ?: SAME_AS_TRANSLATION
}
