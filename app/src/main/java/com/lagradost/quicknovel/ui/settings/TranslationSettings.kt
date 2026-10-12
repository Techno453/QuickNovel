package com.lagradost.quicknovel.ui.settings

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.lagradost.quicknovel.CommonActivity.showToast
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.translator.ConnectionTester
import com.lagradost.quicknovel.translator.CostEstimator
import com.lagradost.quicknovel.translator.LlmConfig
import com.lagradost.quicknovel.translator.LlmProviders
import com.lagradost.quicknovel.translator.MetadataTranslations
import com.lagradost.quicknovel.translator.ModelPreset
import com.lagradost.quicknovel.translator.ModelPresets
import com.lagradost.quicknovel.translator.PowerPolicy
import com.lagradost.quicknovel.translator.PresetTier
import com.lagradost.quicknovel.translator.PromptProfiles
import com.lagradost.quicknovel.translator.SecurePrefs
import com.lagradost.quicknovel.translator.SpendLedger
import com.lagradost.quicknovel.translator.SpendPricing
import com.lagradost.quicknovel.translator.TranslationLanguages
import com.lagradost.quicknovel.translator.TranslationStyle
import com.lagradost.quicknovel.ui.common.ConnectionMessages
import com.lagradost.quicknovel.ui.setup.SetupActivity
import com.lagradost.quicknovel.ui.translate.SpendDialog
import com.lagradost.quicknovel.ui.translate.TranslationForegroundService
import com.lagradost.quicknovel.util.GoogleTranslate
import com.mihon.common.preference.PreferenceData
import com.mihon.presentation.settings.Preference
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

/**
 * Settings → AI translation, in sections (docs/ui-cohesion.md): Provider and key · Reading and
 * background jobs · Glossary and analysis · Spending and limits · Titles, synopses and search ·
 * Default translation style · Advanced. Wording comes from strings_translate.xml, shared with the
 * setup wizard; each concept has one icon.
 */
object TranslationSettings {
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

    /** Short labels for list summaries; the dialogs show the longer descriptions. */
    @Composable
    private fun shortStyleLabel(style: String): String = when (style) {
        TranslationStyle.NAME_PINYIN -> stringResource(R.string.tr_style_short_pinyin)
        TranslationStyle.NAME_MEANING -> stringResource(R.string.tr_style_short_meaning)
        TranslationStyle.NAME_MIXED -> stringResource(R.string.tr_style_short_mixed)
        TranslationStyle.NAME_FAMILY_GIVEN -> stringResource(R.string.tr_style_short_family_given)
        TranslationStyle.NAME_GIVEN_FAMILY -> stringResource(R.string.tr_style_short_given_family)
        TranslationStyle.HONORIFICS_KEEP -> stringResource(R.string.tr_style_short_keep)
        TranslationStyle.HONORIFICS_DROP -> stringResource(R.string.tr_style_short_drop)
        TranslationStyle.HONORIFICS_ADAPT -> stringResource(R.string.tr_style_short_adapt)
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
        val shortLabel: @Composable (String, Map<String, String>) -> String? = { value, _ -> shortStyleLabel(value) }
        return listOf(
            Preference.PreferenceItem.ListPreference(
                icon = icon,
                title = stringResource(R.string.tr_style_names, language),
                preference = nameStyle,
                entries = nameEntries,
                subtitleProvider = shortLabel,
            ),
            Preference.PreferenceItem.ListPreference(
                icon = icon,
                title = stringResource(R.string.tr_style_honorifics, language),
                preference = honorificStyle,
                entries = honorificEntries,
                subtitleProvider = shortLabel,
            ),
        )
    }

    /** A section header inside the group: primary colour, aligned with the row titles. */
    @Composable
    private fun section(@androidx.annotation.StringRes title: Int): Preference.PreferenceItem.CustomPreference {
        val text = stringResource(title)
        return Preference.PreferenceItem.CustomPreference(title = text, content = { SectionHeader(text) })
    }

    @Composable
    private fun icon(@DrawableRes id: Int): Painter = painterResource(id)

