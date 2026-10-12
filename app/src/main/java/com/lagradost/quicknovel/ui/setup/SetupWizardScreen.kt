package com.lagradost.quicknovel.ui.setup

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.compose.centeredGutter
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.translator.ConnectionResult
import com.lagradost.quicknovel.translator.ConnectionStatus
import com.lagradost.quicknovel.translator.ConnectionTester
import com.lagradost.quicknovel.translator.CostEstimator
import com.lagradost.quicknovel.translator.ModelPresets
import com.lagradost.quicknovel.translator.SecurePrefs
import com.lagradost.quicknovel.translator.TranslationLanguages
import com.lagradost.quicknovel.ui.common.ConnectionMessages
import com.lagradost.quicknovel.ui.translate.SpendDialog
import com.lagradost.quicknovel.ui.translate.TranslationForegroundService
import com.lagradost.quicknovel.updates.LibraryUpdateScheduler
import com.lagradost.quicknovel.updates.LibraryUpdateSettings
import com.lagradost.quicknovel.updates.UpdateFrequency
import com.lagradost.quicknovel.util.AdaptiveLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Saved instead of a provider name when the user picks "Later". */
private const val LATER = "later"

/**
 * The setup wizard: welcome + languages, provider and key, connection test, model, done.
 * Every page can be skipped; [onClose] is called after the "completed" flag is set.
 */
