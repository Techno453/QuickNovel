package com.lagradost.quicknovel.translator.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.fasterxml.jackson.annotation.JsonProperty

@Entity(
    tableName = "glossary_terms",
    indices = [Index("novel_id")]
)
data class GlossaryTerm(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "novel_id") val novelId: String,
    /**
     * The source-language term (Chinese, Japanese or Korean). The column and JSON key keep
     * the historical name "chinese" for backward compatibility with existing DBs and exports.
     */
    @ColumnInfo(name = "chinese") @JsonProperty("chinese") val source: String,
    val english: String,
    val category: String, // character, location, rank, technique, sect, artifact, creature, title, other
    val description: String = "",
    val aliases: String = "[]" // JSON array of alternate source-language strings
)
