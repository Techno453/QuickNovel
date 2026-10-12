package com.lagradost.quicknovel.translator

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.lagradost.quicknovel.BaseApplication
import com.lagradost.quicknovel.translator.db.NovelMetadataTranslation
import com.lagradost.quicknovel.translator.db.TranslatorDatabase
import com.lagradost.quicknovel.util.GoogleTranslate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

/**
 * The app's [MetadataTranslator]: translated titles and synopses for Browse, search, the library,
 * the preview sheet and the result page (docs/browse.md).
 *
 * Engine choice ([policy]): the configured translation model through the OpenAI-compatible
 * [TranslationEngine] when "Translate titles and synopses with AI" is on (default), an API key is
 * set and the monthly spend cap is not reached; otherwise the free Google endpoint when
 * "Auto-translate titles & synopses" is on. AI failures fall back to Google. Calls are recorded
 * in the spend ledger as [SpendKind.METADATA].
 */
object MetadataTranslations {
    private const val TAG = "MetadataTranslations"

    /** Default-preferences key of the AI setting (not secret, so not in [SecurePrefs]). */
    const val KEY_AI_METADATA = "translate_metadata_with_ai"

    fun isAiEnabled(context: Context?): Boolean {
        val ctx = (context ?: BaseApplication.context)?.applicationContext ?: return true
        return PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(KEY_AI_METADATA, true)
    }

    fun setAiEnabled(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext).edit()
            .putBoolean(KEY_AI_METADATA, enabled).apply()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val appContext: Context? get() = BaseApplication.context?.applicationContext

    private val store = object : MetadataStore {
        override suspend fun load(ids: List<String>): List<NovelMetadataTranslation> {
            val ctx = appContext ?: return emptyList()
            return ids.chunked(500).flatMap { TranslatorDatabase.getInstance(ctx).novelMetadataDao().get(it) }
        }

        override suspend fun save(rows: List<NovelMetadataTranslation>) {
            val ctx = appContext ?: return
            TranslatorDatabase.getInstance(ctx).novelMetadataDao().upsert(rows)
        }
    }

    private val backend = object : MetadataBackend {
        override suspend fun policy(): MetadataPolicy = withContext(Dispatchers.IO) { currentPolicy(appContext) }

        override fun aiModel(): String = SecurePrefs.getModel(appContext)

        override suspend fun aiTitles(lang: String, titles: List<String>): List<String?> {
            val engine = TranslationEngine(SecurePrefs.engineConfig(appContext), lang)
            val reply = withContext(SpendTag(SpendKind.METADATA)) {
                engine.chat(
                    MetadataPrompts.titlesSystemPrompt(lang),
                    MetadataPrompts.titlesUserPrompt(titles),
                    temperature = 0.0,
                    maxRetries = 1,
                )
            }
            return MetadataPrompts.parseTitles(reply.content, titles.size)
        }

        override suspend fun aiNovel(lang: String, novelId: String, title: String, synopsis: String): Pair<String?, String?> {
            val engine = TranslationEngine(SecurePrefs.engineConfig(appContext), lang)
            val reply = withContext(SpendTag(SpendKind.METADATA, novelId = novelId)) {
                engine.chat(
                    MetadataPrompts.novelSystemPrompt(lang),
                    MetadataPrompts.novelUserPrompt(title, synopsis),
                    temperature = 0.0,
                    maxRetries = 1,
                )
            }
            return MetadataPrompts.parseNovel(reply.content)
        }

        override suspend fun google(lang: String, text: String): String? =
            GoogleTranslate.translateTo(text, from = TranslationLanguages.googleCode(lang), to = "en")
                .takeIf { it != text }
    }

    val translator: MetadataTranslator by lazy { MetadataTranslator(store, backend, scope) }

    /** Engines allowed right now (reads the encrypted prefs: call off the main thread the first time). */
    suspend fun currentPolicy(context: Context?): MetadataPolicy {
        val google = SecurePrefs.isGoogleTranslateTitlesEnabled(context)
        val ai = isAiEnabled(context) && SecurePrefs.hasApiKey(context) && !monthlyCapReached(context)
        return MetadataPolicy(ai = ai, google = google || ai)
    }

    private suspend fun monthlyCapReached(context: Context?): Boolean {
        val ctx = context ?: return false
        val caps = SecurePrefs.spendCaps(ctx)
        if (!caps.monthlyOn) return false
        return try {
            caps.stopReason(SpendLedger.monthTotal(ctx), 0.0) != null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not check the spend cap", e)
            false
        }
    }

    /**
     * Whether titles/synopses are translated at all (Google option on, or AI on with a key).
     * When false the UI shows originals only, even if translations are cached.
     */
    fun anyEnabled(context: Context?): Boolean =
        SecurePrefs.isGoogleTranslateTitlesEnabled(context) || (isAiEnabled(context) && SecurePrefs.hasApiKey(context))

    /** Source language to translate titles of [apiName] from, or null (not a zh/ja/ko source). */
    fun langOf(apiName: String): String? = TranslationLanguages.langOfApi(apiName)

    /** Cached English title, without suspending; null when not translated yet. */
    fun cachedTitle(apiName: String, name: String): String? =
        translator.cachedTitle(TranslatorNovelId.from(apiName, name), name)

    /** English title for a listing item (batched with the other visible items); null if none. */
    suspend fun title(apiName: String, name: String): String? {
        val lang = langOf(apiName) ?: return null
        return translator.title(MetadataTranslator.TitleRequest(TranslatorNovelId.from(apiName, name), lang, name))
    }

    /** English title and synopsis of an opened novel (preview sheet, result page). */
    /** [lang] overrides the API lookup for books without a source API (imported EPUB/TXT). */
    suspend fun novel(apiName: String, name: String, synopsis: String?, lang: String? = langOf(apiName)): MetadataTranslator.Translated? {
        lang ?: return null
        return translator.novel(TranslatorNovelId.from(apiName, name), lang, name, synopsis)
    }
}
