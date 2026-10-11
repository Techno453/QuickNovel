package com.lagradost.quicknovel.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.lagradost.quicknovel.translator.SecurePrefs
import com.lagradost.quicknovel.translator.TranslationLanguages
import com.lagradost.quicknovel.util.GoogleTranslate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** UI-level (Google Translate) title translation for Compose lists. */
object TitleTranslation {
    /**
     * Source language whose titles should be machine-translated for this provider, or null.
     * Gated on [TranslationLanguages] (zh/ja/ko).
     */
    fun sourceLangForApi(apiName: String): String? = TranslationLanguages.langOfApi(apiName)
}

/**
 * The title to display for [response]: the original name immediately, swapped for the
 * Google-translated name once available when the source needs translation and the setting is on.
 */
@Composable
fun rememberDisplayName(response: ImmutableSearchResponse): State<String> {
    val context = LocalContext.current.applicationContext
    return produceState(initialValue = response.name, response.name, response.apiName) {
        value = response.name
        val lang = TitleTranslation.sourceLangForApi(response.apiName) ?: return@produceState
        // First read may load the encrypted prefs, so keep it off the main thread.
        val enabled = withContext(Dispatchers.IO) {
            SecurePrefs.isGoogleTranslateTitlesEnabled(context)
        }
        if (!enabled) return@produceState
        value = GoogleTranslate.translateTo(
            response.name,
            from = TranslationLanguages.googleCode(lang),
            to = "en"
        )
    }
}
