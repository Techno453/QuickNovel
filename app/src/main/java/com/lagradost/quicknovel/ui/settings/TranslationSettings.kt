package com.lagradost.quicknovel.ui.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.lagradost.quicknovel.CommonActivity.showToast
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.translator.CostEstimator
import com.lagradost.quicknovel.translator.LlmConfig
import com.lagradost.quicknovel.translator.LlmProviders
import com.lagradost.quicknovel.translator.ModelPreset
import com.lagradost.quicknovel.translator.ModelPresets
import com.lagradost.quicknovel.translator.PresetTier
import com.lagradost.quicknovel.translator.PromptProfiles
import com.lagradost.quicknovel.translator.SecurePrefs
import com.lagradost.quicknovel.translator.TranslationEngine
import com.lagradost.quicknovel.translator.TranslationLanguages
import com.lagradost.quicknovel.translator.TranslationStyle
import com.lagradost.quicknovel.translator.PowerPolicy
import com.lagradost.quicknovel.translator.SpendLedger
import com.lagradost.quicknovel.translator.SpendPricing
import com.lagradost.quicknovel.ui.translate.SpendDialog
import com.lagradost.quicknovel.ui.translate.TranslationForegroundService
import androidx.compose.runtime.LaunchedEffect
import com.lagradost.quicknovel.util.GoogleTranslate
import com.mihon.common.preference.PreferenceData
import com.mihon.presentation.settings.Preference
import com.mihon.presentation.settings.widget.PreferenceGroupHeader
import com.mihon.presentation.settings.widget.TextPreferenceWidget
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Adapts a getter/setter pair (e.g. a [SecurePrefs] field) to Mihon's [PreferenceData] so it can be
 * used with the standard Compose preference widgets. The value is read once on creation.
 */
class AccessorPreferenceData<T>(
    private val key: String,
    private val default: T,
    read: () -> T,
    private val write: (T) -> Unit,
) : PreferenceData<T> {
    private val state = MutableStateFlow(read())

    override fun key(): String = key
    override fun get(): T = state.value
    override fun set(value: T) {
        write(value)
        state.value = value
    }

    override fun isSet(): Boolean = true
    override fun delete() = set(default)
    override fun defaultValue(): T = default
    override fun changes(): Flow<T> = state
    override fun stateIn(scope: CoroutineScope): StateFlow<T> = state.asStateFlow()
}

object TranslationSettings {
    private const val GROUP_TITLE = "AI Translation"

    private fun secureSwitch(
        context: Context,
        key: String,
        default: Boolean,
        read: (Context) -> Boolean,
        write: (Context, Boolean) -> Unit,
    ) = AccessorPreferenceData(key, default, { read(context) }, { write(context, it) })

    private val STYLE_LANGUAGES = listOf(
        TranslationLanguages.CHINESE,
        TranslationLanguages.JAPANESE,
        TranslationLanguages.KOREAN,
    )

    /** Short labels for list summaries; the dialogs show the longer descriptions. */
    private val SHORT_STYLE_LABELS = mapOf(
        TranslationStyle.NAME_PINYIN to "Pinyin",
        TranslationStyle.NAME_MEANING to "Translated meaning",
        TranslationStyle.NAME_MIXED to "Mixed",
        TranslationStyle.NAME_FAMILY_GIVEN to "Family name first",
        TranslationStyle.NAME_GIVEN_FAMILY to "Given name first",
        TranslationStyle.HONORIFICS_KEEP to "Keep",
        TranslationStyle.HONORIFICS_DROP to "Drop",
        TranslationStyle.HONORIFICS_ADAPT to "Adapt to English",
    )

    @Composable
    private fun styleLabel(style: String): String = when (style) {
        TranslationStyle.NAME_PINYIN -> stringResource(R.string.style_name_pinyin)
        TranslationStyle.NAME_MEANING -> stringResource(R.string.style_name_meaning)
        TranslationStyle.NAME_MIXED -> stringResource(R.string.style_name_mixed)
        TranslationStyle.NAME_FAMILY_GIVEN -> stringResource(R.string.style_name_family_given)
        TranslationStyle.NAME_GIVEN_FAMILY -> stringResource(R.string.style_name_given_family)
        TranslationStyle.HONORIFICS_KEEP -> stringResource(R.string.style_hon_keep)
        TranslationStyle.HONORIFICS_DROP -> stringResource(R.string.style_hon_drop)
        TranslationStyle.HONORIFICS_ADAPT -> stringResource(R.string.style_hon_adapt)
        else -> style
    }