@Composable
fun SetupWizardScreen(onClose: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()

    var stepName by rememberSaveable { mutableStateOf(SetupStep.WELCOME.name) }
    var languagesCsv by rememberSaveable {
        mutableStateOf(SetupWizard.getReadingLanguages(context).joinToString(","))
    }
    var providerName by rememberSaveable {
        mutableStateOf(
            (SetupProvider.forProviderId(SecurePrefs.getActiveProvider(context).id) ?: SetupProvider.OPENAI).name
        )
    }
    var modelId by rememberSaveable { mutableStateOf<String?>(null) }
    // Never in saved state: the key only lives in memory until it is stored in SecurePrefs.
    var keyText by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf<ConnectionResult?>(null) }
    var testedKey by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var showSpend by remember { mutableStateOf(false) }

    val step = SetupStep.valueOf(stepName)
    val languages = languagesCsv.split(',').filter { it.isNotBlank() }.toSet()
    val provider = if (providerName == LATER) null else SetupProvider.valueOf(providerName)
    val steps = SetupChoices.steps(provider)
    val index = steps.indexOf(step).coerceAtLeast(0)
    val savedKey = provider?.let { SecurePrefs.getApiKey(context, it.provider.id) }
    val effectiveKey = keyText.ifBlank { null } ?: savedKey

    val plainFallback by produceState(false) {
        value = withContext(Dispatchers.IO) { SecurePrefs.isUsingPlainFallback(context) }
    }

    fun complete() {
        SecurePrefs.setSetupCompleted(context, true)
    }

    fun runTest() {
        val p = provider ?: return
        val key = effectiveKey ?: return
        testing = true
        scope.launch {
            val result = ConnectionTester.test(p.provider, key)
            testResult = result
            testedKey = key
            testing = false
        }
    }

    /** Stores what the current page collected, then moves on. */
    fun next() {
        when (step) {
            SetupStep.WELCOME -> SetupWizard.setReadingLanguages(context, languages)
            SetupStep.KEY -> if (provider != null) {
                if (keyText.isNotBlank()) SecurePrefs.setApiKey(context, provider.provider.id, keyText)
                // Point the translation model at the chosen provider so the key is the one used.
                if (SecurePrefs.getActiveProvider(context).id != provider.provider.id || SecurePrefs.isCustomModel(context)) {
                    SetupChoices.initialModel(provider, null)?.let { SecurePrefs.selectPreset(context, it.id) }
                }
            }
            SetupStep.MODEL -> (modelId ?: SetupChoices.initialModel(provider, SecurePrefs.getPresetId(context))?.id)
                ?.let { SecurePrefs.selectPreset(context, it) }
            else -> Unit
        }
        val following = steps.getOrNull(index + 1)
        if (following == null) {
            complete()
            onClose()
        } else {
            stepName = following.name
        }
    }

    fun back() {
        steps.getOrNull(index - 1)?.let { stepName = it.name }
    }

    BackHandler {
        if (index > 0) back() else {
            complete() // leaving from the first page counts as Skip
            onClose()
        }
    }

    // The test runs once on entering its page (free request), and again after the key changes.
    LaunchedEffect(step, effectiveKey) {
        if (step == SetupStep.TEST && effectiveKey != null && effectiveKey != testedKey && !testing) runTest()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            val gutter = centeredGutter(minGutter = 16.dp, maxContent = AdaptiveLayout.MAX_SHEET_WIDTH_DP.dp)
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp) // same height with and without Skip
                        .padding(start = gutter, end = gutter - 8.dp, top = 4.dp),
                ) {
                    Text(
                        stringResource(R.string.setup_step_of, index + 1, steps.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (step != SetupStep.DONE) {
                        TextButton(onClick = {
                            complete()
                            onClose()
                        }) { Text(stringResource(R.string.setup_skip)) }
                    }
                }
                LinearProgressIndicator(
                    progress = { (index + 1f) / steps.size },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = gutter),
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = gutter, vertical = 16.dp),
                ) {
                    when (step) {
                        SetupStep.WELCOME -> WelcomePage(languages) { languagesCsv = it.joinToString(",") }
                        SetupStep.KEY -> KeyPage(
                            context = context,
                            provider = provider,
                            onProvider = { p ->
                                providerName = p?.name ?: LATER
                                keyText = ""
                                modelId = null
                                testResult = null
                                testedKey = null
                            },
                            keyText = keyText,
                            onKeyText = { keyText = it },
                            savedKey = savedKey,
                            plainFallback = plainFallback,
                        )
                        SetupStep.TEST -> TestPage(
                            provider = provider,
                            hasKey = effectiveKey != null,
                            testing = testing,
                            result = testResult.takeIf { testedKey == effectiveKey },
                            onTest = ::runTest,
                            onFixKey = ::back,
                        )
                        SetupStep.MODEL -> {
                            val selected = modelId ?: SetupChoices.initialModel(provider, SecurePrefs.getPresetId(context))?.id
                            ModelPage(
                                provider = provider,
                                selectedId = selected,
                                lang = SetupChoices.pricingLanguage(languages),
                                strictMode = SecurePrefs.isStrictModeEnabled(context),
                            ) { modelId = it }
                        }
                        SetupStep.DONE -> DonePage(
                            context = context,
                            languages = languages,
                            provider = provider,
                            savedKey = savedKey,
                            testResult = testResult.takeIf { testedKey != null && testedKey == savedKey },
                            onSpending = { showSpend = true },
                            onSettings = {
                                complete()
                                onOpenSettings()
                            },
                        )
                    }
                }
                HorizontalDivider()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = gutter, vertical = 12.dp),
                ) {
                    if (index > 0) {
                        OutlinedButton(onClick = ::back) { Text(stringResource(R.string.setup_back)) }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Button(onClick = ::next) {
                        Text(stringResource(if (step == SetupStep.DONE) R.string.setup_finish else R.string.setup_next))
                    }
                }
            }
        }
    }

    if (showSpend) SpendDialog(onDismiss = { showSpend = false })
}