    /** The "AI translation" settings group. */
    @Composable
    fun preferenceGroup(): Preference.PreferenceGroup {
        val context = LocalContext.current.applicationContext
        val translateIcon = icon(R.drawable.ic_baseline_translate_24)
        val glossaryIcon = icon(R.drawable.ic_glossary_24)
        val analyzeIcon = icon(R.drawable.ic_analyze_24)
        val batteryIcon = icon(R.drawable.ic_battery_charging_24)
        val searchIcon = icon(R.drawable.ic_search_translate_24)

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
        val readAheadOff = stringResource(R.string.tr_read_ahead_off)
        val readAheadEntries = SecurePrefs.READ_AHEAD_OPTIONS.associateWith { n ->
            if (n == 0) readAheadOff else pluralStringResource(R.plurals.tr_read_ahead_chapters, n, n)
        }
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
        val styleIcon = icon(R.drawable.ic_name_style_24)
        val styleItems = STYLE_LANGUAGES.flatMap { lang -> styleDefaults(context, lang, styleIcon) }
        // Bumped whenever the model, fallback or a key changes so dependent rows re-read SecurePrefs.
        val configVersion = remember { mutableStateOf(0) }

        val monthlyCapTitle = stringResource(R.string.tr_monthly_cap)
        val monthlyCapDesc = stringResource(R.string.tr_monthly_cap_desc)
        val jobCapTitle = stringResource(R.string.tr_job_cap)
        val jobCapDesc = stringResource(R.string.tr_job_cap_desc)

        val items: List<Preference.PreferenceItem<out Any?, out Any?>> = listOf(
            // ---- Provider and key ----
            section(R.string.tr_section_provider),
            Preference.PreferenceItem.CustomPreference(
                title = stringResource(R.string.setup_open_from_settings),
                content = { SetupWizardPreference(configVersion) },
            ),
            Preference.PreferenceItem.CustomPreference(
                title = stringResource(R.string.tr_model),
                content = { ModelPreference(context, configVersion) },
            ),
            Preference.PreferenceItem.CustomPreference(
                title = stringResource(R.string.tr_api_key),
                content = { ApiKeyPreference(context, configVersion) },
            ),

            // ---- Reading and background jobs ----
            section(R.string.tr_section_reading),
            Preference.PreferenceItem.SwitchPreference(
                icon = translateIcon,
                title = stringResource(R.string.tr_show_buttons),
                subtitle = stringResource(R.string.tr_show_buttons_desc),
                preference = enabled,
            ),
            Preference.PreferenceItem.SwitchPreference(
                icon = translateIcon,
                title = stringResource(R.string.tr_on_demand),
                subtitle = stringResource(R.string.tr_on_demand_desc),
                preference = onDemand,
            ),
            Preference.PreferenceItem.ListPreference(
                icon = icon(R.drawable.ic_read_ahead_24),
                title = stringResource(R.string.tr_read_ahead),
                preference = readAhead,
                entries = readAheadEntries,
                subtitleProvider = { value, entries ->
                    if (value == 0) stringResource(R.string.tr_read_ahead_desc_off)
                    else stringResource(R.string.tr_read_ahead_desc, entries[value] ?: value.toString())
                },
                onValueChanged = { value ->
                    if (value == 0) TranslationForegroundService.stopReadAhead(context)
                    true
                },
            ),
            Preference.PreferenceItem.SwitchPreference(
                icon = batteryIcon,
                title = stringResource(R.string.tr_jobs_charging),
                subtitle = stringResource(R.string.tr_jobs_charging_desc),
                preference = onlyWhileCharging,
            ),
            Preference.PreferenceItem.SwitchPreference(
                icon = batteryIcon,
                title = stringResource(R.string.tr_jobs_low_battery),
                subtitle = stringResource(R.string.tr_jobs_low_battery_desc),
                preference = pauseOnLow,
            ),
            Preference.PreferenceItem.SliderPreference(
                icon = batteryIcon,
                title = stringResource(R.string.tr_jobs_threshold),
                subtitle = stringResource(R.string.tr_jobs_threshold_desc),
                preference = lowPercent,
                valueRange = PowerPolicy.MIN_LOW_BATTERY_PERCENT..PowerPolicy.MAX_LOW_BATTERY_PERCENT,
            ),

            // ---- Glossary and analysis ----
            section(R.string.tr_section_glossary),
            Preference.PreferenceItem.SwitchPreference(
                icon = analyzeIcon,
                title = stringResource(R.string.tr_auto_analyze),
                subtitle = stringResource(R.string.tr_auto_analyze_desc),
                preference = autoAnalyze,
            ),
            Preference.PreferenceItem.SliderPreference(
                icon = analyzeIcon,
                title = stringResource(R.string.tr_auto_analyze_count),
                subtitle = stringResource(R.string.tr_auto_analyze_count_desc),
                preference = autoAnalyzeChapters,
                valueRange = 1..SecurePrefs.MAX_ANALYZE_CHAPTERS,
            ),
            Preference.PreferenceItem.SliderPreference(
                icon = analyzeIcon,
                title = stringResource(R.string.tr_analyze_count),
                subtitle = stringResource(R.string.tr_analyze_count_desc),
                preference = analyzeChapters,
                valueRange = 1..SecurePrefs.MAX_ANALYZE_CHAPTERS,
            ),
            Preference.PreferenceItem.CustomPreference(
                title = stringResource(R.string.tr_analysis_model),
                content = { AnalysisModelPreference(context, configVersion) },
            ),
            Preference.PreferenceItem.SwitchPreference(
                icon = glossaryIcon,
                title = stringResource(R.string.tr_auto_add),
                subtitle = stringResource(R.string.tr_auto_add_desc),
                preference = autoAddTerms,
            ),

            // ---- Spending and limits ----
            section(R.string.tr_section_spending),
            Preference.PreferenceItem.CustomPreference(
                title = stringResource(R.string.tr_spending),
                content = { SpendingPreference(context) },
            ),
            Preference.PreferenceItem.CustomPreference(
                title = monthlyCapTitle,
                content = {
                    CapPreference(
                        context, monthlyCapTitle, monthlyCapDesc,
                        SecurePrefs::getMonthlySpendCap, SecurePrefs::setMonthlySpendCap,
                    )
                },
            ),
            Preference.PreferenceItem.CustomPreference(
                title = jobCapTitle,
                content = {
                    CapPreference(
                        context, jobCapTitle, jobCapDesc,
                        SecurePrefs::getPerJobCap, SecurePrefs::setPerJobCap,
                    )
                },
            ),

            // ---- Titles, synopses and search ----
            section(R.string.tr_section_titles),
            metadataAiPreference(context, icon(R.drawable.ic_titles_24)),
            Preference.PreferenceItem.SwitchPreference(
                icon = icon(R.drawable.ic_titles_24),
                title = stringResource(R.string.tr_metadata_google),
                subtitle = stringResource(R.string.tr_metadata_google_desc),
                preference = titles,
                onValueChanged = {
                    GoogleTranslate.clearCache()
                    true
                },
            ),
            Preference.PreferenceItem.SwitchPreference(
                icon = searchIcon,
                title = stringResource(R.string.tr_search_queries),
                subtitle = stringResource(R.string.tr_search_queries_desc),
                preference = searchQueries,
            ),
            Preference.PreferenceItem.SwitchPreference(
                icon = searchIcon,
                title = stringResource(R.string.tr_search_ai),
                subtitle = stringResource(R.string.tr_search_ai_desc),
                preference = aiSearch,
            ),

            // ---- Default translation style ----
            section(R.string.tr_section_style),
        ) + styleItems + listOf(
            // ---- Advanced ----
            section(R.string.tr_section_advanced),
            Preference.PreferenceItem.CustomPreference(
                title = stringResource(R.string.tr_fallback_model),
                content = { FallbackPreference(context, configVersion) },
            ),
            Preference.PreferenceItem.CustomPreference(
                title = stringResource(R.string.tr_fallback_key),
                content = { FallbackKeyPreference(context, configVersion) },
            ),
            Preference.PreferenceItem.SwitchPreference(
                icon = icon(R.drawable.ic_strict_24),
                title = stringResource(R.string.tr_strict),
                subtitle = stringResource(R.string.tr_strict_desc),
                preference = strictMode,
            ),
            Preference.PreferenceItem.SwitchPreference(
                icon = icon(R.drawable.ic_story_context_24),
                title = stringResource(R.string.tr_carry_context),
                subtitle = stringResource(R.string.tr_carry_context_desc),
                preference = carryContext,
            ),
        )

        return Preference.PreferenceGroup(
            title = stringResource(R.string.tr_settings_group),
            preferenceItems = items.toPersistentList(),
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
            title = stringResource(R.string.tr_spending),
            subtitle = month?.let { m ->
                if (cap > 0) stringResource(R.string.tr_spending_month_cap, SpendPricing.format(m), SpendPricing.format(cap))
                else stringResource(R.string.tr_spending_month, SpendPricing.format(m))
            } ?: stringResource(R.string.tr_spending_desc),
            icon = painterResource(R.drawable.ic_spend_24),
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
            subtitle = stringResource(
                R.string.tr_cap_summary,
                if (value > 0) SpendPricing.format(value) else stringResource(R.string.tr_cap_off),
                description,
            ),
            icon = painterResource(R.drawable.ic_spend_24),
            initialValue = if (value > 0) String.format(java.util.Locale.US, "%.2f", value) else "",
            placeholder = stringResource(R.string.tr_cap_hint),
            onSave = { text ->
                val parsed = text.trim().removePrefix("$").replace(',', '.').ifBlank { "0" }.toDoubleOrNull()
                if (parsed == null || parsed < 0) {
                    showToast(context.getString(R.string.tr_cap_invalid))
                } else {
                    write(context, parsed)
                    value = parsed
                    showToast(
                        if (parsed > 0) context.getString(R.string.tr_cap_set_toast, title, SpendPricing.format(parsed))
                        else context.getString(R.string.tr_cap_off_toast, title)
                    )
                }
            },
        )
    }

