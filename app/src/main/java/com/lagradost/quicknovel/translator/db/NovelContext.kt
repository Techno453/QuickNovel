package com.lagradost.quicknovel.translator.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/** Per-novel translation context: story premise and translation style. */
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
)
