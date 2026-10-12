package com.lagradost.quicknovel.ui.glossary

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.quicknovel.translator.NameReplacer
import com.lagradost.quicknovel.translator.StoryBibleJson
import com.lagradost.quicknovel.translator.StoryBibleRepository
import com.lagradost.quicknovel.translator.TermAliases
import com.lagradost.quicknovel.translator.TranslationLanguages
import com.lagradost.quicknovel.translator.db.GlossaryTerm
import com.lagradost.quicknovel.translator.db.NovelContext
import com.lagradost.quicknovel.translator.db.PendingTerm
import com.lagradost.quicknovel.translator.db.TranslatorDatabase
import com.lagradost.quicknovel.ui.translate.NovelChapterSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PresetTerm(
    val chinese: String,
    val english: String,
    val category: String
)

/** List filter: category (null = all), free-text query, and "unreviewed auto-added only". */
data class GlossaryFilter(
    val category: String? = null,
    val query: String = "",
    val unreviewedOnly: Boolean = false,
) {
    fun matches(term: GlossaryTerm): Boolean {
        if (unreviewedOnly && !(term.autoAdded && !term.locked)) return false
        if (category != null && GlossaryTerm.normalizeCategory(term.category) != category) return false
        val q = query.trim()
        if (q.isEmpty()) return true
        return term.source.contains(q, ignoreCase = true) ||
            term.english.contains(q, ignoreCase = true) ||
            term.effectiveNotes.contains(q, ignoreCase = true) ||
            TermAliases.parse(term.aliases).any { it.contains(q, ignoreCase = true) }
    }
}

/** A locked character was renamed; offer to update stored translations. */
data class RenameOffer(val oldEnglish: String, val newEnglish: String, val chapterCount: Int)

class GlossaryViewModel(application: Application) : AndroidViewModel(application) {
    private val db = TranslatorDatabase.getInstance(application)
    private val glossaryDao = db.glossaryDao()
    private val pendingTermDao = db.pendingTermDao()
    private val translationDao = db.translationDao()
    private val mapper = jacksonObjectMapper()

    var novelId: String = ""
        private set

    /** Provider language (zh/ja/ko) derived from the novel id, or null. */
    var sourceLang: String? = null
        private set

    val terms: MutableLiveData<List<GlossaryTerm>> = MutableLiveData(emptyList())
    val visibleTerms: MutableLiveData<List<GlossaryTerm>> = MutableLiveData(emptyList())
    val filter: MutableLiveData<GlossaryFilter> = MutableLiveData(GlossaryFilter())
    val pendingTerms: MutableLiveData<List<PendingTerm>> = MutableLiveData(emptyList())
    val pendingCount: MutableLiveData<Int> = MutableLiveData(0)
    val novelContext: MutableLiveData<NovelContext?> = MutableLiveData(null)
    val renameOffer: MutableLiveData<RenameOffer?> = MutableLiveData(null)

    private var collectJob: Job? = null

    fun init(novelId: String) {
        if (this.novelId == novelId && collectJob?.isActive == true) return
        this.novelId = novelId
        sourceLang = NovelChapterSource.langOf(novelId)
        filter.value = GlossaryFilter()
        collectJob?.cancel()
        collectJob = viewModelScope.launch {
            glossaryDao.getTermsForNovel(novelId).collectLatest { list ->
                val sorted = list.sortedWith(
                    compareBy<GlossaryTerm>(
                        { GlossaryTerm.CATEGORIES.indexOf(GlossaryTerm.normalizeCategory(it.category)) },
                        { it.english.lowercase() }
                    )
                )
                terms.postValue(sorted)
                visibleTerms.postValue(sorted.filter { (filter.value ?: GlossaryFilter()).matches(it) })
            }
        }
        loadPendingTerms()
        loadContext()
    }

    fun setFilter(newFilter: GlossaryFilter) {
        filter.value = newFilter
        visibleTerms.value = terms.value.orEmpty().filter { newFilter.matches(it) }
    }

    fun loadPendingTerms() {
        viewModelScope.launch {
            val pending = withContext(Dispatchers.IO) { pendingTermDao.getPendingTerms(novelId) }
            pendingTerms.postValue(pending)
            pendingCount.postValue(pending.size)
        }
    }

    fun loadContext() {
        viewModelScope.launch {
            novelContext.postValue(withContext(Dispatchers.IO) { db.novelContextDao().get(novelId) })
        }
    }

    /**
     * Saves a new or edited term. When the English name of a locked character changes and older
     * translations contain the old name, [renameOffer] is posted.
     */
    fun saveTerm(term: GlossaryTerm, previous: GlossaryTerm?) {
        viewModelScope.launch(Dispatchers.IO) {
            glossaryDao.upsertTerm(term)
            if (previous != null && (previous.locked || term.locked) && term.isCharacter &&
                previous.english.isNotBlank() && previous.english != term.english
            ) {
                val count = translationDao.getAllForNovel(novelId)
                    .count { NameReplacer.countOccurrences(it.englishText, previous.english) > 0 }
                if (count > 0) renameOffer.postValue(RenameOffer(previous.english, term.english, count))
            }
        }
    }

    fun clearRenameOffer() {
        renameOffer.value = null
    }

