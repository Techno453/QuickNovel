package com.lagradost.quicknovel.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.lagradost.quicknovel.translator.MetadataTranslations
import com.lagradost.quicknovel.translator.TranslationLanguages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** UI-level title translation for Compose lists (see [MetadataTranslations], docs/browse.md). */
object TitleTranslation {
    /**
     * Source language whose titles should be machine-translated for this provider, or null.
     * Gated on [TranslationLanguages] (zh/ja/ko).
     */
    fun sourceLangForApi(apiName: String): String? = TranslationLanguages.langOfApi(apiName)
}

/**
 * The English title of [name] on [apiName], or null while it is not available (not a zh/ja/ko
 * source, translation off, or not translated yet). Starts from the in-memory cache, never blocks:
 * visible items are batched into one translation request.
 */
@Composable
fun rememberTranslatedTitle(name: String, apiName: String): State<String?> {
    val context = LocalContext.current.applicationContext
    return produceState(
        initialValue = MetadataTranslations.cachedTitle(apiName, name),
        name, apiName,
    ) {
        if (TitleTranslation.sourceLangForApi(apiName) == null) {
            value = null
            return@produceState
        }
        // First read may load the encrypted prefs, so keep it off the main thread.
        val enabled = withContext(Dispatchers.IO) { MetadataTranslations.anyEnabled(context) }
        value = if (enabled) MetadataTranslations.title(apiName, name) else null
    }
}

@Composable
fun rememberTranslatedTitle(response: ImmutableSearchResponse): State<String?> =
    rememberTranslatedTitle(response.name, response.apiName)

/**
 * The title to display for [response]: the original name immediately, swapped for the
 * translated name once available when the source needs translation and translation is on.
 */
@Composable
fun rememberDisplayName(response: ImmutableSearchResponse): State<String> {
    val translated = rememberTranslatedTitle(response)
    return produceState(initialValue = translated.value ?: response.name, translated.value, response.name) {
        value = translated.value ?: response.name
    }
}
