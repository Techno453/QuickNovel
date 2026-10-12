package com.lagradost.quicknovel.translator.db

import androidx.room.ColumnInfo
import androidx.room.Entity

/** Short summary of a translated chapter, fed to the next chapter as continuity context. */
@Entity(
    tableName = "chapter_summaries",
    primaryKeys = ["novel_id", "chapter_num"]
)
data class ChapterSummary(
    @ColumnInfo(name = "novel_id") val novelId: String,
    @ColumnInfo(name = "chapter_num") val chapterNum: Int,
    val summary: String,
    /** JSON array of source-language names of the characters present. */
    @ColumnInfo(name = "characters_present", defaultValue = "[]") val charactersPresent: String = "[]",
)