    /** Replaces the old name in stored translations; returns the number of chapters changed. */
    suspend fun applyRename(offer: RenameOffer): Int = withContext(Dispatchers.IO) {
        var changed = 0
        for (chapter in translationDao.getAllForNovel(novelId)) {
            val updated = NameReplacer.replace(chapter.englishText, offer.oldEnglish, offer.newEnglish)
            if (updated != chapter.englishText) {
                translationDao.updateText(novelId, chapter.chapterNum, updated)
                changed++
            }
        }
        changed
    }

    fun deleteTerm(term: GlossaryTerm) {
        viewModelScope.launch(Dispatchers.IO) {
            glossaryDao.deleteTerm(term)
        }
    }

    /** Marks the given terms reviewed and locked. */
    fun lockTerms(list: List<GlossaryTerm>) {
        if (list.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            glossaryDao.lockTerms(list.map { it.id })
        }
    }

    /**
     * Approves a pending term (user-confirmed → locked). If the source already exists (a conflict),
     * the existing entry is updated with the approved rendering instead of adding a duplicate.
     */
    private suspend fun approve(pending: PendingTerm, english: String, category: String, gender: String, notes: String) {
        val existing = glossaryDao.getBySource(novelId, pending.source)
        val cleanNotes = notes.takeUnless { it.startsWith("conflicts with existing") || it.startsWith("same English as existing") }
            .orEmpty()
        val term = (existing ?: GlossaryTerm(novelId = novelId, source = pending.source, english = english, firstChapter = pending.chapterNum))
            .copy(
                english = english,
                category = category,
                gender = gender,
                notes = cleanNotes.ifBlank { existing?.notes.orEmpty() },
                autoAdded = false,
                locked = true,
            )
        glossaryDao.upsertTerm(term)
        pendingTermDao.deleteById(pending.id)
    }

    fun approvePendingTerm(pending: PendingTerm, english: String, category: String, gender: String, notes: String) {
        viewModelScope.launch(Dispatchers.IO) {
            approve(pending, english, category, gender, notes)
            loadPendingTerms()
        }
    }

    fun approveAllPending() {
        viewModelScope.launch(Dispatchers.IO) {
            for (p in pendingTermDao.getPendingTerms(novelId)) {
                approve(p, p.suggestedEnglish, GlossaryTerm.normalizeCategory(p.category), p.gender, p.notes)
            }
            loadPendingTerms()
        }
    }

    fun rejectPendingTerm(pending: PendingTerm) {
        viewModelScope.launch(Dispatchers.IO) {
            pendingTermDao.deleteById(pending.id)
            loadPendingTerms()
        }
    }

    fun rejectAllPending() {
        viewModelScope.launch(Dispatchers.IO) {
            pendingTermDao.deleteAll(novelId)
            loadPendingTerms()
        }
    }

    suspend fun exportJson(): String = withContext(Dispatchers.IO) {
        StoryBibleJson.export(glossaryDao.getAllTermsSync(novelId), db.novelContextDao().get(novelId))
    }

    /** @throws IllegalArgumentException if [json] is not an export */
    fun parseImport(json: String): StoryBibleJson.Imported = StoryBibleJson.import(json, novelId)

    /** Imported terms replace existing ones with the same source; context fields fill in when present. */
    suspend fun applyImport(imported: StoryBibleJson.Imported): Int = withContext(Dispatchers.IO) {
        val existing = glossaryDao.getAllTermsSync(novelId).associateBy { it.source }
        glossaryDao.upsertTerms(imported.terms.map { t -> t.copy(id = existing[t.source]?.id ?: 0) })
        imported.context?.let { ctx ->
            StoryBibleRepository.updateContext(getApplication(), novelId) { cur ->
                cur.copy(
                    premise = ctx.premise.ifBlank { cur.premise },
                    styleNotes = ctx.styleNotes.ifBlank { cur.styleNotes },
                    nameStyle = ctx.nameStyle.ifBlank { cur.nameStyle },
                    honorificStyle = ctx.honorificStyle.ifBlank { cur.honorificStyle },
                )
            }
            loadContext()
        }
        imported.terms.size
    }

    fun saveStyle(nameStyle: String, honorificStyle: String, styleNotes: String, premise: String) {
        viewModelScope.launch(Dispatchers.IO) {
            StoryBibleRepository.updateContext(getApplication(), novelId) {
                it.copy(nameStyle = nameStyle, honorificStyle = honorificStyle, styleNotes = styleNotes, premise = premise)
            }
            loadContext()
        }
    }

    /** Adds the bundled cultivation presets (locked, skipping sources already present). */
    fun loadPreset(context: Context): Int {
        return try {
            val json = context.assets.open("common_cultivation_terms.json")
                .bufferedReader().readText()
            val presets: List<PresetTerm> = mapper.readValue(json)
            val presetTerms = presets.map { preset ->
                GlossaryTerm(
                    novelId = novelId,
                    source = preset.chinese,
                    english = preset.english,
                    category = GlossaryTerm.normalizeCategory(preset.category),
                    locked = true,
                )
            }
            viewModelScope.launch(Dispatchers.IO) {
                val existing = glossaryDao.getAllTermsSync(novelId).map { it.source }.toHashSet()
                glossaryDao.upsertTerms(presetTerms.filter { it.source !in existing })
            }
            presetTerms.size
        } catch (e: Exception) {
            -1
        }
    }
}