    /** Default name style + honorific style list rows for one source language. */
    @Composable
    private fun styleDefaults(
        context: Context,
        lang: String,
        icon: Painter,
    ): List<Preference.PreferenceItem<out Any?, out Any?>> {
        val language = TranslationLanguages.displayName(lang)
        val nameStyle = remember(lang) {
            AccessorPreferenceData(
                SecurePrefs.KEY_DEFAULT_NAME_STYLE_PREFIX + lang,
                TranslationStyle.defaultNameStyle(lang),
                { SecurePrefs.getDefaultNameStyle(context, lang) },
                { SecurePrefs.setDefaultNameStyle(context, lang, it) },
            )
        }
        val honorificStyle = remember(lang) {
            AccessorPreferenceData(
                SecurePrefs.KEY_DEFAULT_HONORIFIC_STYLE_PREFIX + lang,
                TranslationStyle.defaultHonorificStyle(lang),
                { SecurePrefs.getDefaultHonorificStyle(context, lang) },
                { SecurePrefs.setDefaultHonorificStyle(context, lang, it) },
            )
        }
        val nameEntries = TranslationStyle.nameStyleOptions(lang).associateWith { styleLabel(it) }
        val honorificEntries = TranslationStyle.HONORIFIC_OPTIONS.associateWith { styleLabel(it) }
        val shortLabel: @Composable (String, Map<String, String>) -> String? =
            { value, _ -> SHORT_STYLE_LABELS[value] ?: value }
        return listOf(
            Preference.PreferenceItem.ListPreference(
                icon = icon,
                title = "$language names",
                preference = nameStyle,
                entries = nameEntries,
                subtitleProvider = shortLabel,
            ),
            Preference.PreferenceItem.ListPreference(
                icon = icon,
                title = "$language honorifics",
                preference = honorificStyle,
                entries = honorificEntries,
                subtitleProvider = shortLabel,
            ),
        )
    }

