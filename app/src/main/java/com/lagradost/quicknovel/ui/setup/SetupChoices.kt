package com.lagradost.quicknovel.ui.setup

import com.lagradost.quicknovel.translator.CostEstimator
import com.lagradost.quicknovel.translator.LlmProvider
import com.lagradost.quicknovel.translator.LlmProviders
import com.lagradost.quicknovel.translator.ModelPreset
import com.lagradost.quicknovel.translator.ModelPresets
import com.lagradost.quicknovel.translator.TranslationLanguages

/** Wizard pages, in order. */
enum class SetupStep { WELCOME, KEY, TEST, MODEL, DONE }

/** The providers the wizard offers (anything else is configured in the full settings). */
enum class SetupProvider(val provider: LlmProvider, val keyPageUrl: String) {
    OPENAI(LlmProviders.OPENAI, "https://platform.openai.com/api-keys"),
    OPENROUTER(LlmProviders.OPENROUTER, "https://openrouter.ai/settings/keys"),
    GOOGLE(LlmProviders.GOOGLE, "https://aistudio.google.com/apikey");

    companion object {
        fun forProviderId(id: String?): SetupProvider? = entries.firstOrNull { it.provider.id == id }
    }
}

/** The three model roles the wizard offers, from the recommendations in docs/translation-models.md. */
enum class SetupModelRole { DEFAULT, QUALITY, GOOGLE_CHEAP }

/**
 * One model option: a preset, its role, and the cost per 100 chapters measured in the
 * 2026-10-11 benchmark (docs/translation-models.md, "Recommendations" table). The estimate shown
 * next to it comes from [CostEstimator] and the preset's list prices.
 */
data class SetupModelChoice(
    val role: SetupModelRole,
    val preset: ModelPreset,
    val measuredUsdPer100: Double,
)

/** Pure decisions behind the wizard (no Android), unit tested. */
object SetupChoices {
    /** Measured $ per 100 chapters (docs/translation-models.md, Recommendations). */
    private const val MEASURED_LUNA = 0.19
    private const val MEASURED_SOL = 3.73
    private const val MEASURED_FLASH_LITE = 0.57

    /** Source languages the wizard asks about, in display order. */
    val READING_LANGUAGES = listOf(
        TranslationLanguages.CHINESE,
        TranslationLanguages.JAPANESE,
        TranslationLanguages.KOREAN,
    )

    /** Show the wizard on launch only if it was never finished or skipped and no API key exists. */
    fun shouldShowOnLaunch(setupCompleted: Boolean, hasAnyApiKey: Boolean): Boolean =
        !setupCompleted && !hasAnyApiKey

    /** Pages for a provider choice; "later" (null) skips the key test and the model choice. */
    fun steps(provider: SetupProvider?): List<SetupStep> =
        if (provider == null) listOf(SetupStep.WELCOME, SetupStep.KEY, SetupStep.DONE)
        else SetupStep.entries.toList()

    /** Model options for [provider]: only presets that the provider's key can use. */
    fun modelChoices(provider: SetupProvider?): List<SetupModelChoice> = when (provider) {
        SetupProvider.OPENAI -> listOf(
            SetupModelChoice(SetupModelRole.DEFAULT, ModelPresets.GPT_6_LUNA, MEASURED_LUNA),
            SetupModelChoice(SetupModelRole.QUALITY, ModelPresets.GPT_6_SOL, MEASURED_SOL),
        )
        SetupProvider.OPENROUTER -> listOf(
            SetupModelChoice(SetupModelRole.DEFAULT, ModelPresets.OR_GPT_6_LUNA, MEASURED_LUNA),
            SetupModelChoice(SetupModelRole.QUALITY, ModelPresets.OR_GPT_6_SOL, MEASURED_SOL),
            SetupModelChoice(SetupModelRole.GOOGLE_CHEAP, ModelPresets.OR_GEMINI_3_1_FLASH_LITE, MEASURED_FLASH_LITE),
        )
        SetupProvider.GOOGLE -> listOf(
            SetupModelChoice(SetupModelRole.GOOGLE_CHEAP, ModelPresets.GEMINI_3_1_FLASH_LITE, MEASURED_FLASH_LITE),
        )
        null -> emptyList()
    }

    /** The preselected model: the current one if [provider] offers it, else the provider's first option. */
    fun initialModel(provider: SetupProvider?, currentPresetId: String?): ModelPreset? {
        val choices = modelChoices(provider)
        return choices.firstOrNull { it.preset.id == currentPresetId }?.preset ?: choices.firstOrNull()?.preset
    }

    /**
     * Estimated USD for 100 chapters of [lang] with [preset] (CostEstimator defaults: 4,500
     * characters per chapter, no Story Bible terms), [strictMode] as in the settings.
     */
    fun estimatePer100(preset: ModelPreset, lang: String, strictMode: Boolean): CostEstimator.Estimate =
        CostEstimator.estimateForPreset(preset, 100, lang, strictMode)

    /** The language to price estimates in: the first one the user reads, else Chinese. */
    fun pricingLanguage(reading: Collection<String>): String =
        READING_LANGUAGES.firstOrNull { it in reading } ?: TranslationLanguages.CHINESE

    /**
     * The provider-language setting after the wizard: CJK languages the user reads are on,
     * the others off; every non-CJK language keeps its current state. An empty choice leaves
     * the setting unchanged (nothing would be searchable otherwise).
     */
    fun providerLanguages(current: Set<String>, reading: Set<String>): Set<String> {
        if (reading.none { it in READING_LANGUAGES }) return current
        return current.filterNot { it in READING_LANGUAGES }.toSet() + reading.filter { it in READING_LANGUAGES }
    }

    /** Read-ahead after the wizard toggle: keep a custom count when turning on, 0 when off. */
    fun readAheadAfterToggle(enabled: Boolean, current: Int, default: Int): Int =
        if (!enabled) 0 else if (current > 0) current else default
}
