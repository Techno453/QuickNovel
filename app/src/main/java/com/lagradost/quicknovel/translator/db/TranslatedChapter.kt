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
    @ColumnInfo(name = "source_lang", defaultValue = "zh") val sourceLang: String = "zh",
    /** Story Bible terms whose rendering was missing from the output (null = not checked / pre-v3). */
    @ColumnInfo(name = "glossary_violations") val glossaryViolations: Int? = null,
    /** Untranslated CJK fragments left in the output. */
    @ColumnInfo(name = "cjk_leaks") val cjkLeaks: Int? = null,
    /** Chunks whose output had fewer than 70% of the input paragraphs. */
    @ColumnInfo(name = "paragraph_warnings") val paragraphWarnings: Int? = null,
    /** True if some passages were re-translated by the fallback model ([model] records the model(s) used). */
    @ColumnInfo(name = "used_fallback", defaultValue = "0") val usedFallback: Boolean = false,
    /**
     * SHA-256 hex of the normalised plain source text this translation was made from
     * ([com.lagradost.quicknovel.translator.SourceHash]); "" = unknown (rows from before DB v6), treated as valid.
     */
    @ColumnInfo(name = "source_hash", defaultValue = "") val sourceHash: String = "",
    /** The source changed since this translation was made (still readable; never re-translated automatically). */
    @ColumnInfo(defaultValue = "0") val stale: Boolean = false,
    /**
     * Made by the result page's Preview (no glossary, DB v9). Served only inside a preview; for
     * everything else the chapter counts as untranslated and the normal path overwrites the row.
     */
    @ColumnInfo(defaultValue = "0") val preview: Boolean = false,
)
