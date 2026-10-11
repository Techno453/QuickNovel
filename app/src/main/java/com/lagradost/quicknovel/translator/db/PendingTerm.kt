package com.lagradost.quicknovel.translator.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_terms")
data class PendingTerm(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "novel_id") val novelId: String,
    val chinese: String,
    @ColumnInfo(name = "suggested_english") val suggestedEnglish: String,
    val category: String = "other",
    val context: String = "",
    @ColumnInfo(name = "chapter_num") val chapterNum: Int = 0
)