@Composable
private fun PageTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Body(text: String, muted: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun Warning(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun WelcomePage(languages: Set<String>, onLanguages: (Set<String>) -> Unit) {
    PageTitle(stringResource(R.string.setup_welcome_title))
    Body(stringResource(R.string.setup_welcome_body))
    Body(stringResource(R.string.setup_welcome_cost), muted = true)
    Spacer(modifier = Modifier.height(4.dp))
    Text(stringResource(R.string.setup_languages_title), style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SetupChoices.READING_LANGUAGES.forEach { lang ->
            val selected = lang in languages
            FilterChip(
                selected = selected,
                onClick = { onLanguages(if (selected) languages - lang else languages + lang) },
                label = { Text(TranslationLanguages.displayName(lang)) },
                leadingIcon = if (selected) {
                    { Icon(painterResource(R.drawable.ic_baseline_check_24), contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else null,
            )
        }
    }
    Body(stringResource(R.string.setup_languages_hint), muted = true)
    if (languages.isEmpty()) Warning(stringResource(R.string.setup_languages_none))
}

/** A radio row in an outlined card: title, description, optional extra lines. */
@Composable
private fun ChoiceCard(
    selected: Boolean,
    onClick: () -> Unit,
    title: String,
    description: String?,
    overline: String? = null,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    OutlinedCard(
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                if (overline != null) {
                    Text(overline, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (description != null) {
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                extra()
            }
        }
    }
}

private fun providerDescription(provider: SetupProvider): Int = when (provider) {
    SetupProvider.OPENAI -> R.string.setup_provider_openai_desc
    SetupProvider.OPENROUTER -> R.string.setup_provider_openrouter_desc
    SetupProvider.GOOGLE -> R.string.setup_provider_google_desc
}

private fun providerName(provider: SetupProvider): Int = when (provider) {
    SetupProvider.OPENAI -> R.string.setup_provider_openai
    SetupProvider.OPENROUTER -> R.string.setup_provider_openrouter
    SetupProvider.GOOGLE -> R.string.setup_provider_google
}

@Composable
private fun KeyPage(
    context: Context,
    provider: SetupProvider?,
    onProvider: (SetupProvider?) -> Unit,
    keyText: String,
    onKeyText: (String) -> Unit,
    savedKey: String?,
    plainFallback: Boolean,
) {
    PageTitle(stringResource(R.string.setup_provider_title))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.selectableGroup()) {
        SetupProvider.entries.forEach { p ->
            ChoiceCard(
                selected = provider == p,
                onClick = { if (provider != p) onProvider(p) },
                title = stringResource(providerName(p)),
                description = stringResource(providerDescription(p)),
            )
        }
        ChoiceCard(
            selected = provider == null,
            onClick = { if (provider != null) onProvider(null) },
            title = stringResource(R.string.setup_provider_later),
            description = stringResource(R.string.setup_provider_later_desc),
        )
    }
    if (provider == null) return

    var showKey by remember { mutableStateOf(false) }
    val label = provider.provider.label
    Spacer(modifier = Modifier.height(4.dp))
    OutlinedTextField(
        value = keyText,
        // API keys never contain whitespace; this also drops a newline pasted with the key.
        onValueChange = { onKeyText(it.filterNot(Char::isWhitespace)) },
        label = { Text(stringResource(R.string.setup_key_label, label)) },
        placeholder = { Text(provider.provider.keyPlaceholder) },
        singleLine = true,
        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    if (plainFallback) Warning(stringResource(R.string.setup_key_plain_warning))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { showKey = !showKey }) {
            Text(stringResource(if (showKey) R.string.setup_key_hide else R.string.setup_key_show))
        }
        OutlinedButton(onClick = {
            clipboardText(context)?.let { onKeyText(it.filterNot(Char::isWhitespace)) }
        }) { Text(stringResource(R.string.setup_key_paste)) }
    }
    if (savedKey != null && keyText.isBlank()) {
        Body(stringResource(R.string.setup_key_saved, savedKey.takeLast(4)), muted = true)
    }
    Body(stringResource(R.string.setup_key_where), muted = true)
    val activityContext = LocalContext.current
    TextButton(onClick = { openUrl(activityContext, provider.keyPageUrl) }) {
        Icon(painterResource(R.drawable.ic_baseline_open_in_new_24), contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(stringResource(R.string.setup_key_get, label))
    }
    if (savedKey == null && keyText.isBlank()) Body(stringResource(R.string.setup_key_missing), muted = true)
}

private fun clipboardText(context: Context): String? = try {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
} catch (t: Throwable) {
    logError(t)
    null
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri()))
    } catch (t: Throwable) {
        Toast.makeText(context, context.getString(R.string.setup_key_open_failed, url), Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun TestPage(
    provider: SetupProvider?,
    hasKey: Boolean,
    testing: Boolean,
    result: ConnectionResult?,
    onTest: () -> Unit,
    onFixKey: () -> Unit,
) {
    PageTitle(stringResource(R.string.setup_test_title))
    Body(stringResource(R.string.setup_test_body), muted = true)
    val label = provider?.provider?.label ?: return
    if (!hasKey) {
        Warning(stringResource(R.string.setup_test_no_key))
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onTest, enabled = !testing) {
            Text(stringResource(if (result == null) R.string.setup_test_run else R.string.setup_test_again))
        }
        if (testing) {
            Spacer(modifier = Modifier.width(12.dp))
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(8.dp))
            Body(stringResource(R.string.setup_test_running), muted = true)
        }
    }
    if (result == null || testing) return
    val ok = result.status == ConnectionStatus.OK
    // Same wording as Settings → AI translation → API key → Test connection.
    val message = stringResource(ConnectionMessages.messageRes(result.status, result.httpCode), label)
    OutlinedCard(
        border = BorderStroke(1.dp, if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                message,
                style = MaterialTheme.typography.bodyLarge,
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            val detail = ConnectionMessages.detail(result)
            if (!ok && detail.isNotBlank()) {
                Text(
                    stringResource(R.string.setup_test_detail, detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (result.status == ConnectionStatus.INVALID_KEY) {
                TextButton(onClick = onFixKey) { Text(stringResource(R.string.setup_test_fix_key)) }
            }
        }
    }
}

private fun roleLabel(role: SetupModelRole): Int = when (role) {
    SetupModelRole.DEFAULT -> R.string.setup_model_role_default
    SetupModelRole.QUALITY -> R.string.setup_model_role_quality
    SetupModelRole.GOOGLE_CHEAP -> R.string.setup_model_role_google
}

private fun roleNote(role: SetupModelRole): Int = when (role) {
    SetupModelRole.DEFAULT -> R.string.setup_model_note_default
    SetupModelRole.QUALITY -> R.string.setup_model_note_quality
    SetupModelRole.GOOGLE_CHEAP -> R.string.setup_model_note_google
}

@Composable
private fun ModelPage(
    provider: SetupProvider?,
    selectedId: String?,
    lang: String,
    strictMode: Boolean,
    onSelect: (String) -> Unit,
) {
    PageTitle(stringResource(R.string.setup_model_title))
    Body(stringResource(R.string.setup_model_body, TranslationLanguages.displayName(lang)), muted = true)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.selectableGroup()) {
        SetupChoices.modelChoices(provider).forEach { choice ->
            val estimate = CostEstimator.formatPer100(SetupChoices.estimatePer100(choice.preset, lang, strictMode))
            ChoiceCard(
                selected = selectedId == choice.preset.id,
                onClick = { onSelect(choice.preset.id) },
                overline = stringResource(roleLabel(choice.role)),
                title = choice.preset.label,
                description = stringResource(
                    R.string.setup_model_cost, estimate, String.format(Locale.US, "%.2f", choice.measuredUsdPer100)
                ),
            ) {
                Text(stringResource(roleNote(choice.role)), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Body(stringResource(R.string.setup_model_estimate_note), muted = true)
}

@Composable
private fun DonePage(
    context: Context,
    languages: Set<String>,
    provider: SetupProvider?,
    savedKey: String?,
    testResult: ConnectionResult?,
    onSpending: () -> Unit,
    onSettings: () -> Unit,
) {
    PageTitle(stringResource(R.string.setup_done_title))
    val lang = SetupChoices.pricingLanguage(languages)
    val preset = SecurePrefs.getPreset(context)
    val languageNames = SetupChoices.READING_LANGUAGES.filter { it in languages }
        .joinToString(", ") { TranslationLanguages.displayName(it) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (languageNames.isNotEmpty()) Body(stringResource(R.string.setup_done_languages, languageNames))
        if (provider == null) {
            Body(stringResource(R.string.setup_done_provider_later))
        } else {
            Body(stringResource(R.string.setup_done_provider, provider.provider.label))
            Body(
                if (savedKey != null) stringResource(R.string.setup_done_key_saved, savedKey.takeLast(4))
                else stringResource(R.string.setup_done_key_missing)
            )
            if (savedKey != null) {
                val connection = when {
                    testResult == null -> stringResource(R.string.setup_done_connection_untested)
                    testResult.status == ConnectionStatus.OK -> stringResource(R.string.setup_done_connection_ok)
                    else -> stringResource(
                        R.string.setup_done_connection_failed,
                        testResult.status.name.lowercase(Locale.ROOT).replace('_', ' ')
                    )
                }
                Body(stringResource(R.string.setup_done_connection, connection))
            }
            if (preset != null) {
                val cost = CostEstimator.formatPer100(SetupChoices.estimatePer100(preset, lang, SecurePrefs.isStrictModeEnabled(context)))
                Body(stringResource(R.string.setup_done_model, preset.label, cost))
            }
        }
    }

    Spacer(modifier = Modifier.height(4.dp))
    Text(stringResource(R.string.setup_done_background_title), style = MaterialTheme.typography.titleMedium)

    // The same switch as Settings → AI translation → "Translate chapters when opened" (off by
    // default): without it the reader shows the original and read ahead has nothing to follow.
    var onDemand by remember { mutableStateOf(SecurePrefs.isTranslateOnDemandEnabled(context)) }
    ToggleRow(
        title = stringResource(R.string.tr_on_demand),
        description = stringResource(R.string.tr_on_demand_desc),
        checked = onDemand,
    ) { on ->
        SecurePrefs.setTranslateOnDemandEnabled(context, on)
        onDemand = on
    }

    var readAhead by remember { mutableStateOf(SecurePrefs.getReadAheadChapters(context)) }
    val readAheadPreset = preset ?: ModelPresets.DEFAULT
    val perChapter = CostEstimator.estimateForPreset(readAheadPreset, 1, lang, SecurePrefs.isStrictModeEnabled(context))
        .usd?.let { CostEstimator.formatCost(it) } ?: "?"
    ToggleRow(
        title = stringResource(R.string.tr_read_ahead),
        description = if (readAhead > 0) {
            stringResource(R.string.setup_done_read_ahead_desc, readAhead, perChapter, readAheadPreset.label)
        } else {
            stringResource(R.string.setup_done_read_ahead_desc_off)
        },
        checked = readAhead > 0,
    ) { on ->
        val count = SetupChoices.readAheadAfterToggle(on, SecurePrefs.getReadAheadChapters(context), SecurePrefs.DEFAULT_READ_AHEAD_CHAPTERS)
        SecurePrefs.setReadAheadChapters(context, count)
        if (count == 0) TranslationForegroundService.stopReadAhead(context)
        readAhead = count
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val activityContext = LocalContext.current
    var libraryCheck by remember { mutableStateOf(LibraryUpdateSettings.frequency(context) != UpdateFrequency.Off) }
    ToggleRow(
        title = stringResource(R.string.setup_done_library_check),
        description = stringResource(R.string.setup_done_library_check_desc),
        checked = libraryCheck,
    ) { on ->
        val frequency = if (on) UpdateFrequency.DEFAULT else UpdateFrequency.Off
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(LibraryUpdateSettings.KEY_FREQUENCY, frequency.key)
        }
        LibraryUpdateScheduler.schedule(context, frequency)
        libraryCheck = on
        if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(activityContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            try {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } catch (t: Throwable) {
                logError(t)
            }
        }
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onSpending) {
            Icon(painterResource(R.drawable.ic_spend_24), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.setup_done_spending))
        }
        TextButton(onClick = onSettings) { Text(stringResource(R.string.setup_done_all_settings)) }
    }
}

@Composable
private fun ToggleRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = checked, onClick = { onChange(!checked) }, role = Role.Switch),
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp, top = 4.dp, bottom = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
