package com.lagradost.quicknovel.ui.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.lagradost.quicknovel.translator.LlmConfig
import com.lagradost.quicknovel.translator.SecurePrefs
import com.lagradost.quicknovel.translator.TranslationEngine
import com.lagradost.quicknovel.translator.TranslationLanguages
import com.lagradost.quicknovel.translator.TranslationStyle
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
        val styleItems = STYLE_LANGUAGES.flatMap { lang -> styleDefaults(context, lang, translateIcon) }

        val items: List<Preference.PreferenceItem<out Any?, out Any?>> = listOf(
            Preference.PreferenceItem.CustomPreference(
                title = "API key",
                content = { ApiKeyPreference(context) },
            ),
            Preference.PreferenceItem.CustomPreference(
                title = "API base URL",
                content = { BaseUrlPreference(context) },
            ),
            Preference.PreferenceItem.CustomPreference(
                title = "Translation model",
                content = { ModelPreference(context) },
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
        ) + styleItems

        return Preference.PreferenceGroup(
            title = GROUP_TITLE,
            preferenceItems = items.toPersistentList(),
        )
    }

    @Composable
    private fun ApiKeyPreference(context: Context) {
        val scope = rememberCoroutineScope()
        var savedKey by remember { mutableStateOf(SecurePrefs.getApiKey(context)) }
        var verifying by remember { mutableStateOf(false) }

        TextInputPreference(
            title = "API key",
            subtitle = savedKey?.let { "Set (…${it.takeLast(4)})" } ?: "Not set",
            initialValue = savedKey ?: "",
            placeholder = "sk-...",
            masked = true,
            onSave = { value ->
                val key = value.trim()
                SecurePrefs.setApiKey(context, key)
                savedKey = key.ifBlank { null }
                showToast(if (key.isNotBlank()) "API key saved" else "API key cleared")
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
                            TranslationEngine(
                                key,
                                SecurePrefs.getModel(context),
                                SecurePrefs.getBaseUrl(context),
                            ).verify()
                        } finally {
                            verifying = false
                        }
                        showToast(if (error == null) "API key is valid!" else "Verification failed: $error")
                    }
                }
            },
        )
    }

    @Composable
    private fun BaseUrlPreference(context: Context) {
        var baseUrl by remember { mutableStateOf(SecurePrefs.getBaseUrl(context)) }
        TextInputPreference(
            title = "API base URL",
            subtitle = baseUrl + if (LlmConfig.isDefaultBaseUrl(baseUrl)) " (OpenAI)" else "",
            initialValue = baseUrl,
            placeholder = LlmConfig.DEFAULT_BASE_URL,
            onSave = { value ->
                SecurePrefs.setBaseUrl(context, value)
                baseUrl = SecurePrefs.getBaseUrl(context)
            },
            extraButtonLabel = "Reset",
            onExtraButton = {
                SecurePrefs.setBaseUrl(context, null)
                baseUrl = SecurePrefs.getBaseUrl(context)
            },
            closeOnExtraButton = true,
        )
    }

    /** Model presets ([SecurePrefs.modelPresets]) plus a free-text custom model. */
    @Composable
    private fun ModelPreference(context: Context) {
        var model by remember { mutableStateOf(SecurePrefs.getModel(context)) }
        var showDialog by remember { mutableStateOf(false) }
        val presets = SecurePrefs.modelPresets
        val label = presets.firstOrNull { it.first == model }?.second ?: "Custom: $model"

        TextPreferenceWidget(
            title = "Translation model",
            subtitle = label,
            icon = painterResource(R.drawable.ic_baseline_translate_24),
            onPreferenceClick = { showDialog = true },
        )
        if (!showDialog) return

        val isPreset = presets.any { it.first == model }
        var selected by remember { mutableStateOf(if (isPreset) model else null) }
        var custom by remember { mutableStateOf(if (isPreset) "" else model) }
        val dismiss = { showDialog = false }

        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Translation model") },
            text = {
                Column {
                    presets.forEach { (id, name) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected = id }
                        ) {
                            RadioButton(selected = selected == id, onClick = { selected = id })
                            Text(name)
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = null }
                    ) {
                        RadioButton(selected = selected == null, onClick = { selected = null })
                        Text("Custom…")
                    }
                    if (selected == null) {
                        OutlinedTextField(
                            value = custom,
                            onValueChange = { custom = it },
                            placeholder = { Text("e.g. deepseek-chat") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selected != null || custom.isNotBlank(),
                    onClick = {
                        SecurePrefs.setModel(context, selected ?: custom.trim())
                        model = SecurePrefs.getModel(context)
                        dismiss()
                    }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) }
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