    /** "≈ $0.29 / 100 ch" for a preset, Chinese source, with the current strict-mode setting. */
    private fun per100(preset: ModelPreset, strictMode: Boolean): String =
        CostEstimator.formatPer100(CostEstimator.estimateForPreset(preset, 100, TranslationLanguages.CHINESE, strictMode))

    @Composable
    private fun maskedKey(key: String?): String =
        key?.let { stringResource(R.string.tr_key_saved_short, it.takeLast(4)) } ?: stringResource(R.string.tr_key_not_set)

    @Composable
    private fun suggestedFallbacks(): String {
        val labels = ModelPresets.SUGGESTED_FALLBACKS.map { it.label }
        return if (labels.size == 2) stringResource(R.string.tr_or, labels[0], labels[1]) else labels.joinToString(", ")
    }

    /** Opens the step-by-step setup wizard; rows re-read their settings when it closes. */
    @Composable
    private fun SetupWizardPreference(version: MutableState<Int>) {
        val activityContext = LocalContext.current
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            version.value++
        }
        TextPreferenceWidget(
            title = stringResource(R.string.setup_open_from_settings),
            subtitle = stringResource(R.string.setup_open_from_settings_desc),
            icon = painterResource(R.drawable.ic_setup_24),
            onPreferenceClick = { launcher.launch(SetupActivity.intent(activityContext, firstRun = false)) },
        )
    }

    /** API key for the selected model's provider (keys are stored per provider). */
    @Composable
    private fun ApiKeyPreference(context: Context, version: MutableState<Int>) {
        Column {
            ApiKeyRow(context, version)
            if (SecurePrefs.isUsingPlainFallback(context)) {
                Text(
                    stringResource(R.string.setup_key_plain_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 64.dp, end = 16.dp, bottom = 8.dp),
                )
            }
        }
    }

    @Composable
    private fun ApiKeyRow(context: Context, version: MutableState<Int>) {
        val scope = rememberCoroutineScope()
        val provider = remember(version.value) { SecurePrefs.getActiveProvider(context) }
        var savedKey by remember(version.value) { mutableStateOf(SecurePrefs.getApiKey(context, provider.id)) }
        var testing by remember { mutableStateOf(false) }

        TextInputPreference(
            title = stringResource(R.string.tr_api_key),
            subtitle = stringResource(R.string.tr_api_key_summary, provider.label, maskedKey(savedKey)),
            icon = painterResource(R.drawable.ic_api_key_24),
            initialValue = savedKey ?: "",
            placeholder = provider.keyPlaceholder,
            masked = true,
            onSave = { value ->
                val key = value.trim()
                SecurePrefs.setApiKey(context, provider.id, key)
                savedKey = key.ifBlank { null }
                version.value++
                showToast(
                    context.getString(
                        if (key.isNotBlank()) R.string.tr_key_saved_toast else R.string.tr_key_cleared_toast,
                        provider.label,
                    )
                )
            },
            // Same test and wording as the setup wizard's "Test the connection" page.
            extraButtonLabel = stringResource(if (testing) R.string.setup_test_running else R.string.setup_test_run),
            extraButtonEnabled = !testing,
            onExtraButton = { value ->
                val key = value.trim()
                if (key.isBlank()) {
                    showToast(context.getString(R.string.tr_key_enter_first))
                } else {
                    testing = true
                    scope.launch {
                        // Free check (one GET), never a paid completion; see ConnectionTester.
                        val result = try {
                            ConnectionTester.test(provider, key)
                        } finally {
                            testing = false
                        }
                        showToast(ConnectionMessages.messageWithDetail(context, result, provider.label))
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

    /** The settings dialogs' shared buttons: Cancel, then the confirm action (default "OK"). */
    @Composable
    private fun DialogButtons(onDismiss: () -> Unit) =
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }

    /**
     * Model presets ([SecurePrefs.modelPresets], grouped by tier, with the cost per 100 chapters) plus
     * a custom model (free-text base URL, model id and extra request-body JSON).
     */
    @Composable
    private fun ModelPreference(context: Context, version: MutableState<Int>) {
        var showDialog by remember { mutableStateOf(false) }
        val strictMode = SecurePrefs.isStrictModeEnabled(context)
        val preset = remember(version.value) { SecurePrefs.getPreset(context) }
        val summary = if (preset != null) {
            stringResource(R.string.tr_model_summary, preset.label, preset.provider.label, per100(preset, strictMode))
        } else {
            val model = remember(version.value) { SecurePrefs.getCustomModel(context) }
            val estimate = CostEstimator.estimateCostForChapters(100, model, TranslationLanguages.CHINESE, strictMode)
            stringResource(
                R.string.tr_model_custom_summary, model, SecurePrefs.getCustomBaseUrl(context),
                CostEstimator.formatPer100(estimate),
            )
        }

        TextPreferenceWidget(
            title = stringResource(R.string.tr_model),
            subtitle = summary,
            icon = painterResource(R.drawable.ic_model_24),
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
            title = { Text(stringResource(R.string.tr_model)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        stringResource(if (strictMode) R.string.tr_model_dialog_note_strict else R.string.tr_model_dialog_note) +
                            " " + stringResource(R.string.tr_model_dialog_keys),
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
                    GroupLabel(stringResource(R.string.tr_model_other))
                    ChoiceRow(
                        selected = selected == custom,
                        label = stringResource(R.string.tr_model_custom),
                        detail = stringResource(R.string.tr_model_custom_desc),
                        notes = null,
                    ) { selected = custom }
                    if (selected == custom) {
                        OutlinedTextField(
                            value = customUrl,
                            onValueChange = { customUrl = it },
                            label = { Text(stringResource(R.string.tr_model_base_url)) },
                            placeholder = { Text(LlmConfig.DEFAULT_BASE_URL) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                        OutlinedTextField(
                            value = customModel,
                            onValueChange = { customModel = it },
                            label = { Text(stringResource(R.string.tr_model_id)) },
                            placeholder = { Text(stringResource(R.string.tr_model_id_example)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                        OutlinedTextField(
                            value = customExtra,
                            onValueChange = { customExtra = it },
                            label = { Text(stringResource(R.string.tr_model_extra_body)) },
                            placeholder = { Text("{\"reasoning_effort\":\"none\"}") },
                            isError = !extraValid,
                            supportingText = {
                                Text(
                                    stringResource(
                                        if (extraValid) R.string.tr_model_extra_body_help else R.string.tr_model_extra_body_invalid
                                    )
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                        GroupLabel(stringResource(R.string.tr_model_prompt))
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
            dismissButton = { DialogButtons(dismiss) },
        )
    }

    /** Fallback model for passages blocked by a content filter or refused (off by default). */
    @Composable
    private fun FallbackPreference(context: Context, version: MutableState<Int>) {
        var showDialog by remember { mutableStateOf(false) }
        val strictMode = SecurePrefs.isStrictModeEnabled(context)
        val preset = remember(version.value) { ModelPresets.byId(SecurePrefs.getFallbackPresetId(context)) }
        val suggested = suggestedFallbacks()
        val summary = if (preset == null) {
            stringResource(R.string.tr_fallback_off, suggested)
        } else {
            val name = "${preset.label} (${preset.provider.label})"
            when {
                preset.toEngineConfig().sameModelAs(SecurePrefs.engineConfig(context)) ->
                    stringResource(R.string.tr_fallback_same, name)
                SecurePrefs.getApiKey(context, preset.provider.id) == null ->
                    stringResource(R.string.tr_fallback_needs_key, name, preset.provider.label)
                else -> stringResource(R.string.tr_fallback_used, name)
            }
        }

        TextPreferenceWidget(
            title = stringResource(R.string.tr_fallback_model),
            subtitle = summary,
            icon = painterResource(R.drawable.ic_fallback_24),
            onPreferenceClick = { showDialog = true },
        )
        if (!showDialog) return

        var selected by remember { mutableStateOf(SecurePrefs.getFallbackPresetId(context)) }
        val dismiss = { showDialog = false }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(R.string.tr_fallback_model)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.tr_fallback_dialog_note, suggested), style = MaterialTheme.typography.bodySmall)
                    ChoiceRow(selected = selected == null, label = stringResource(R.string.tr_cap_off), detail = null, notes = null) {
                        selected = null
                    }
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
            dismissButton = { DialogButtons(dismiss) },
        )
    }

    /** Model for the "Analyze novel" pre-pass (manual and automatic); default: the translation model. */
    @Composable
    private fun AnalysisModelPreference(context: Context, version: MutableState<Int>) {
        var showDialog by remember { mutableStateOf(false) }
        val chapters = SecurePrefs.DEFAULT_AUTO_ANALYZE_CHAPTERS
        fun estimate(model: String) =
            CostEstimator.formatEstimate(CostEstimator.estimateAnalysis(chapters, model, TranslationLanguages.CHINESE))
        val preset = remember(version.value) { ModelPresets.byId(SecurePrefs.getAnalysisPresetId(context)) }
        val summary = if (preset == null) {
            stringResource(R.string.tr_same_as_translation_model)
        } else {
            val line = stringResource(
                R.string.tr_analysis_model_summary, preset.label, preset.provider.label, estimate(preset.model), chapters
            )
            if (SecurePrefs.getApiKey(context, preset.provider.id) == null) {
                stringResource(R.string.tr_analysis_model_needs_key, line, preset.provider.label)
            } else line
        }

        TextPreferenceWidget(
            title = stringResource(R.string.tr_analysis_model),
            subtitle = summary,
            icon = painterResource(R.drawable.ic_model_24),
            onPreferenceClick = { showDialog = true },
        )
        if (!showDialog) return

        var selected by remember { mutableStateOf(SecurePrefs.getAnalysisPresetId(context)) }
        val dismiss = { showDialog = false }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(R.string.tr_analysis_model)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.tr_analysis_model_dialog_note, chapters), style = MaterialTheme.typography.bodySmall)
                    ChoiceRow(
                        selected = selected == null,
                        label = stringResource(R.string.tr_same_as_translation_model),
                        detail = null,
                        notes = null,
                    ) { selected = null }
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
            dismissButton = { DialogButtons(dismiss) },
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
            title = stringResource(R.string.tr_fallback_key),
            subtitle = stringResource(R.string.tr_api_key_summary, provider.label, maskedKey(savedKey)),
            icon = painterResource(R.drawable.ic_api_key_24),
            initialValue = savedKey ?: "",
            placeholder = provider.keyPlaceholder,
            masked = true,
            onSave = { value ->
                SecurePrefs.setApiKey(context, provider.id, value.trim())
                version.value++
                showToast(
                    context.getString(
                        if (value.isNotBlank()) R.string.tr_key_saved_toast else R.string.tr_key_cleared_toast,
                        provider.label,
                    )
                )
            },
        )
    }

    /** A preference row that opens a single-line text dialog (optionally masked, with an extra action). */
    @Composable
    private fun TextInputPreference(
        title: String,
        subtitle: String?,
        icon: Painter,
        initialValue: String,
        placeholder: String,
        onSave: (String) -> Unit,
        masked: Boolean = false,
        extraButtonLabel: String? = null,
        extraButtonEnabled: Boolean = true,
        onExtraButton: (String) -> Unit = {},
    ) {
        var showDialog by remember { mutableStateOf(false) }
        TextPreferenceWidget(
            title = title,
            subtitle = subtitle,
            icon = icon,
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
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                Row {
                    if (extraButtonLabel != null) {
                        TextButton(enabled = extraButtonEnabled, onClick = { onExtraButton(text) }) { Text(extraButtonLabel) }
                    }
                    DialogButtons(dismiss)
                }
            },
        )
    }
}

/** Section header inside the AI translation group, aligned with the row titles (16 + 24 + 8 + 16 dp). */
@Composable
internal fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 64.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

/**
 * "Translate titles and synopses with AI" (default on; docs/browse.md). Self-contained so it is
 * listed in the AI translation group with a single line.
 */
@Composable
internal fun metadataAiPreference(context: Context, icon: Painter): Preference.PreferenceItem.SwitchPreference {
    val preference = remember {
        AccessorPreferenceData(
            MetadataTranslations.KEY_AI_METADATA, true,
            { MetadataTranslations.isAiEnabled(context) },
            { MetadataTranslations.setAiEnabled(context, it) },
        )
    }
    return Preference.PreferenceItem.SwitchPreference(
        icon = icon,
        title = stringResource(R.string.tr_metadata_ai),
        subtitle = stringResource(R.string.tr_metadata_ai_desc),
        preference = preference,
    )
}