    /** The "AI Translation" settings group. Add new SecurePrefs-backed items to the list below. */
    @Composable
    fun preferenceGroup(): Preference.PreferenceGroup {
        val context = LocalContext.current.applicationContext
        val translateIcon = painterResource(R.drawable.ic_baseline_translate_24)

        val enabled = remember {
            secureSwitch(
                context, "translator_enabled", true,
                SecurePrefs::isTranslationEnabled, SecurePrefs::setTranslationEnabled
            )
        }
        val titles = remember {
            secureSwitch(
                context, "translator_gtranslate_titles", false,
                SecurePrefs::isGoogleTranslateTitlesEnabled,
                SecurePrefs::setGoogleTranslateTitlesEnabled
            )
        }
        val searchQueries = remember {
            secureSwitch(
                context, "translator_search_queries", true,
                SecurePrefs::isTranslateSearchEnabled, SecurePrefs::setTranslateSearchEnabled
            )
        }
        val aiSearch = remember {
            secureSwitch(
                context, "translator_ai_search", false,
                SecurePrefs::isAiSearchEnabled, SecurePrefs::setAiSearchEnabled
            )
        }
        val onDemand = remember {
            secureSwitch(
                context, "translator_on_demand", false,
                SecurePrefs::isTranslateOnDemandEnabled, SecurePrefs::setTranslateOnDemandEnabled
            )
        }

        val autoAddTerms = remember {
            secureSwitch(
                context, "translator_auto_add_terms", true,
                SecurePrefs::isAutoAddTermsEnabled, SecurePrefs::setAutoAddTermsEnabled
            )
        }
        val strictMode = remember {
            secureSwitch(
                context, "translator_strict_mode", true,
                SecurePrefs::isStrictModeEnabled, SecurePrefs::setStrictModeEnabled
            )
        }
        val carryContext = remember {
            secureSwitch(
                context, "translator_carry_context", true,
                SecurePrefs::isCarryStoryContextEnabled, SecurePrefs::setCarryStoryContextEnabled
            )
        }
        val analyzeChapters = remember {
            AccessorPreferenceData(
                "translator_analyze_chapters", SecurePrefs.DEFAULT_ANALYZE_CHAPTERS,
                { SecurePrefs.getAnalyzeChapterCount(context) },
                { SecurePrefs.setAnalyzeChapterCount(context, it) },
            )
        }
        val autoAnalyze = remember {
            secureSwitch(
                context, "translator_auto_analyze", true,
                SecurePrefs::isAutoAnalyzeEnabled, SecurePrefs::setAutoAnalyzeEnabled
            )
        }
        val autoAnalyzeChapters = remember {
            AccessorPreferenceData(
                "translator_auto_analyze_chapters", SecurePrefs.DEFAULT_AUTO_ANALYZE_CHAPTERS,
                { SecurePrefs.getAutoAnalyzeChapterCount(context) },
                { SecurePrefs.setAutoAnalyzeChapterCount(context, it) },
            )
        }
        val readAhead = remember {
            AccessorPreferenceData(
                "translator_read_ahead", SecurePrefs.DEFAULT_READ_AHEAD_CHAPTERS,
                { SecurePrefs.getReadAheadChapters(context) },
                { SecurePrefs.setReadAheadChapters(context, it) },
            )
        }
        val readAheadEntries = SecurePrefs.READ_AHEAD_OPTIONS.associateWith { n ->
            when (n) {
                0 -> "Off"
                1 -> "1 chapter"
                else -> "$n chapters"
            }
        }
        val styleItems = STYLE_LANGUAGES.flatMap { lang -> styleDefaults(context, lang, translateIcon) }
        // Bumped whenever the model, fallback or a key changes so dependent rows re-read SecurePrefs.
        val configVersion = remember { mutableStateOf(0) }

        val items: List<Preference.PreferenceItem<out Any?, out Any?>> = listOf(
            Preference.PreferenceItem.CustomPreference(
                title = "Translation model",
                content = { ModelPreference(context, configVersion) },
            ),
            Preference.PreferenceItem.CustomPreference(
                title = "API key",
                content = { ApiKeyPreference(context, configVersion) },
            ),
            Preference.PreferenceItem.CustomPreference(
                title = "Fallback model",
                content = { FallbackPreference(context, configVersion) },
            ),
            Preference.PreferenceItem.CustomPreference(
                title = "Fallback API key",
                content = { FallbackKeyPreference(context, configVersion) },
            ),
            Preference.PreferenceItem.SwitchPreference(
                icon = translateIcon,
                title = "Enable translation UI",
                subtitle = "Show the Translate button for Chinese, Japanese and Korean sources",
                preference = enabled,
            ),
            Preference.PreferenceItem.SwitchPreference(
                icon = translateIcon,
                title = "Auto-translate titles & synopses",
                subtitle = "Use Google Translate (free) for titles, chapter names and synopses",
                preference = titles,
                onValueChanged = {
                    GoogleTranslate.clearCache()
                    true
                },
            ),
            metadataAiPreference(context, translateIcon),
            Preference.PreferenceItem.SwitchPreference(
                icon = translateIcon,
                title = "Translate search queries",
                subtitle = "Search Chinese, Japanese and Korean sources in their own language",
                preference = searchQueries,
            ),
            Preference.PreferenceItem.SwitchPreference(
                icon = translateIcon,
                title = "Use AI for search queries",
                subtitle = "Ask the LLM first (better at original novel titles); needs an API key",
                preference = aiSearch,
            ),
            Preference.PreferenceItem.SwitchPreference(
                icon = translateIcon,
                title = "Translate chapters on demand",
                subtitle = "Translate untranslated chapters with the LLM when opened in the reader",
                preference = onDemand,
            ),
            Preference.PreferenceItem.ListPreference(
                icon = translateIcon,
                title = "Auto-translate ahead",
                preference = readAhead,
                entries = readAheadEntries,
                subtitleProvider = { value, entries ->
                    if (value == 0) "Off. Keeps the next chapters after the one you are reading translated in the background"
                    else "Keep the next ${entries[value]} after the one you are reading translated (when reading in translation)"
                },
                onValueChanged = { value ->
                    if (value == 0) TranslationForegroundService.stopReadAhead(context)
                    true
                },
            ),
            Preference.PreferenceItem.CustomPreference(
                title = "Consistency",
                content = { PreferenceGroupHeader(title = "Consistency") },
            ),
            Preference.PreferenceItem.SwitchPreference(
                icon = translateIcon,
                title = "Auto-add discovered names",
                subtitle = "Add confidently detected names to the Story Bible right away instead of waiting for review",
                preference = autoAddTerms,
            ),
            Preference.PreferenceItem.SwitchPreference(
                icon = translateIcon,
                title = "Strict consistency mode",
                subtitle = "Retry a passage once on glossary misses, leftover source text or dropped paragraphs",
                preference = strictMode,
            ),
            Preference.PreferenceItem.SwitchPreference(
                icon = translateIcon,
                title = "Carry story context between chapters",
                subtitle = "Send the premise and previous chapter summary with each chapter",
                preference = carryContext,
            ),
            Preference.PreferenceItem.SliderPreference(
                icon = translateIcon,
                title = "Chapters to analyze",
                subtitle = "Default for Analyze novel, which builds the character sheet",
                preference = analyzeChapters,
                valueRange = 1..SecurePrefs.MAX_ANALYZE_CHAPTERS,
            ),
            Preference.PreferenceItem.SwitchPreference(
                icon = translateIcon,
                title = "Analyze novel before first translation",
                subtitle = "Build the character sheet from the first chapters automatically before a novel is translated for the first time",
                preference = autoAnalyze,
            ),
            Preference.PreferenceItem.SliderPreference(
                icon = translateIcon,
                title = "Chapters analyzed automatically",
                subtitle = "How many of the first chapters the automatic analysis reads",
                preference = autoAnalyzeChapters,
                valueRange = 1..SecurePrefs.MAX_ANALYZE_CHAPTERS,
            ),
            Preference.PreferenceItem.CustomPreference(
                title = "Analysis model",
                content = { AnalysisModelPreference(context, configVersion) },
            ),
        ) + spendAndJobItems(context, translateIcon) + styleItems

        return Preference.PreferenceGroup(
            title = GROUP_TITLE,
            preferenceItems = items.toPersistentList(),
        )
    }

