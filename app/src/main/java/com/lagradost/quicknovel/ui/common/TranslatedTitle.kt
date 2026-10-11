package com.lagradost.quicknovel.ui.common

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.lagradost.quicknovel.translator.SecurePrefs
import com.lagradost.quicknovel.util.Apis
import com.lagradost.quicknovel.util.GoogleTranslate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** UI-level (Google Translate) title translation for Compose lists. */
object TitleTranslation {
    /** Whether titles coming from a provider with this language should be machine-translated. */
    fun needsTranslation(lang: String?): Boolean = lang == "zh"

    fun needsTranslationForApi(apiName: String): Boolean =
        needsTranslation(Apis.getApiFromNameNull(apiName)?.lang)

    @Volatile
    private var enabledCache: Boolean? = null

    /** Call when the "auto-translate titles" setting changes. */
    fun invalidate() {
        enabledCache = null
    }

    /** Reads the (encrypted) setting once and caches it; call off the main thread. */
    fun isEnabled(context: Context): Boolean =
        enabledCache ?: SecurePrefs.isGoogleTranslateTitlesEnabled(context).also { enabledCache = it }
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
        if (!TitleTranslation.needsTranslationForApi(response.apiName)) return@produceState
        val enabled = withContext(Dispatchers.IO) { TitleTranslation.isEnabled(context) }
        if (!enabled) return@produceState
        value = GoogleTranslate.translate(response.name)
    }
}
