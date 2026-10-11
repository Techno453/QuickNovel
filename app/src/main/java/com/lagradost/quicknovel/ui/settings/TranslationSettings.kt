package com.lagradost.quicknovel.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.lagradost.quicknovel.CommonActivity.showToast
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.translator.SecurePrefs
import com.lagradost.quicknovel.translator.TranslationEngine
import com.lagradost.quicknovel.ui.common.TitleTranslation
import com.lagradost.quicknovel.util.GoogleTranslate
import com.mihon.common.preference.PreferenceData
import com.mihon.presentation.settings.Preference
import com.mihon.presentation.settings.widget.TextPreferenceWidget
import kotlinx.collections.immutable.persistentMapOf
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

    private val MODELS = persistentMapOf(
        SecurePrefs.MODEL_GPT4O_MINI to "gpt-4o-mini (fast & cheap)",
        SecurePrefs.MODEL_GPT4O to "gpt-4o (best quality)",
    )

    private fun secureBoolean(
        context: Context,
        key: String,
        default: Boolean,
        read: (Context) -> Boolean,
        write: (Context, Boolean) -> Unit,
    ) = AccessorPreferenceData(key, default, { read(context) }, { write(context, it) })

    /** The "AI Translation" settings group. Add new SecurePrefs-backed items to the list below. */
    @Composable
    fun preferenceGroup(): Preference.PreferenceGroup {
        val context = LocalContext.current.applicationContext
        val translateIcon = painterResource(R.drawable.ic_baseline_translate_24)

        val model = remember {
            AccessorPreferenceData(
                "translator_model", SecurePrefs.MODEL_GPT4O_MINI,
                { SecurePrefs.getModel(context) }, { SecurePrefs.setModel(context, it) })
        }
        val enabled = remember {
            secureBoolean(
                context, "translator_enabled", true,
                SecurePrefs::isTranslationEnabled, SecurePrefs::setTranslationEnabled
            )
        }
        val titles = remember {
            secureBoolean(
                context, "translator_gtranslate_titles", false,
                SecurePrefs::isGoogleTranslateTitlesEnabled,
                SecurePrefs::setGoogleTranslateTitlesEnabled
            )
        }

        val items: List<Preference.PreferenceItem<out Any?, out Any?>> = listOf(
            Preference.PreferenceItem.CustomPreference(
                title = "OpenAI API key",
                content = { ApiKeyPreference(context) },
            ),
            Preference.PreferenceItem.ListPreference(
                icon = translateIcon,
                title = "Translation model",
                preference = model,
                entries = MODELS,
            ),
            Preference.PreferenceItem.SwitchPreference(
                icon = translateIcon,
                title = "Enable translation UI",
                subtitle = "Show the Translate button for supported sources",
                preference = enabled,
            ),
            Preference.PreferenceItem.SwitchPreference(
                icon = translateIcon,
                title = "Auto-translate titles & synopses",
                subtitle = "Use Google Translate (free) for titles, chapter names and synopses",
                preference = titles,
                onValueChanged = {
                    GoogleTranslate.clearCache()
                    TitleTranslation.invalidate()
                    true
                },
            ),
        )

        return Preference.PreferenceGroup(
            title = GROUP_TITLE,
            preferenceItems = items.toPersistentList(),
        )
    }

    @Composable
    private fun ApiKeyPreference(context: Context) {
        val scope = rememberCoroutineScope()
        var savedKey by remember { mutableStateOf(SecurePrefs.getApiKey(context)) }
        var showDialog by remember { mutableStateOf(false) }

        TextPreferenceWidget(
            title = "OpenAI API key",
            subtitle = savedKey?.let { "Set (…${it.takeLast(4)})" } ?: "Not set",
            icon = painterResource(R.drawable.ic_baseline_translate_24),
            onPreferenceClick = { showDialog = true },
        )

        if (!showDialog) return
        var text by remember { mutableStateOf(savedKey ?: "") }
        var verifying by remember { mutableStateOf(false) }
        val dismiss = { showDialog = false }

        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("OpenAI API key") },
            text = {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text("sk-...") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val key = text.trim()
                    SecurePrefs.setApiKey(context, key)
                    savedKey = key.ifBlank { null }
                    showToast(if (key.isNotBlank()) "API key saved" else "API key cleared")
                    dismiss()
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        enabled = !verifying,
                        onClick = {
                            val key = text.trim()
                            if (key.isBlank()) {
                                showToast("Enter an API key first")
                                return@TextButton
                            }
                            verifying = true
                            scope.launch {
                                val valid = try {
                                    TranslationEngine(key, SecurePrefs.getModel(context)).testApiKey()
                                } finally {
                                    verifying = false
                                }
                                showToast(if (valid) "API key is valid!" else "Invalid API key")
                            }
                        }) { Text(if (verifying) "Verifying…" else "Verify") }
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
    }
}