    /** "Spending & long jobs": spend dashboard, caps, charging / battery conditions. */
    @Composable
    private fun spendAndJobItems(context: Context, icon: Painter): List<Preference.PreferenceItem<out Any?, out Any?>> {
        val onlyWhileCharging = remember {
            secureSwitch(
                context, SecurePrefs.KEY_JOBS_ONLY_WHILE_CHARGING, false,
                SecurePrefs::isJobsOnlyWhileCharging, SecurePrefs::setJobsOnlyWhileCharging
            )
        }
        val pauseOnLow = remember {
            secureSwitch(
                context, SecurePrefs.KEY_JOBS_PAUSE_ON_LOW_BATTERY, true,
                SecurePrefs::isJobsPauseOnLowBattery, SecurePrefs::setJobsPauseOnLowBattery
            )
        }
        val lowPercent = remember {
            AccessorPreferenceData(
                SecurePrefs.KEY_JOBS_LOW_BATTERY_PERCENT, PowerPolicy.DEFAULT_LOW_BATTERY_PERCENT,
                { SecurePrefs.getJobsLowBatteryPercent(context) },
                { SecurePrefs.setJobsLowBatteryPercent(context, it) },
            )
        }
        return listOf(
            Preference.PreferenceItem.CustomPreference(
                title = "Spending & long jobs",
                content = { PreferenceGroupHeader(title = "Spending & long jobs") },
            ),
            Preference.PreferenceItem.CustomPreference(
                title = "Spending",
                content = { SpendingPreference(context) },
            ),
            Preference.PreferenceItem.CustomPreference(
                title = "Monthly spend cap",
                content = {
                    CapPreference(
                        context, "Monthly spend cap",
                        "Jobs that would pass it are refused; running jobs stop when it is reached",
                        SecurePrefs::getMonthlySpendCap, SecurePrefs::setMonthlySpendCap,
                    )
                },
            ),
            Preference.PreferenceItem.CustomPreference(
                title = "Per-job cap",
                content = {
                    CapPreference(
                        context, "Per-job cap",
                        "Largest cost of one range job or overnight batch",
                        SecurePrefs::getPerJobCap, SecurePrefs::setPerJobCap,
                    )
                },
            ),
            Preference.PreferenceItem.SwitchPreference(
                icon = icon,
                title = "Long jobs only while charging",
                subtitle = "Range translations and auto-translate ahead wait for a charger. The chapter you are reading is always translated",
                preference = onlyWhileCharging,
            ),
            Preference.PreferenceItem.SwitchPreference(
                icon = icon,
                title = "Pause long jobs on low battery",
                subtitle = "Range translations and auto-translate ahead pause below the threshold and resume when charging",
                preference = pauseOnLow,
            ),
            Preference.PreferenceItem.SliderPreference(
                icon = icon,
                title = "Low battery threshold (%)",
                subtitle = "Battery level at which long jobs pause",
                preference = lowPercent,
                valueRange = PowerPolicy.MIN_LOW_BATTERY_PERCENT..PowerPolicy.MAX_LOW_BATTERY_PERCENT,
            ),
        )
    }

