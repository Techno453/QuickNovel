package com.lagradost.quicknovel.translator.db

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "translated_chapters",
    primaryKeys = ["novel_id", "chapter_num"]
)
data class TranslatedChapter(
    @ColumnInfo(name = "novel_id") val novelId: String,
    @ColumnInfo(name = "chapter_num") val chapterNum: Int,
    @ColumnInfo(name = "english_text") val englishText: String,
    @ColumnInfo(name = "translated_at") val translatedAt: Long = System.currentTimeMillis(),
    val model: String = "gpt-4o-mini",
    /** ISO 639-1 source language (zh/ja/ko). Rows created before DB v2 are Chinese. */
    @ColumnInfo(name = "source_lang", defaultValue = "zh") val sourceLang: String = "zh"
)
