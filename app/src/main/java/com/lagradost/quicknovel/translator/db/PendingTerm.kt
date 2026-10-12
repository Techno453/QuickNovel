package com.lagradost.quicknovel.translator.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** A term the model discovered that needs review (low confidence or conflicting rendering). */
@Entity(tableName = "pending_terms")
data class PendingTerm(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "novel_id") val novelId: String,
    /** Source-language term; the column keeps the historical name "chinese". */
    @ColumnInfo(name = "chinese") val source: String,
    @ColumnInfo(name = "suggested_english") val suggestedEnglish: String,
    val category: String = "other",
    val context: String = "",
    @ColumnInfo(name = "chapter_num") val chapterNum: Int = 0,
    @ColumnInfo(defaultValue = GlossaryTerm.GENDER_UNKNOWN) val gender: String = GlossaryTerm.GENDER_UNKNOWN,
    @ColumnInfo(defaultValue = "") val notes: String = "",
    /** Model's self-reported confidence, 0..1. */
    @ColumnInfo(defaultValue = "0.5") val confidence: Float = 0.5f,
)