    /** Opens the spend dashboard; the summary shows this month's total. */
    @Composable
    private fun SpendingPreference(context: Context) {
        var show by remember { mutableStateOf(false) }
        var month by remember { mutableStateOf<Double?>(null) }
        LaunchedEffect(show) { if (!show) month = SpendLedger.monthTotal(context) }
        val cap = SecurePrefs.getMonthlySpendCap(context)
        TextPreferenceWidget(
            title = "Spending",
            subtitle = month?.let { m ->
                "This month: ${SpendPricing.format(m)}" + if (cap > 0) " of ${SpendPricing.format(cap)}" else ""
            } ?: "Totals per day, month, novel and model",
            icon = painterResource(R.drawable.ic_baseline_translate_24),
            onPreferenceClick = { show = true },
        )
        if (show) SpendDialog(onDismiss = { show = false })
    }

    /** A USD cap ("0" or blank = off). */
    @Composable
    private fun CapPreference(
        context: Context,
        title: String,
        description: String,
        read: (Context) -> Double,
        write: (Context, Double) -> Unit,
    ) {
        var value by remember { mutableStateOf(read(context)) }
        TextInputPreference(
            title = title,
            subtitle = (if (value > 0) SpendPricing.format(value) else "Off") + " · $description",
            initialValue = if (value > 0) String.format(java.util.Locale.US, "%.2f", value) else "",
            placeholder = "USD, e.g. 5 (empty = off)",
            onSave = { text ->
                val parsed = text.trim().removePrefix("$").replace(',', '.').ifBlank { "0" }.toDoubleOrNull()
                if (parsed == null || parsed < 0) {
                    showToast("Enter an amount in USD, e.g. 5 or 2.50")
                } else {
                    write(context, parsed)
                    value = parsed
                    showToast(if (parsed > 0) "$title: ${SpendPricing.format(parsed)}" else "$title off")
                }
            },
        )
    }

    /** "≈ $0.29 / 100 ch" for a preset, Chinese source, with the current strict-mode setting. */
    private fun per100(preset: ModelPreset, strictMode: Boolean): String =
        CostEstimator.formatPer100(CostEstimator.estimateForPreset(preset, 100, TranslationLanguages.CHINESE, strictMode))

    private fun maskedKey(key: String?): String = key?.let { "set (…${it.takeLast(4)})" } ?: "not set"

    /** API key for the selected model's provider (keys are stored per provider). */
    @Composable
    private fun ApiKeyPreference(context: Context, version: MutableState<Int>) {
        val scope = rememberCoroutineScope()
        val provider = remember(version.value) { SecurePrefs.getActiveProvider(context) }
        var savedKey by remember(version.value) { mutableStateOf(SecurePrefs.getApiKey(context, provider.id)) }
        var verifying by remember { mutableStateOf(false) }

        TextInputPreference(
            title = "API key",
            subtitle = "${provider.label}: ${maskedKey(savedKey)}",
            initialValue = savedKey ?: "",
            placeholder = provider.keyPlaceholder,
            masked = true,
            onSave = { value ->
                val key = value.trim()
                SecurePrefs.setApiKey(context, provider.id, key)
                savedKey = key.ifBlank { null }
                version.value++
                showToast(if (key.isNotBlank()) "${provider.label} API key saved" else "${provider.label} API key cleared")
            },
            extraButtonLabel = if (verifying) "Verifying…" else "Verify",
            extraButtonEnabled = !verifying,
            onExtraButton = { value ->
                val key = value.trim()
                if (key.isBlank()) {
                    showToast("Enter an API key first")
                } else {
                    verifying = true
                    scope.launch {
                        val error = try {
                            TranslationEngine(SecurePrefs.engineConfig(context).copy(apiKey = key)).verify()
                        } finally {
                            verifying = false
                        }
                        showToast(if (error == null) "API key is valid!" else "Verification failed: $error")
                    }
                }
            },
        )
    }

