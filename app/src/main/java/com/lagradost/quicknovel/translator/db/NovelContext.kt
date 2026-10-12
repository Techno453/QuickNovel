package com.lagradost.quicknovel.translator.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/** Per-novel translation context: story premise, translation style and analysis state. */
@JsonIgnoreProperties(ignoreUnknown = true)
@Entity(tableName = "novel_context")
data class NovelContext(
    @PrimaryKey @ColumnInfo(name = "novel_id") val novelId: String = "",
    /** 3–5 sentence story summary, built by the "Analyze novel" pre-pass (user-editable). */
    @ColumnInfo(defaultValue = "") val premise: String = "",
    /** Free-text style instructions from the user. */
    @ColumnInfo(name = "style_notes", defaultValue = "") val styleNotes: String = "",
    /** Name style (see StoryStyle); blank = global default for the source language. */
    @ColumnInfo(name = "name_style", defaultValue = "") val nameStyle: String = "",
    /** "keep", "drop" or "adapt"; blank = global default for the source language. */
    @ColumnInfo(name = "honorific_style", defaultValue = "") val honorificStyle: String = "",
    @ColumnInfo(name = "updated_at", defaultValue = "0") val updatedAt: Long = 0,
    /**
     * When the "Analyze novel" pre-pass last succeeded (epoch ms); 0 = never, so the next
     * translation runs it first (see NovelAnalyzer). Not exported with the Story Bible.
     */
    @ColumnInfo(name = "analyzed_at", defaultValue = "0") @get:JsonIgnore val analyzedAt: Long = 0,
    /** Number of chapters that analysis read (0 = never analyzed or unknown). Not exported. */
    @ColumnInfo(name = "analysis_chapters", defaultValue = "0") @get:JsonIgnore val analysisChapters: Int = 0,
) {
    @get:JsonIgnore
    val isAnalyzed: Boolean get() = analyzedAt > 0
}
