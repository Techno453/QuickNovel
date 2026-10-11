package com.lagradost.quicknovel.ui.glossary

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.quicknovel.translator.db.GlossaryTerm
import com.lagradost.quicknovel.translator.db.PendingTerm
import com.lagradost.quicknovel.translator.db.TranslatorDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PresetTerm(
    val chinese: String,
    val english: String,
    val category: String
)

class GlossaryViewModel(application: Application) : AndroidViewModel(application) {
    private val db = TranslatorDatabase.getInstance(application)
    private val glossaryDao = db.glossaryDao()
    private val pendingTermDao = db.pendingTermDao()
    private val mapper = jacksonObjectMapper()

    var novelId: String = ""

    val terms: MutableLiveData<List<GlossaryTerm>> = MutableLiveData(emptyList())
    val pendingTerms: MutableLiveData<List<PendingTerm>> = MutableLiveData(emptyList())
    val pendingCount: MutableLiveData<Int> = MutableLiveData(0)

    fun init(novelId: String) {
        this.novelId = novelId
        viewModelScope.launch {
            glossaryDao.getTermsForNovel(novelId).collectLatest { list ->
                terms.postValue(list)
            }
        }
        loadPendingTerms()
    }

    fun loadPendingTerms() {
        viewModelScope.launch {
            val pending = withContext(Dispatchers.IO) {
                pendingTermDao.getPendingTerms(novelId)
            }
            pendingTerms.postValue(pending)
            pendingCount.postValue(pending.size)
        }
    }

    fun addTerm(term: GlossaryTerm) {
        viewModelScope.launch(Dispatchers.IO) {
            glossaryDao.upsertTerm(term)
        }
    }

    fun deleteTerm(term: GlossaryTerm) {
        viewModelScope.launch(Dispatchers.IO) {
            glossaryDao.deleteTerm(term)
        }
    }

    fun approvePendingTerm(pending: PendingTerm, english: String, category: String) {
        viewModelScope.launch(Dispatchers.IO) {
            glossaryDao.upsertTerm(
                GlossaryTerm(
                    novelId = novelId,
                    chinese = pending.chinese,
                    english = english,
                    category = category
                )
            )
            pendingTermDao.deleteById(pending.id)
            loadPendingTerms()
        }
    }

    fun rejectPendingTerm(pending: PendingTerm) {
        viewModelScope.launch(Dispatchers.IO) {
            pendingTermDao.deleteById(pending.id)
            loadPendingTerms()
        }
    }

    fun exportJson(): String {
        val list = terms.value ?: emptyList()
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(list)
    }

    fun importJson(json: String): Int {
        return try {
            val imported: List<GlossaryTerm> = mapper.readValue(json)
            val withNovelId = imported.map { it.copy(id = 0, novelId = novelId) }
            viewModelScope.launch(Dispatchers.IO) {
                glossaryDao.upsertTerms(withNovelId)
            }
            withNovelId.size
        } catch (e: Exception) {
            -1
        }
    }

    fun loadPreset(context: Context): Int {
        return try {
            val json = context.assets.open("common_cultivation_terms.json")
                .bufferedReader().readText()
            val presets: List<PresetTerm> = mapper.readValue(json)
            val terms = presets.map { preset ->
                GlossaryTerm(
                    novelId = novelId,
                    chinese = preset.chinese,
                    english = preset.english,
                    category = preset.category
                )
            }
            viewModelScope.launch(Dispatchers.IO) {
                glossaryDao.upsertTerms(terms)
            }
            terms.size
        } catch (e: Exception) {
            -1
        }
    }
}