    /** One selectable row: radio, label, detail line and (when selected) the notes. */
    @Composable
    private fun ChoiceRow(selected: Boolean, label: String, detail: String?, notes: String?, onClick: () -> Unit) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                Text(label)
                if (!detail.isNullOrBlank()) {
                    Text(detail, style = MaterialTheme.typography.bodySmall)
                }
                if (selected && !notes.isNullOrBlank()) {
                    Text(
                        notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    @Composable
    private fun GroupLabel(text: String) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
    }

    /**
     * Model presets ([SecurePrefs.modelPresets], grouped by tier, with the cost per 100 chapters) plus
     * a custom model (free-text base URL, model id and extra request-body JSON).
     */
    @Composable
    private fun ModelPreference(context: Context, version: MutableState<Int>) {
        var showDialog by remember { mutableStateOf(false) }
        val strictMode = SecurePrefs.isStrictModeEnabled(context)
        val summary = remember(version.value, strictMode) {
            val preset = SecurePrefs.getPreset(context)
            if (preset != null) {
                "${preset.label} (${preset.provider.label}) · ${per100(preset, strictMode)} (zh)"
            } else {
                val model = SecurePrefs.getCustomModel(context)
                val estimate = CostEstimator.estimateCostForChapters(100, model, TranslationLanguages.CHINESE, strictMode)
                "Custom: $model at ${SecurePrefs.getCustomBaseUrl(context)} · ${CostEstimator.formatPer100(estimate)}"
            }
        }

        TextPreferenceWidget(
            title = "Translation model",
            subtitle = summary,
            icon = painterResource(R.drawable.ic_baseline_translate_24),
            onPreferenceClick = { showDialog = true },
        )
        if (!showDialog) return

        val custom = SecurePrefs.CUSTOM_PRESET_ID
        var selected by remember { mutableStateOf(SecurePrefs.getPresetId(context)) }
        var customUrl by remember { mutableStateOf(SecurePrefs.getCustomBaseUrl(context)) }
        var customModel by remember {
            mutableStateOf(if (SecurePrefs.isCustomModel(context)) SecurePrefs.getCustomModel(context) else "")
        }
        var customExtra by remember { mutableStateOf(SecurePrefs.getCustomExtraBodyJson(context)) }
        var customProfile by remember { mutableStateOf(SecurePrefs.getCustomPromptProfile(context)) }
        val extraValid = LlmConfig.parseExtraBodyOrNull(customExtra) != null
        val dismiss = { showDialog = false }

        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Translation model") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Cost per 100 chapters of 4,500 Chinese characters" +
                            (if (strictMode) ", strict mode on." else ".") +
                            " Each provider needs its own API key.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    PresetTier.entries.forEach { tier ->
                        val presets = SecurePrefs.modelPresets.filter { it.tier == tier }
                        if (presets.isEmpty()) return@forEach
                        GroupLabel(tier.label)
                        presets.forEach { p ->
                            ChoiceRow(
                                selected = selected == p.id,
                                label = p.label,
                                detail = "${p.provider.label} · ${per100(p, strictMode)}",
                                notes = p.notes,
                            ) { selected = p.id }
                        }
                    }
                    GroupLabel("Other")
                    ChoiceRow(
                        selected = selected == custom,
                        label = "Custom…",
                        detail = "Any OpenAI-compatible endpoint (local server, other providers)",
                        notes = null,
                    ) { selected = custom }
                    if (selected == custom) {
                        OutlinedTextField(
                            value = customUrl,
                            onValueChange = { customUrl = it },
                            label = { Text("Base URL") },
                            placeholder = { Text(LlmConfig.DEFAULT_BASE_URL) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                        OutlinedTextField(
                            value = customModel,
                            onValueChange = { customModel = it },
                            label = { Text("Model id") },
                            placeholder = { Text("e.g. glm-5.3-flash") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                        OutlinedTextField(
                            value = customExtra,
                            onValueChange = { customExtra = it },
                            label = { Text("Extra request body (JSON, optional)") },
                            placeholder = { Text("{\"reasoning_effort\":\"none\"}") },
                            isError = !extraValid,
                            supportingText = {
                                Text(
                                    if (extraValid) "Merged into every request; a null value removes a field, e.g. \"temperature\": null"
                                    else "Must be a JSON object"
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                        GroupLabel("Prompt")
                        PromptProfiles.ALL.forEach { profile ->
                            ChoiceRow(
                                selected = customProfile == profile.id,
                                label = profile.label,
                                detail = null,
                                notes = profile.description,
                            ) { customProfile = profile.id }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selected != custom || (customModel.isNotBlank() && extraValid),
                    onClick = {
                        if (selected == custom) {
                            SecurePrefs.setCustomModel(context, customUrl, customModel, customExtra)
                            SecurePrefs.setCustomPromptProfile(context, customProfile)
                        } else {
                            SecurePrefs.selectPreset(context, selected)
                        }
                        version.value++
                        dismiss()
                    }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    /** Fallback model for passages blocked by a content filter or refused (off by default). */
    @Composable
    private fun FallbackPreference(context: Context, version: MutableState<Int>) {
        var showDialog by remember { mutableStateOf(false) }
        val strictMode = SecurePrefs.isStrictModeEnabled(context)
        val summary = remember(version.value) {
            val preset = ModelPresets.byId(SecurePrefs.getFallbackPresetId(context))
            if (preset == null) {
                "Off. Re-translates passages blocked by a content filter or refused. Suggested: " +
                    ModelPresets.SUGGESTED_FALLBACKS.joinToString(" or ") { it.label }
            } else {
                val primary = SecurePrefs.engineConfig(context)
                buildString {
                    append("${preset.label} (${preset.provider.label})")
                    when {
                        preset.toEngineConfig().sameModelAs(primary) -> append(" · same as the translation model, not used")
                        SecurePrefs.getApiKey(context, preset.provider.id) == null ->
                            append(" · needs a ${preset.provider.label} API key")
                        else -> append(" · used for blocked or refused passages")
                    }
                }
            }
        }

        TextPreferenceWidget(
            title = "Fallback model",
            subtitle = summary,
            icon = painterResource(R.drawable.ic_baseline_translate_24),
            onPreferenceClick = { showDialog = true },
        )
        if (!showDialog) return

        var selected by remember { mutableStateOf(SecurePrefs.getFallbackPresetId(context)) }
        val dismiss = { showDialog = false }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Fallback model") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Used once for a passage that the translation model's provider blocks (content filter) " +
                            "or that comes back as a refusal or summary. Suggested: " +
                            ModelPresets.SUGGESTED_FALLBACKS.joinToString(" or ") { it.label } + ".",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ChoiceRow(selected = selected == null, label = "Off", detail = null, notes = null) { selected = null }
                    SecurePrefs.modelPresets.forEach { p ->
                        ChoiceRow(
                            selected = selected == p.id,
                            label = p.label,
                            detail = "${p.provider.label} · ${per100(p, strictMode)}",
                            notes = p.notes,
                        ) { selected = p.id }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    SecurePrefs.setFallbackPresetId(context, selected)
                    version.value++
                    dismiss()
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    /** Model for the "Analyze novel" pre-pass (manual and automatic); default: the translation model. */
    @Composable
    private fun AnalysisModelPreference(context: Context, version: MutableState<Int>) {
        var showDialog by remember { mutableStateOf(false) }
        val chapters = SecurePrefs.DEFAULT_AUTO_ANALYZE_CHAPTERS
        fun estimate(model: String) =
            CostEstimator.formatEstimate(CostEstimator.estimateAnalysis(chapters, model, TranslationLanguages.CHINESE))
        val summary = remember(version.value) {
            val preset = ModelPresets.byId(SecurePrefs.getAnalysisPresetId(context))
            if (preset == null) {
                "Same as the translation model"
            } else {
                buildString {
                    append("${preset.label} (${preset.provider.label}) · ${estimate(preset.model)} per $chapters-chapter analysis (zh)")
                    if (SecurePrefs.getApiKey(context, preset.provider.id) == null) {
                        append(" · needs a ${preset.provider.label} API key; the translation model is used until then")
                    }
                }
            }
        }

        TextPreferenceWidget(
            title = "Analysis model",
            subtitle = summary,
            icon = painterResource(R.drawable.ic_baseline_translate_24),
            onPreferenceClick = { showDialog = true },
        )
        if (!showDialog) return

        var selected by remember { mutableStateOf(SecurePrefs.getAnalysisPresetId(context)) }
        val dismiss = { showDialog = false }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Analysis model") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Model for Analyze novel, which builds the character sheet (names, genders, places, terms) " +
                            "from the first chapters. It runs once per novel, so a stronger model costs little. " +
                            "Cost shown for $chapters Chinese chapters.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ChoiceRow(selected = selected == null, label = "Same as translation model", detail = null, notes = null) {
                        selected = null
                    }
                    SecurePrefs.modelPresets.forEach { p ->
                        ChoiceRow(
                            selected = selected == p.id,
                            label = p.label,
                            detail = "${p.provider.label} · ${estimate(p.model)}",
                            notes = p.notes,
                        ) { selected = p.id }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    SecurePrefs.setAnalysisPresetId(context, selected)
                    version.value++
                    dismiss()
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    /** API key for the fallback model's provider; only shown when it differs from the main model's provider. */
    @Composable
    private fun FallbackKeyPreference(context: Context, version: MutableState<Int>) {
        val provider = remember(version.value) {
            ModelPresets.byId(SecurePrefs.getFallbackPresetId(context))?.provider
                ?.takeIf { it.id != SecurePrefs.getActiveProvider(context).id && it != LlmProviders.CUSTOM }
        } ?: return
        val savedKey = remember(version.value) { SecurePrefs.getApiKey(context, provider.id) }
        TextInputPreference(
            title = "Fallback API key",
            subtitle = "${provider.label}: ${maskedKey(savedKey)}",
            initialValue = savedKey ?: "",
            placeholder = provider.keyPlaceholder,
            masked = true,
            onSave = { value ->
                SecurePrefs.setApiKey(context, provider.id, value.trim())
                version.value++
                showToast(if (value.isNotBlank()) "${provider.label} API key saved" else "${provider.label} API key cleared")
            },
        )
    }

    /** A preference row that opens a single-line text dialog (optionally masked, with an extra action). */
    @Composable
    private fun TextInputPreference(
        title: String,
        subtitle: String?,
        initialValue: String,
        placeholder: String,
        onSave: (String) -> Unit,
        masked: Boolean = false,
        extraButtonLabel: String? = null,
        extraButtonEnabled: Boolean = true,
        closeOnExtraButton: Boolean = false,
        onExtraButton: (String) -> Unit = {},
    ) {
        var showDialog by remember { mutableStateOf(false) }
        TextPreferenceWidget(
            title = title,
            subtitle = subtitle,
            icon = painterResource(R.drawable.ic_baseline_translate_24),
            onPreferenceClick = { showDialog = true },
        )
        if (!showDialog) return

        var text by remember { mutableStateOf(initialValue) }
        val dismiss = { showDialog = false }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(placeholder) },
                    singleLine = true,
                    visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onSave(text)
                    dismiss()
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    if (extraButtonLabel != null) {
                        TextButton(enabled = extraButtonEnabled, onClick = {
                            onExtraButton(text)
                            if (closeOnExtraButton) dismiss()
                        }) { Text(extraButtonLabel) }
                    }
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
    }
}

/**
 * "Translate titles and synopses with AI" (default on; docs/browse.md). Self-contained so it is
 * listed in the AI Translation group with a single line.
 */
@Composable
internal fun metadataAiPreference(context: Context, icon: Painter): Preference.PreferenceItem.SwitchPreference {
    val preference = remember {
        AccessorPreferenceData(
            com.lagradost.quicknovel.translator.MetadataTranslations.KEY_AI_METADATA, true,
            { com.lagradost.quicknovel.translator.MetadataTranslations.isAiEnabled(context) },
            { com.lagradost.quicknovel.translator.MetadataTranslations.setAiEnabled(context, it) },
        )
    }
    return Preference.PreferenceItem.SwitchPreference(
        icon = icon,
        title = "Translate titles and synopses with AI",
        subtitle = "Use the translation model for titles in Browse, search and the library, and for " +
            "synopses when a novel is opened (about \$0.0002 per novel, cached). Without an API key " +
            "the free Google option above is used",
        preference = preference,
    )
}
