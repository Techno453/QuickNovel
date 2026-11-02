package com.lagradost.quicknovel.translator.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * A term the translator reported in one chapter only (docs/translation-pipeline.md "Terms found
 * while translating"). It stays out of the glossary until its source turns up in another
 * chapter; then it is promoted with this rendering (auto-added, or pending review when its
 * confidence is low or auto-add is off) and the row is removed. Never shown or exported.
 */
@Entity(
    tableName = "term_candidates",
    indices = [Index(value = ["novel_id", "source"], unique = true)]
)
data class TermCandidate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "novel_id") val novelId: String,
    val source: String,
    val english: String,
    val category: String = GlossaryTerm.CATEGORY_OTHER,
    val gender: String = GlossaryTerm.GENDER_UNKNOWN,
    val notes: String = "",
    /** JSON array of alternate source forms, as in [GlossaryTerm.aliases]. */
    val aliases: String = "[]",
    /** Chapter the term was reported in. */
    @ColumnInfo(name = "chapter_num") val chapterNum: Int,
    /** Model's self-reported confidence, 0..1. */
    val confidence: Float = 0.5f,
)

@Dao
interface TermCandidateDao {
    @Query("SELECT * FROM term_candidates WHERE novel_id = :novelId")
    suspend fun getAll(novelId: String): List<TermCandidate>

    @Query("SELECT * FROM term_candidates WHERE novel_id = :novelId AND source = :source LIMIT 1")
    suspend fun getBySource(novelId: String, source: String): TermCandidate?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(candidate: TermCandidate): Long

    @Query("DELETE FROM term_candidates WHERE novel_id = :novelId AND source IN (:sources)")
    suspend fun deleteSources(novelId: String, sources: List<String>)

    @Query("DELETE FROM term_candidates WHERE novel_id = :novelId")
    suspend fun deleteAll(novelId: String)
}
